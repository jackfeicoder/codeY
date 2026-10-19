package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.tool.Tool;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolDefinition;
import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult;
import io.github.jackfeicoder.codey.tool.filesystem.FileGlob;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceFiles;

import java.nio.file.Path;
import java.util.Set;

/** 按文件名或相对路径通配符定位文件，只返回路径，不读取正文、不搜索代码内容。 */
public final class SearchFileTool implements Tool {
    @Override public io.github.jackfeicoder.codey.tool.ToolAccess access() { return io.github.jackfeicoder.codey.tool.ToolAccess.READ_ONLY; }
    /** 返回路径条数的默认值与硬上限，扫描量另受共享遍历器限制。 */
    public static final int DEFAULT_MAX_RESULTS = 100;
    public static final int MAX_RESULTS = 1_000;
    /** 工具只接受定位参数，宿主工作区和输出预算不可被模型覆盖。 */
    private static final Set<String> ARGUMENT_NAMES = Set.of("pattern", "path", "maxResults", "caseSensitive");
    /** schema 与结果正文的编码器，不负责 LLM 请求编码。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 注册时发送给模型的稳定定义，无需额外工具说明 Markdown。 */
    private static final ToolDefinition DEFINITION = createDefinition();

    /** 声明名称、职责、匹配规则和参数范围。 */
    @Override
    public ToolDefinition definition() { return DEFINITION; }

    /** 仅在真实工作区会话中提供，不以进程目录冒充工作区。 */
    @Override
    public boolean requiresWorkspace() { return true; }

    /** 参数校验不访问文件系统，执行时再检查实际路径。 */
    @Override
    public void validateArguments(JsonNode arguments, ToolContext context) { options(arguments); }

    /** 扫描元数据并筛选普通文件；共享遍历器与 list_files 解耦，不调用其他工具。 */
    @Override
    public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Options options = options(arguments);
        var directory = WorkspaceFiles.directory(context, options.path());
        var results = new Results(options, WorkspaceFiles.relative(directory.root(), directory.start()), context.maxOutputChars());
        results.requireMetadataBudget();
        var scan = WorkspaceFiles.scan(directory, entry -> {
            if (!"file".equals(entry.type())) return true; // 目录、链接和其他特殊条目不作为文件命中。
            String candidate = WorkspaceFiles.relative(directory.start(), entry.path());
            return !options.matcher().matches(candidate)
                    || results.add(WorkspaceFiles.relative(directory.root(), entry.path()));
        });
        results.scan = scan;
        return results.output(false);
    }

    /** 没有 / 的 pattern 递归匹配文件名；包含 / 时相对 path 匹配，双星目录段可以匹配零层。 */
    private static ToolDefinition createDefinition() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("pattern");
        var properties = schema.putObject("properties");
        properties.putObject("pattern").put("type", "string").put("minLength", 1).put("maxLength", FileGlob.MAX_PATTERN_LENGTH)
                .put("description", "必填。文件名或相对 path 的路径模式，如 AgentLoop.java、*Config*.java、**/prompts/*.md。"
                        + "* 匹配单段内任意字符，? 匹配一个字符，独立的 ** 匹配零层或多层目录。"
                        + "不含 / 时匹配任意层的文件名；含 / 时从搜索起点匹配。不支持正则、[] 字符类或 {} 分组，它们按字面匹配。");
        properties.putObject("path").put("type", "string").put("minLength", 1).put("default", ".")
                .put("description", "搜索起点，相对工作区的目录；结果路径始终相对工作区");
        properties.putObject("maxResults").put("type", "integer").put("minimum", 1).put("maximum", MAX_RESULTS)
                .put("default", DEFAULT_MAX_RESULTS).put("description", "最多返回多少条文件路径");
        properties.putObject("caseSensitive").put("type", "boolean").put("default", false)
                .put("description", "是否区分大小写；行为在 Windows/Linux 上一致，结果保留实际文件名");
        return new ToolDefinition("search_file", "按文件名或路径通配符定位工作区文件，只返回匹配路径，不读取文件正文。"
                + "知道文件名或命名特征时优先使用本工具；浏览目录用 list_files，阅读具体内容用 read_file。"
                + "从搜索起点遍历至目录末端，不设人为递归深度；默认返回 100 条，扫描最多 10000 项。"
                + "跳过 .git、target、node_modules、.gradle、build、dist 目录。"
                + "不跟随发现的符号链接或 junction，不返回链接或目录；可显式指定被忽略目录为 path 查看其内容。"
                + "结果按每层名称排序并深度优先；截断时根据 limitReason 缩小 path 或调整合法参数，不代表已经搜索全部文件。", schema);
    }

    /** 拒绝未知键、错误类型、非法范围和不受支持的模式结构。 */
    private static Options options(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) throw invalid("search_file arguments must be an object.");
        var names = arguments.fieldNames();
        while (names.hasNext()) if (!ARGUMENT_NAMES.contains(names.next())) {
            throw invalid("Only pattern, path, maxResults and caseSensitive are supported; search_file has no depth parameter.");
        }
        JsonNode pattern = arguments.get("pattern");
        if (pattern == null || !pattern.isTextual()) throw invalid("pattern must be a nonblank string.");
        JsonNode path = arguments.get("path");
        if (path != null && (!path.isTextual() || path.asText().isBlank())) throw invalid("path must be a nonblank string.");
        JsonNode sensitivity = arguments.get("caseSensitive");
        if (sensitivity != null && !sensitivity.isBoolean()) throw invalid("caseSensitive must be a boolean.");
        boolean caseSensitive = sensitivity != null && sensitivity.booleanValue();
        FileGlob matcher;
        try { matcher = new FileGlob(pattern.asText(), caseSensitive); }
        catch (IllegalArgumentException invalid) { throw invalid(invalid.getMessage()); }
        return new Options(pattern.asText().replace('\\', '/'), WorkspaceFiles.relativePath(path == null ? "." : path.asText()),
                integer(arguments, "maxResults", DEFAULT_MAX_RESULTS, MAX_RESULTS), caseSensitive, matcher);
    }

    /** 整数不接受浮点、小数文本、null 或越界值。 */
    private static int integer(JsonNode arguments, String name, int fallback, int maximum) {
        JsonNode value = arguments.get(name);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum) {
            throw invalid(name + " must be an integer within its allowed range.");
        }
        return value.intValue();
    }

    /** 参数错误只返回受控说明，不附带原始参数。 */
    private static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, message); }

    /** 模式、搜索范围、预算和匹配器属于当前调用，不放到注册表全局状态中。 */
    private record Options(String pattern, Path path, int maxResults, boolean caseSensitive, FileGlob matcher) { }

    /** 只累计路径并预留元数据空间，保证执行器收到的始终是完整 JSON。 */
    private static final class Results {
        /** 当前调用的模式和返回限制。 */
        private final Options options;
        /** 已规范化、相对工作区的搜索起点。 */
        private final String path;
        /** 宿主允许输出的 Unicode 码点数。 */
        private final int outputLimit;
        /** 只保存工作区相对文件路径。 */
        private final ArrayNode files = MAPPER.createArrayNode();
        /** 共享遍历器返回的扫描和深度统计。 */
        private WorkspaceFiles.Scan scan = new WorkspaceFiles.Scan(0, false, false);
        /** 本工具主动停止原因，优先于遍历器的扫描/深度限制。 */
        private String stopReason;

        private Results(Options options, String path, int outputLimit) {
            this.options = options;
            this.path = path;
            this.outputLimit = outputLimit;
        }

        /** 以最坏扫描计数和最长截断原因预留，后续统计增长不会撑破结果。 */
        private boolean fits() {
            String text = output(true);
            return text.codePointCount(0, text.length()) <= outputLimit;
        }

        private void requireMetadataBudget() {
            if (!fits()) throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit search_file metadata.");
        }

        /** 只有发现超出上限的额外命中才标记 maxResults；恰好达到上限不误报。 */
        private boolean add(String file) {
            if (files.size() >= options.maxResults()) { stopReason = "maxResults"; return false; }
            files.add(file);
            if (fits()) return true;
            files.remove(files.size() - 1);
            if (files.isEmpty()) throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit one file path.");
            stopReason = "outputLimit";
            return false;
        }

        /** 空结果和截断分开表达，避免把未扫描区域当作确定不存在。 */
        private String output(boolean reserve) {
            String reason = stopReason != null ? stopReason : scan.scanLimited() ? "scanLimit" : null;
            ObjectNode result = MAPPER.createObjectNode().put("pattern", options.pattern()).put("path", path)
                    .put("caseSensitive", options.caseSensitive())
                    .put("maxResults", options.maxResults()).put("returnedFiles", files.size());
            result.set("files", files);
            result.put("truncated", reserve ? false : reason != null);
            if (reserve) result.put("limitReason", "outputLimit");
            else if (reason == null) result.putNull("limitReason");
            else result.put("limitReason", reason);
            result.put("scannedEntries", reserve ? WorkspaceFiles.MAX_SCANNED_ENTRIES : scan.scannedEntries());
            ArrayNode ignored = result.putArray("ignoredDirectoryNames");
            WorkspaceFiles.IGNORED_DIRECTORY_NAMES.forEach(ignored::add);
            return result.toString();
        }
    }
}
