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
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 工作区目录探索工具：有界收集子项 → 按名称排序 → 深度优先返回相对路径，不读取文件正文。 */
public final class ListFilesTool implements Tool {
    /** 子项算第一层；默认 10 层覆盖当前项目深层 Java 包与提示词资源。 */
    public static final int DEFAULT_MAX_DEPTH = 10;
    /** 防止模型要求无界递归。 */
    public static final int MAX_DEPTH = 20;
    /** 返回条数的默认值与硬上限，目录也占一条。 */
    public static final int DEFAULT_MAX_ENTRIES = 200;
    public static final int MAX_ENTRIES = 1_000;
    /** 包括忽略目录在内的实际扫描上限，同时约束排序缓存和读取工作量。 */
    public static final int MAX_SCANNED_ENTRIES = 10_000;
    /** 只忽略遍历中遇到的这些目录；显式 path 指定它们时仍可列出内容。 */
    private static final List<String> IGNORED_NAMES = List.of(".git", "target", "node_modules", ".gradle", "build", "dist");
    /** JSON 参数白名单，工作区与宿主预算不接受模型覆盖。 */
    private static final Set<String> ARGUMENT_NAMES = Set.of("path", "maxDepth", "maxEntries");
    /** 只用于 schema 与结果的 JSON 编码，不在运行中修改。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 实际默认深度与定义在实例创建时固定，注册表不会展示与执行不一致的默认值。 */
    private final int defaultDepth;
    private final ToolDefinition definition;

    /** 独立使用工具时采用十层默认值。 */
    public ListFilesTool() { this(DEFAULT_MAX_DEPTH); }

    /** Main 注入配置，工具本身不读取 .env；模型仍可显式指定范围内的 maxDepth。 */
    public ListFilesTool(int defaultDepth) {
        if (defaultDepth < 1 || defaultDepth > MAX_DEPTH) throw new IllegalArgumentException("Directory depth must be within 1..20.");
        this.defaultDepth = defaultDepth;
        this.definition = createDefinition(defaultDepth);
    }

    /** 提供与当前实例执行默认值一致的协议定义。 */
    @Override
    public ToolDefinition definition() { return definition; }

    /** 普通聊天不展示目录工具，真实工作区由宿主传入。 */
    @Override
    public boolean requiresWorkspace() { return true; }

    /** 路径存在性及真实范围在执行时检查，先拒绝错误类型、未知参数和非法范围。 */
    @Override
    public void validateArguments(JsonNode arguments, ToolContext context) { options(arguments); }

    /** 返回完整 JSON；输出容量不足时少返回条目，不依赖执行器截断 JSON 字符串。 */
    @Override
    public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        checkInterrupted();
        Options options = options(arguments);
        if (context.workspace().mode() == WorkspaceBinding.Mode.NONE) {
            throw error(ToolResult.ErrorCode.WORKSPACE_REQUIRED, "list_files requires a project workspace.");
        }
        if (!context.hasWorkspace()) {
            throw error(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, "list_files workspace is unavailable.");
        }
        Path root = Path.of(context.workspace().path());
        Path requested = root.resolve(options.path()).normalize();
        if (!requested.startsWith(root)) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Directory path is outside the workspace.");
        }
        try {
            Path start = requested.toRealPath();
            if (!start.startsWith(root)) {
                throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Directory resolves outside the workspace.");
            }
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                throw error(ToolResult.ErrorCode.NOT_A_DIRECTORY, "list_files requires a directory path.");
            }
            String display = relative(root, start);
            Listing listing = new Listing(display, options, context.maxOutputChars());
            listing.requireMetadataBudget();
            walk(root, start, 0, listing);
            return listing.output(false);
        } catch (NoSuchFileException missing) {
            throw error(ToolResult.ErrorCode.DIRECTORY_NOT_FOUND, "A directory or entry was not found; refresh the listing.");
        } catch (AccessDeniedException denied) {
            throw error(ToolResult.ErrorCode.DIRECTORY_NOT_READABLE, "Directory access was denied; retry a narrower path or depth.");
        } catch (IOException | DirectoryIteratorException failed) {
            checkInterrupted();
            throw error(ToolResult.ErrorCode.DIRECTORY_READ_FAILED, "Failed to inspect the directory; retry a narrower path or depth.");
        }
    }

    /** 每层先关闭目录流再递归；不会同时持有整棵树的流，也不会跟随发现的链接目录。 */
    private static void walk(Path root, Path directory, int parentDepth, Listing listing)
            throws IOException, InterruptedException {
        checkInterrupted();
        Path real = directory.toRealPath();
        if (!real.startsWith(root) || !real.equals(directory)) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Directory changed or resolves outside the workspace.");
        }
        if (!Files.isReadable(real)) {
            throw error(ToolResult.ErrorCode.DIRECTORY_NOT_READABLE, "Directory is not readable.");
        }
        List<Path> children = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(real)) {
            for (Path child : stream) {
                checkInterrupted();
                if (listing.scanned >= MAX_SCANNED_ENTRIES) {
                    listing.scanLimited = true;
                    break;
                }
                listing.scanned++;
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        int depth = parentDepth + 1;
        for (Path child : children) {
            checkInterrupted();
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory() && IGNORED_NAMES.contains(child.getFileName().toString())) continue;
            String type;
            if (attributes.isSymbolicLink()) type = "symlink";
            else if (attributes.isDirectory()) {
                // Windows junction 等重解析目录未必标为 symbolicLink；真实路径不同也只列出、不进入。
                type = child.toRealPath().equals(child) ? "directory" : "symlink";
            } else type = attributes.isRegularFile() ? "file" : "other";
            ObjectNode entry = MAPPER.createObjectNode().put("path", relative(root, child))
                    .put("type", type).put("depth", depth);
            if (!listing.add(entry)) return;
            if ("directory".equals(type) && depth < listing.options.maxDepth() && !listing.scanLimited) {
                walk(root, child, depth, listing);
                if (listing.stopReason != null) return;
            }
        }
    }

    /** 完整工具说明放在结构化 schema 中，提示词只说明先列目录再读文件的使用顺序。 */
    private static ToolDefinition createDefinition(int defaultDepth) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("minLength", 1).put("default", ".")
                .put("description", "相对工作区的目录，默认 .；返回的每条路径仍相对工作区");
        properties.putObject("maxDepth").put("type", "integer").put("minimum", 1).put("maximum", MAX_DEPTH)
                .put("default", defaultDepth).put("description", "子项为第 1 层，深度范围 1–20");
        properties.putObject("maxEntries").put("type", "integer").put("minimum", 1).put("maximum", MAX_ENTRIES)
                .put("default", DEFAULT_MAX_ENTRIES).put("description", "最多返回的文件和目录条数，范围 1–1000");
        schema.put("additionalProperties", false);
        return new ToolDefinition("list_files",
                "查看工作区目录结构以寻找文件，再用 read_file 读取具体文件。默认查看 " + defaultDepth + " 层、最多 200 条。"
                        + "按每层名称排序、深度优先返回；忽略遍历中遇到的 .git、target、node_modules、.gradle、build、dist 目录。"
                        + "不读取正文、不跟随发现的链接；返回文件类型、深度与 truncated/limitReason。"
                        + "扫描最多 10000 项，结果受输出预算限制；截断时选择更具体的 path 继续探索，不代表整个工作区已列完。",
                schema);
    }

    /** 缺失字段使用默认值；显式 null、绝对路径、浮点数字、未知字段均拒绝。 */
    private Options options(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "list_files arguments must be an object.");
        }
        var names = arguments.fieldNames();
        while (names.hasNext()) {
            if (!ARGUMENT_NAMES.contains(names.next())) {
                throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Only path, maxDepth and maxEntries are supported.");
            }
        }
        JsonNode value = arguments.get("path");
        if (value != null && (!value.isTextual() || value.asText().isBlank())) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "path must be a nonblank string.");
        }
        Path path;
        try { path = Path.of(value == null ? "." : value.asText()); }
        catch (InvalidPathException invalid) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Directory path has invalid syntax.");
        }
        if (path.isAbsolute() || path.getRoot() != null) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Use a path relative to the workspace.");
        }
        return new Options(path, integer(arguments, "maxDepth", defaultDepth, MAX_DEPTH),
                integer(arguments, "maxEntries", DEFAULT_MAX_ENTRIES, MAX_ENTRIES));
    }

    /** 避免 JSON 类型自动转换，使 Java 校验与 schema 的整数范围一致。 */
    private static int integer(JsonNode arguments, String name, int fallback, int maximum) {
        JsonNode node = arguments.get(name);
        if (node == null) return fallback;
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 1 || node.intValue() > maximum) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, name + " must be an integer within its allowed range.");
        }
        return node.intValue();
    }

    /** 显示路径不泄露工作区绝对位置，分隔符统一为 /；根目录显示 .。 */
    private static String relative(Path root, Path path) {
        String result = root.relativize(path).toString().replace('\\', '/');
        return result.isEmpty() ? "." : result;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static ToolException error(ToolResult.ErrorCode code, String message) { return new ToolException(code, message); }

    /** 参数只包含工具请求，宿主工作区和预算单独传入。 */
    private record Options(Path path, int maxDepth, int maxEntries) { }

    /** 一次遍历的有界结果状态，不作为全局缓存或后续调用的游标。 */
    private static final class Listing {
        private final String path;
        private final Options options;
        private final int outputLimit;
        private final ArrayNode entries = MAPPER.createArrayNode();
        private int scanned;
        private boolean scanLimited;
        private String stopReason;

        private Listing(String path, Options options, int outputLimit) {
            this.path = path;
            this.options = options;
            this.outputLimit = outputLimit;
        }

        /** 保留最坏元数据的空间，后续扫描计数增长或截断原因改变也不会破坏 JSON。 */
        private boolean fits() {
            String text = output(true);
            return text.codePointCount(0, text.length()) <= outputLimit;
        }

        private void requireMetadataBudget() {
            if (!fits()) throw error(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit list_files metadata.");
        }

        /** 返回 false 时不再遍历；至少一条也放不下则给出明确错误，避免伪装为空目录。 */
        private boolean add(ObjectNode entry) {
            if (entries.size() >= options.maxEntries()) { stopReason = "maxEntries"; return false; }
            entries.add(entry);
            if (fits()) return true;
            entries.remove(entries.size() - 1);
            if (entries.isEmpty()) throw error(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit one directory entry.");
            stopReason = "outputLimit";
            return false;
        }

        /** reserve=true 只用于预算计算；实际输出不虚报扫描条数或截断。 */
        private String output(boolean reserve) {
            String reason = stopReason != null ? stopReason : scanLimited ? "scanLimit" : null;
            ObjectNode result = MAPPER.createObjectNode().put("path", path).put("maxDepth", options.maxDepth())
                    .put("maxEntries", options.maxEntries()).put("returnedEntries", entries.size());
            result.set("entries", entries);
            result.put("truncated", reserve ? false : reason != null);
            if (reserve) result.put("limitReason", "outputLimit");
            else if (reason == null) result.putNull("limitReason");
            else result.put("limitReason", reason);
            result.put("scannedEntries", reserve ? MAX_SCANNED_ENTRIES : scanned);
            ArrayNode ignored = result.putArray("ignoredDirectoryNames");
            IGNORED_NAMES.forEach(ignored::add);
            return result.toString();
        }
    }
}
