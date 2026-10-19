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
import io.github.jackfeicoder.codey.tool.filesystem.Utf8TextReader;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceFiles;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 在工作区文本正文中执行字面搜索，返回匹配行；不执行 Shell、不推测关键词、不调用其他工具。 */
public final class GrepCodeTool implements Tool {
    @Override public io.github.jackfeicoder.codey.tool.ToolAccess access() { return io.github.jackfeicoder.codey.tool.ToolAccess.READ_ONLY; }
    /** 查询限制为单行文本；参数长度按 Unicode 字符计数。 */
    public static final int MAX_QUERY_LENGTH = 256;
    /** 返回匹配行数，单行多次出现关键词也只返回一条。 */
    public static final int DEFAULT_MAX_RESULTS = 50;
    public static final int MAX_RESULTS = 500;
    /** 全次搜索最多检查一千个候选文件，实际读取（含预读）最多 32 MiB。 */
    public static final int MAX_FILES = 1_000;
    public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    /** 参数只包含查询与范围，不允许覆盖工作区和宿主硬预算。 */
    private static final Set<String> ARGUMENT_NAMES = Set.of("query", "path", "filePattern", "caseSensitive", "maxResults");
    /** 可跳过的文件问题；路径越界与宿主错误仍直接终止工具。 */
    private static final List<ToolResult.ErrorCode> SKIP_CODES = List.of(ToolResult.ErrorCode.FILE_TOO_LARGE,
            ToolResult.ErrorCode.LINE_TOO_LONG, ToolResult.ErrorCode.INVALID_ENCODING, ToolResult.ErrorCode.NOT_TEXT_FILE,
            ToolResult.ErrorCode.FILE_NOT_FOUND, ToolResult.ErrorCode.FILE_NOT_READABLE,
            ToolResult.ErrorCode.FILE_READ_FAILED, ToolResult.ErrorCode.NOT_A_FILE);
    /** 仅用于 schema 和结果正文，不参与模型协议处理。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 结构化说明在注册时发送，无需单独工具说明 MD。 */
    private static final ToolDefinition DEFINITION = createDefinition();

    /** 提供字面内容搜索的协议定义。 */
    @Override
    public ToolDefinition definition() { return DEFINITION; }

    /** 无工作区时隐藏并拒绝执行。 */
    @Override
    public boolean requiresWorkspace() { return true; }

    /** 参数校验不访问文件系统。 */
    @Override
    public void validateArguments(JsonNode arguments, ToolContext context) { options(arguments); }

    /** 默认搜索目录至末端，也可直接搜索已知文件；匹配结果由模型判断相关性。 */
    @Override
    public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        checkInterrupted();
        Options options = options(arguments);
        var scope = WorkspaceFiles.scope(context, options.path());
        var results = new Results(options, WorkspaceFiles.relative(scope.root(), scope.start()), context.maxOutputChars());
        results.requireMetadataBudget();
        if (scope.directory()) {
            results.scan = WorkspaceFiles.scan(new WorkspaceFiles.Directory(scope.root(), scope.start()), entry -> {
                if (!"file".equals(entry.type()) || !options.filter().matches(WorkspaceFiles.relative(scope.start(), entry.path()))) return true;
                return searchFile(scope.root(), entry.path(), options, results);
            });
        } else {
            results.scan = new WorkspaceFiles.Scan(1, false, false);
            if (options.filter().matches(scope.start().getFileName().toString())) searchFile(scope.root(), scope.start(), options, results);
        }
        return results.output(false);
    }

    /** 校验真实路径后逐行读取；文件失败时撤回该文件已收集的匹配，避免留下不完整的错误结果。 */
    private static boolean searchFile(Path root, Path candidate, Options options, Results results) throws InterruptedException {
        checkInterrupted();
        if (results.scannedFiles >= MAX_FILES) { results.stopReason = "fileLimit"; return false; }
        results.scannedFiles++;
        int mark = results.matches.size();
        try {
            Path file = candidate.toRealPath();
            if (!file.startsWith(root)) throw new ToolException(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "File resolves outside the workspace.");
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new ToolException(ToolResult.ErrorCode.NOT_A_FILE, "Search candidate is not a regular file.");
            if (attributes.size() > Utf8TextReader.MAX_FILE_BYTES) throw new ToolException(ToolResult.ErrorCode.FILE_TOO_LARGE, "File exceeds the text size limit.");
            if (!Files.isReadable(file)) throw new ToolException(ToolResult.ErrorCode.FILE_NOT_READABLE, "File is not readable.");
            boolean stopped = false;
            String display = WorkspaceFiles.relative(root, file);
            try (var reader = Utf8TextReader.open(file, results.bytes)) {
                long lineNumber = 0;
                for (String line; (line = reader.readLine()) != null;) {
                    checkInterrupted();
                    lineNumber++;
                    String haystack = options.caseSensitive() ? line : line.toLowerCase(Locale.ROOT);
                    if (haystack.contains(options.needle()) && !results.add(display, lineNumber, line)) { stopped = true; break; }
                }
            }
            if (stopped) { results.partialFiles++; return false; }
            results.searchedFiles++;
            return true;
        } catch (Utf8TextReader.ByteLimitExceeded exhausted) {
            results.partialFiles++;
            results.stopReason = "byteLimit";
            return false;
        } catch (CharacterCodingException invalid) {
            return results.skip(mark, ToolResult.ErrorCode.INVALID_ENCODING);
        } catch (NoSuchFileException missing) {
            return results.skip(mark, ToolResult.ErrorCode.FILE_NOT_FOUND);
        } catch (AccessDeniedException denied) {
            return results.skip(mark, ToolResult.ErrorCode.FILE_NOT_READABLE);
        } catch (ToolException failed) {
            if (!SKIP_CODES.contains(failed.code())) throw failed;
            return results.skip(mark, failed.code());
        } catch (IOException failed) {
            checkInterrupted();
            return results.skip(mark, ToolResult.ErrorCode.FILE_READ_FAILED);
        }
    }

    /** 明确区分正文 query 与文件范围 filePattern，不提供语义搜索、函数识别或正则开关。 */
    private static ToolDefinition createDefinition() {
        var schema = MAPPER.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("query");
        var properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string").put("minLength", 1).put("maxLength", MAX_QUERY_LENGTH)
                .put("description", "必填，最多 256 字符的单行字面文本，如 login、API_KEY；不支持正则、模糊或语义搜索，保留查询中的空格");
        properties.putObject("path").put("type", "string").put("minLength", 1).put("default", ".")
                .put("description", "相对工作区的目录或单个文件；目录不设人为递归深度，返回路径始终相对工作区");
        properties.putObject("filePattern").put("type", "string").put("default", "*")
                .put("maxLength", FileGlob.MAX_PATTERN_LENGTH).put("minLength", 1)
                .put("description", "可选文件范围，语义与 search_file 相同，如 *.java、**/*.jsx；相对搜索目录匹配，单文件时匹配其文件名，不单独返回按名称匹配的文件");
        properties.putObject("caseSensitive").put("type", "boolean").put("default", false)
                .put("description", "是否区分正文与文件筛选模式的大小写，默认不区分，结果保留原文");
        properties.putObject("maxResults").put("type", "integer").put("minimum", 1).put("maximum", MAX_RESULTS)
                .put("default", DEFAULT_MAX_RESULTS).put("description", "最多返回的匹配行数，默认 50、最大 500；同一行只返回一次");
        return new ToolDefinition("grep_code", "在工作区 UTF-8 文件正文逐行搜索字面关键词，返回路径、1 起始行号和完整匹配行。"
                + "不知道代码位置时可直接搜索目录，不必先 search_file；需要完整方法再用 read_file。关键词由你选择，工具不识别函数、变量或相似语义。"
                + "共享目录忽略与链接规则，不进入发现的链接目录；最多扫描 10000 项、检查 1000 个候选文件、读取 32 MiB。"
                + "单文件最多 4 MiB、单行最多 16384 个 UTF-16 单元。跳过编码/二进制/大小/读取问题并统计原因。"
                + "truncated 表示预算截断，complete=false 表示截断或有候选文件被跳过；空匹配不代表所有文件都没有关键词。", schema);
    }

    /** 严格校验字段和类型；不对查询做 strip，以保留有意义的前后空白。 */
    private static Options options(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) throw invalid("grep_code arguments must be an object.");
        var names = arguments.fieldNames();
        while (names.hasNext()) if (!ARGUMENT_NAMES.contains(names.next())) throw invalid("Only query, path, filePattern, caseSensitive and maxResults are supported.");
        JsonNode query = arguments.get("query");
        if (query == null || !query.isTextual() || query.asText().isBlank()
                || query.asText().codePointCount(0, query.asText().length()) > MAX_QUERY_LENGTH
                || query.asText().codePoints().anyMatch(c -> (Character.isISOControl(c) && c != '\t') || c == 0x2028 || c == 0x2029)) {
            throw invalid("query must be nonblank single-line literal text of at most 256 characters.");
        }
        JsonNode path = arguments.get("path");
        if (path != null && (!path.isTextual() || path.asText().isBlank())) throw invalid("path must be a nonblank string.");
        JsonNode pattern = arguments.get("filePattern");
        if (pattern != null && !pattern.isTextual()) throw invalid("filePattern must be a nonblank glob string.");
        JsonNode sensitive = arguments.get("caseSensitive");
        if (sensitive != null && !sensitive.isBoolean()) throw invalid("caseSensitive must be a boolean.");
        boolean caseSensitive = sensitive != null && sensitive.booleanValue();
        String filePattern = pattern == null ? "*" : pattern.asText().replace('\\', '/');
        FileGlob filter;
        try { filter = new FileGlob(filePattern, caseSensitive); }
        catch (IllegalArgumentException invalid) { throw invalid(invalid.getMessage()); }
        JsonNode maximum = arguments.get("maxResults");
        if (maximum != null && (!maximum.isIntegralNumber() || !maximum.canConvertToInt()
                || maximum.intValue() < 1 || maximum.intValue() > MAX_RESULTS)) throw invalid("maxResults must be an integer within 1..500.");
        return new Options(query.asText(), WorkspaceFiles.relativePath(path == null ? "." : path.asText()), filePattern,
                caseSensitive, maximum == null ? DEFAULT_MAX_RESULTS : maximum.intValue(),
                caseSensitive ? query.asText() : query.asText().toLowerCase(Locale.ROOT), filter);
    }

    /** 每条匹配与读取都可响应取消，工具自身不清除线程标志。 */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
    /** 参数错误不回显原始输入。 */
    private static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, message); }
    /** 单次请求的数据与预处理后的查询，不读配置或保存索引。 */
    private record Options(String query, Path path, String filePattern, boolean caseSensitive, int maxResults, String needle, FileGlob filter) { }

    /** 本次搜索的有界结果和统计，不作为跨任务记忆。 */
    private static final class Results {
        /** 已校验的查询与范围。 */
        private final Options options;
        /** 相对工作区的实际搜索起点。 */
        private final String path;
        /** 宿主输出上限，单位为 Unicode 码点。 */
        private final int outputLimit;
        /** 成功文件的匹配，以及当前文件待完成验证的临时匹配。 */
        private final ArrayNode matches = MAPPER.createArrayNode();
        /** 所有文件共用的实际读取字节预算。 */
        private final Utf8TextReader.ByteBudget bytes = new Utf8TextReader.ByteBudget(MAX_TOTAL_BYTES);
        /** 仅保存跳过原因的计数，不回显原始异常。 */
        private final EnumMap<ToolResult.ErrorCode, Integer> skipped = new EnumMap<>(ToolResult.ErrorCode.class);
        /** 目录遍历统计，单文件请求按一个目录项计数。 */
        private WorkspaceFiles.Scan scan = new WorkspaceFiles.Scan(0, false, false);
        /** 已开始检查的候选文件总数，包括成功、跳过和部分读取。 */
        private int scannedFiles;
        /** 已完整扫描且成功关闭的文本文件数。 */
        private int searchedFiles;
        /** 因大小、编码或读取问题跳过的文件数。 */
        private int skippedFiles;
        /** 因全局预算提前停止的当前文件数。 */
        private int partialFiles;
        /** 返回、输出、文件或字节限制，目录扫描限制由 scan 提供。 */
        private String stopReason;

        private Results(Options options, String path, int outputLimit) {
            this.options = options; this.path = path; this.outputLimit = outputLimit;
        }

        /** 每次加入匹配前后都按完整 JSON 预留最坏统计空间。 */
        private boolean fits() {
            String json = output(true);
            return json.codePointCount(0, json.length()) <= outputLimit;
        }
        private void requireMetadataBudget() {
            if (!fits()) throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit grep_code metadata.");
        }

        /** 额外命中才触发 maxResults；完整匹配行放不下时明确报错或截断，不切碎 JSON/Unicode。 */
        private boolean add(String file, long line, String text) {
            if (matches.size() >= options.maxResults()) { stopReason = "maxResults"; return false; }
            matches.addObject().put("path", file).put("line", line).put("text", text);
            if (fits()) return true;
            matches.remove(matches.size() - 1);
            if (matches.isEmpty()) throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit one complete matching line.");
            stopReason = "outputLimit";
            return false;
        }

        /** 同一文件若在后续区域发现编码/NUL/读取问题，撤回此前暂存的匹配再计入跳过原因。 */
        private boolean skip(int mark, ToolResult.ErrorCode reason) {
            while (matches.size() > mark) matches.remove(matches.size() - 1);
            skippedFiles++;
            skipped.merge(reason, 1, Integer::sum);
            return stopReason == null;
        }

        /** 预算截断和文件跳过分别表达；计数指已检查范围，不是整个工作区的文件总量。 */
        private String output(boolean reserve) {
            String reason = stopReason != null ? stopReason : scan.scanLimited() ? "scanLimit" : null;
            var result = MAPPER.createObjectNode().put("query", options.query()).put("path", path)
                    .put("filePattern", options.filePattern()).put("caseSensitive", options.caseSensitive())
                    .put("maxResults", options.maxResults()).put("returnedMatches", matches.size());
            result.set("matches", matches);
            result.put("scannedEntries", reserve ? WorkspaceFiles.MAX_SCANNED_ENTRIES : scan.scannedEntries())
                    .put("scannedFiles", reserve ? MAX_FILES : scannedFiles)
                    .put("searchedFiles", reserve ? MAX_FILES : searchedFiles)
                    .put("skippedFiles", reserve ? MAX_FILES : skippedFiles)
                    .put("partialFiles", reserve ? MAX_FILES : partialFiles)
                    .put("readBytes", reserve ? MAX_TOTAL_BYTES : bytes.used())
                    .put("complete", !reserve && reason == null && skippedFiles == 0)
                    .put("truncated", reserve ? false : reason != null);
            if (reserve) result.put("limitReason", "outputLimit");
            else if (reason == null) result.putNull("limitReason");
            else result.put("limitReason", reason);
            var reasons = result.putObject("skippedReasons");
            for (var code : SKIP_CODES) {
                if (reserve || skipped.containsKey(code)) reasons.put(code.name(), reserve ? MAX_FILES : skipped.get(code));
            }
            var ignored = result.putArray("ignoredDirectoryNames");
            WorkspaceFiles.IGNORED_DIRECTORY_NAMES.forEach(ignored::add);
            return result.toString();
        }
    }
}
