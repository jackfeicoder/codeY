package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.channels.Channels;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/**
 * 工作区内 UTF-8 文本的只读分页工具，返回带行号的 JSON 文本。
 * 路径按真实文件检查，内存读取与输出有界；不修改文件，不调用模型或自动注册到 CLI。
 */
public final class ReadFileTool implements Tool {
    /** 缺省从文件第一行开始。 */
    public static final int DEFAULT_START_LINE = 1;
    /** 缺省最多返回 200 行，输出预算不足时可少于该值。 */
    public static final int DEFAULT_MAX_LINES = 200;
    /** 模型参数不能一次要求超过 500 行。 */
    public static final int MAX_LINES = 500;
    /** 首版仅接受不超过 4 MiB 的文件，也限制流的实际读取量以应对文件增长。 */
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** 一行最多保留的 UTF-16 单元数，超长行明确报错而非无界构造字符串。 */
    public static final int MAX_LINE_CHARS = 16_384;
    /** 唯一支持的参数名称，拒绝模型额外指定工作区等宿主信息。 */
    private static final Set<String> ARGUMENT_NAMES = Set.of("path", "startLine", "maxLines");
    /** JSON 用于 schema 和输出正文，初始化后不更改配置。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 在代码中维护一份工具说明，参数默认值和范围与实际校验共用常量。 */
    private static final ToolDefinition DEFINITION = createDefinition();

    /** 提供用途及参数 schema，供注册表和后续 LLM tools 映射使用。 */
    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    /** 普通聊天不展示或执行项目读取工具。 */
    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    /** 校验字段、类型和范围；路径存在性及真实范围在执行前检查。 */
    @Override
    public void validateArguments(JsonNode arguments, ToolContext context) {
        options(arguments);
    }

    /**
     * 顺序跳过前文并读取完整行，受文件/单行/输出三类上限约束。
     * 返回 JSON 是工具输出文本，ToolExecutor 再包装状态与 callId，不在此创建消息。
     */
    @Override
    public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        checkInterrupted();
        ReadOptions options = options(arguments); // 直接 Java 调用也执行同样的参数校验。
        if (context.workspace().mode() == WorkspaceBinding.Mode.NONE) {
            throw error(ToolResult.ErrorCode.WORKSPACE_REQUIRED, "read_file requires a project workspace.");
        }
        if (!context.hasWorkspace()) {
            throw error(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, "read_file workspace is unavailable.");
        }
        Path root = Path.of(context.workspace().path()); // 宿主提供的真实工作区，不来自模型参数。
        Path candidate = root.resolve(options.relativePath()).normalize();
        if (!candidate.startsWith(root)) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "File path is outside the workspace.");
        }
        try {
            Path file = candidate.toRealPath(); // 包括中间目录和最终符号链接的实际指向。
            if (!file.startsWith(root)) {
                throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "File resolves outside the workspace.");
            }
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw error(ToolResult.ErrorCode.NOT_A_FILE, "read_file only accepts regular files.");
            }
            if (attributes.size() > MAX_FILE_BYTES) {
                throw error(ToolResult.ErrorCode.FILE_TOO_LARGE, "File exceeds the 4 MiB read_file limit.");
            }
            if (!Files.isReadable(file)) {
                throw error(ToolResult.ErrorCode.FILE_NOT_READABLE, "File is not readable.");
            }
            // 打开已解析路径，不跟随打开瞬间出现的最终符号链接；不是完整文件系统沙箱。
            try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 InputStream stream = new LimitedInputStream(Channels.newInputStream(channel));
                 PushbackReader reader = new PushbackReader(new InputStreamReader(stream,
                         StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)), 1)) {
                int first = reader.read(); // 只移除文件开头的 UTF-8 BOM，内容中的 BOM 原样保留。
                if (first != -1 && first != '\uFEFF') reader.unread(first);
                String displayPath = root.relativize(file).toString().replace('\\', '/');
                return readPage(reader, displayPath, options, context.maxOutputChars());
            }
        } catch (NoSuchFileException missing) {
            throw error(ToolResult.ErrorCode.FILE_NOT_FOUND, "File was not found.");
        } catch (AccessDeniedException denied) {
            throw error(ToolResult.ErrorCode.FILE_NOT_READABLE, "File access was denied.");
        } catch (CharacterCodingException invalid) {
            throw error(ToolResult.ErrorCode.INVALID_ENCODING, "File is not valid UTF-8 in the read region.");
        } catch (IOException failed) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            throw error(ToolResult.ErrorCode.FILE_READ_FAILED, "Failed to read the file.");
        }
    }

    /** 构造与实际实现一致的元数据，不在 MD 中复制另一份参数定义。 */
    private static ToolDefinition createDefinition() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("minLength", 1)
                .put("description", "相对当前工作区的文件路径，禁止绝对路径和越界路径");
        properties.putObject("startLine").put("type", "integer").put("minimum", 1)
                .put("maximum", Integer.MAX_VALUE).put("default", DEFAULT_START_LINE)
                .put("description", "起始行号，从 1 开始");
        properties.putObject("maxLines").put("type", "integer").put("minimum", 1).put("maximum", MAX_LINES)
                .put("default", DEFAULT_MAX_LINES).put("description", "最多返回的行数，输出预算不足时会提前分页");
        schema.putArray("required").add("path");
        schema.put("additionalProperties", false);
        return new ToolDefinition("read_file",
                "读取工作区内已知 UTF-8 文本文件，返回路径、完整行及行号、hasMore 和 nextStartLine。"
                        + "可从 nextStartLine 继续。文件最多 4 MiB，单行最多 16384 个 UTF-16 单元；不用于读取二进制、遍历或搜索。",
                schema);
    }

    /** 将 JSON 转为不可变读取选项，拒绝未知参数、浮点/字符串行数与绝对路径。 */
    private static ReadOptions options(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || !arguments.path("path").isTextual()
                || arguments.path("path").asText().isBlank()) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "read_file requires a nonblank string path.");
        }
        var names = arguments.fieldNames();
        while (names.hasNext()) {
            if (!ARGUMENT_NAMES.contains(names.next())) {
                throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Only path, startLine and maxLines are supported.");
            }
        }
        Path path;
        try {
            path = Path.of(arguments.path("path").asText()); // 保留文件名空格，不 strip 路径内容。
        } catch (InvalidPathException invalid) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "File path has invalid syntax.");
        }
        if (path.isAbsolute() || path.getRoot() != null) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Use a path relative to the workspace.");
        }
        return new ReadOptions(path, positiveInteger(arguments, "startLine", DEFAULT_START_LINE, Integer.MAX_VALUE),
                positiveInteger(arguments, "maxLines", DEFAULT_MAX_LINES, MAX_LINES));
    }

    /** 缺失使用默认值；显式 null、浮点和溢出值不静默转换。 */
    private static int positiveInteger(JsonNode arguments, String field, int fallback, int maximum) {
        JsonNode value = arguments.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1 || value.intValue() > maximum) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, field + " must be an integer within its allowed range.");
        }
        return value.intValue();
    }

    /** 单次读取的有效参数，不包含工作区或模型凭证。 */
    private record ReadOptions(Path relativePath, int startLine, int maxLines) { }

    /** 只保留请求页，不读取整文件进内存；输出容量不足时下一次从未返回行继续。 */
    private static String readPage(PushbackReader reader, String path, ReadOptions options, int outputLimit)
            throws IOException, InterruptedException {
        ArrayNode lines = MAPPER.createArrayNode(); // 每项保存实际文件行号和原始行文本。
        for (long skipped = 1; skipped < options.startLine(); skipped++) {
            checkInterrupted();
            if (readLine(reader) == null) return checkedOutput(path, options.startLine(), lines, false, outputLimit);
        }
        boolean hasMore = false;
        for (int index = 0; index < options.maxLines(); index++) {
            checkInterrupted();
            String line = readLine(reader);
            if (line == null) break;
            lines.addObject().put("line", (long) options.startLine() + index).put("text", line);
            // 同时计入 EOF/非 EOF 两种元数据的长度，避免最后一次标志变化导致 JSON 被截断。
            if (!fits(path, options.startLine(), lines, true, outputLimit)
                    || !fits(path, options.startLine(), lines, false, outputLimit)) {
                lines.remove(lines.size() - 1);
                if (lines.isEmpty()) {
                    throw error(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit one complete line.");
                }
                hasMore = true; // 当前行未返回，下次从其行号重新读取。
                break;
            }
            if (index + 1 == options.maxLines()) {
                int next = reader.read(); // 仅查看是否还有字符，不预先读取下一条超长行。
                hasMore = next != -1;
                if (next != -1) reader.unread(next);
            }
        }
        return checkedOutput(path, options.startLine(), lines, hasMore, outputLimit);
    }

    /** 有界逐字符读取，兼容 LF/CRLF/CR，末尾换行不产生额外空行。 */
    private static String readLine(PushbackReader reader) throws IOException, InterruptedException {
        StringBuilder text = new StringBuilder();
        while (true) {
            if ((text.length() & 255) == 0) checkInterrupted();
            int current = reader.read();
            if (current == -1) return text.isEmpty() ? null : text.toString();
            if (current == '\n') return text.toString();
            if (current == '\r') {
                int next = reader.read();
                if (next != -1 && next != '\n') reader.unread(next);
                return text.toString();
            }
            if (current == 0) throw error(ToolResult.ErrorCode.NOT_TEXT_FILE, "File contains a NUL character.");
            if (text.length() >= MAX_LINE_CHARS) {
                throw error(ToolResult.ErrorCode.LINE_TOO_LONG, "A line exceeds the read_file character limit.");
            }
            text.append((char) current);
        }
    }

    /** 判断完整 JSON 是否能放进宿主的码点预算，包括 JSON 转义字符。 */
    private static boolean fits(String path, int start, ArrayNode lines, boolean more, int limit) {
        String output = output(path, start, lines, more);
        return output.codePointCount(0, output.length()) <= limit;
    }

    /** 空文件等结果仍需容纳元数据；预算过小时返回明确失败，不交给执行器切断 JSON。 */
    private static String checkedOutput(String path, int start, ArrayNode lines, boolean more, int limit) {
        String output = output(path, start, lines, more);
        if (output.codePointCount(0, output.length()) > limit) {
            throw error(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit read_file metadata.");
        }
        return output;
    }

    /** 以 hasMore/nextStartLine 表达分页；endLine 在无返回行时为 null，未计算整文件总行数。 */
    private static String output(String path, int start, ArrayNode lines, boolean more) {
        ObjectNode body = MAPPER.createObjectNode().put("path", path).put("startLine", start)
                .put("returnedLines", lines.size());
        if (lines.isEmpty()) body.putNull("endLine");
        else body.put("endLine", (long) start + lines.size() - 1);
        body.set("lines", lines);
        body.put("hasMore", more);
        if (more) body.put("nextStartLine", (long) start + lines.size());
        else body.putNull("nextStartLine");
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Failed to encode read_file output.");
        }
    }

    /** 周期检查中断，交由 ToolExecutor 恢复中断标志并返回关联错误结果。 */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    /** 错误说明不回显文件内容、绝对路径或底层异常。 */
    private static ToolException error(ToolResult.ErrorCode code, String message) {
        return new ToolException(code, message);
    }

    /** 实际字节读取上限，文件在元数据检查后增长时也不会无限读取。 */
    private static final class LimitedInputStream extends FilterInputStream {
        /** 包括跳过前文与 UTF-8 解码预读在内的已读取字节数。 */
        private long consumed;

        /** 包装已打开的文件通道，关闭该流会关闭底层流。 */
        private LimitedInputStream(InputStream input) { super(input); }

        /** 超过上限时拒绝，恰好等于上限的文件仍可探测 EOF。 */
        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1) count(1);
            return value;
        }

        /** 最多多读一个字节确认越界，不分配与文件大小成比例的缓冲。 */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int allowed = (int) Math.min(length, MAX_FILE_BYTES - consumed + 1);
            int read = in.read(bytes, offset, allowed);
            if (read > 0) count(read);
            return read;
        }

        /** 统一计数，不通过 FilterInputStream.read 导致重复计数。 */
        private void count(int amount) {
            consumed += amount;
            if (consumed > MAX_FILE_BYTES) {
                throw error(ToolResult.ErrorCode.FILE_TOO_LARGE, "File grew beyond the 4 MiB read_file limit.");
            }
        }
    }
}
