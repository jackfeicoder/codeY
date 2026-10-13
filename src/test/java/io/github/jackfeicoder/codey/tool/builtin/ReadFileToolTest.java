package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.ToolCall;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolExecutor;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.tool.ToolResult;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 用临时真实文件验证分页、编码、路径范围与资源上限，不读取项目凭证。 */
class ReadFileToolTest {
    /** 每项测试独立的工作区及外部文件容器。 */
    @TempDir Path directory;
    /** 解析工具输出，确认返回的是完整有效 JSON。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 参数默认值与实现一致，注册表按工作区筛选此工具。 */
    @Test
    void definitionIsUsableOnlyInWorkspace() throws Exception {
        ReadFileTool tool = new ReadFileTool();
        assertEquals("read_file", tool.definition().name());
        JsonNode schema = tool.definition().parameters();
        assertEquals("path", schema.path("required").get(0).asText());
        assertEquals(200, schema.at("/properties/maxLines/default").asInt());
        assertEquals(500, schema.at("/properties/maxLines/maximum").asInt());
        assertEquals(1, schema.at("/properties/startLine/default").asInt());
        assertFalse(schema.path("additionalProperties").asBoolean());
        ToolRegistry registry = new ToolRegistry().register(tool);
        assertTrue(registry.definitions(new ToolContext(WorkspaceBinding.none())).isEmpty());
        assertEquals(1, registry.definitions(context(16_000)).size());
    }

    /** 保留中文、表情、空格文件名和行内空白，仅移除开头 BOM。 */
    @Test
    void readsUtf8AndPreservesContentAndCallId() throws Exception {
        Files.writeString(directory.resolve("中文 file.txt"), "\uFEFF  中文😀  \n\t第二行\uFEFF", StandardCharsets.UTF_8);
        ToolResult result = execute("{\"path\":\"中文 file.txt\"}");
        JsonNode page = success(result);
        assertEquals("中文 file.txt", page.path("path").asText());
        assertEquals("  中文😀  ", page.at("/lines/0/text").asText());
        assertEquals("\t第二行\uFEFF", page.at("/lines/1/text").asText());
        assertEquals(1, page.at("/lines/0/line").asInt());
        assertEquals(2, page.path("endLine").asInt());
        assertFalse(page.path("hasMore").asBoolean());
        assertEquals("read-call", result.toMessage().toolCallId());
        assertEquals(result.output(), MAPPER.readTree(result.toMessage().content()).path("output").asText());
    }

    /** 换行风格统一分页，空行保留，末尾换行不产生虚构行。 */
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void handlesLineEndingsAndBlankLines(String newline) throws Exception {
        Files.writeString(directory.resolve("file.txt"), "first" + newline + newline + "third" + newline);
        JsonNode page = success(execute("{\"path\":\"file.txt\"}"));
        assertEquals(3, page.path("returnedLines").asInt());
        assertEquals("", page.at("/lines/1/text").asText());
        assertEquals("third", page.at("/lines/2/text").asText());
        assertFalse(page.path("hasMore").asBoolean());
    }

    /** 从指定行开始，下一页行号可直接复用，结尾准确标记 EOF。 */
    @Test
    void paginatesWithoutMissingOrRepeatingLines() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "one\ntwo\nthree\nfour\nfive");
        JsonNode first = success(execute("{\"path\":\"file.txt\",\"startLine\":2,\"maxLines\":2}"));
        assertEquals("two", first.at("/lines/0/text").asText());
        assertEquals("three", first.at("/lines/1/text").asText());
        assertEquals(2, first.at("/lines/0/line").asInt());
        assertTrue(first.path("hasMore").asBoolean());
        assertEquals(4, first.path("nextStartLine").asInt());
        JsonNode last = success(execute("{\"path\":\"file.txt\",\"startLine\":4,\"maxLines\":2}"));
        assertEquals("four", last.at("/lines/0/text").asText());
        assertEquals("five", last.at("/lines/1/text").asText());
        assertFalse(last.path("hasMore").asBoolean());
        assertTrue(last.path("nextStartLine").isNull());
    }

    /** 省略行数时使用 200 行，显式设置可读取更大的页。 */
    @Test
    void appliesDefaultPageSizeAndExplicitOverride() throws Exception {
        Files.writeString(directory.resolve("file.txt"), IntStream.rangeClosed(1, 205)
                .mapToObj(Integer::toString).collect(Collectors.joining("\n")));
        JsonNode defaults = success(execute("{\"path\":\"file.txt\"}"));
        assertEquals(200, defaults.path("returnedLines").asInt());
        assertEquals(201, defaults.path("nextStartLine").asInt());
        JsonNode larger = success(execute("{\"path\":\"file.txt\",\"maxLines\":500}"));
        assertEquals(205, larger.path("returnedLines").asInt());
        assertFalse(larger.path("hasMore").asBoolean());
    }

    /** 空文件、BOM 空文件与超出 EOF 都返回合法空页。 */
    @ParameterizedTest
    @ValueSource(strings = {"", "\uFEFF", "first\nsecond"})
    void returnsEmptyPageAfterEof(String text) throws Exception {
        Files.writeString(directory.resolve("file.txt"), text);
        JsonNode page = success(execute("{\"path\":\"file.txt\",\"startLine\":3}"));
        assertEquals(0, page.path("returnedLines").asInt());
        assertTrue(page.path("lines").isEmpty());
        assertTrue(page.path("endLine").isNull());
        assertTrue(page.path("nextStartLine").isNull());
        assertFalse(page.path("hasMore").asBoolean());
    }

    /** JSON 转义与元数据也占预算；提前分页保持整行与 JSON 完整。 */
    @Test
    void outputBudgetCreatesResumablePagesInsteadOfTruncatedJson() throws Exception {
        String line = "😀\t\"\\".repeat(15);
        Files.writeString(directory.resolve("file.txt"), line + "\n" + line + "\n" + line);
        ToolResult firstResult = execute("{\"path\":\"file.txt\"}", 330);
        JsonNode first = success(firstResult);
        assertEquals(1, first.path("returnedLines").asInt());
        assertEquals(line, first.at("/lines/0/text").asText());
        assertEquals(2, first.path("nextStartLine").asInt());
        assertTrue(first.path("hasMore").asBoolean());
        assertTrue(firstResult.output().codePointCount(0, firstResult.output().length()) <= 330);
        JsonNode second = success(execute("{\"path\":\"file.txt\",\"startLine\":2}", 330));
        assertEquals(2, second.at("/lines/0/line").asInt());
        assertEquals(line, second.at("/lines/0/text").asText());
        assertEquals(3, second.path("nextStartLine").asInt());
    }

    /** 单行或空页元数据都放不下时明确返回预算错误。 */
    @Test
    void reportsInsufficientBudget() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "long content".repeat(30));
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                execute("{\"path\":\"file.txt\"}", 200).errorCode());
        Files.writeString(directory.resolve("file.txt"), "");
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                execute("{\"path\":\"file.txt\"}", 20).errorCode());
    }

    /** 参数错误不静默纠正，模型应根据错误结果重新构造调用。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"path\":null}", "{\"path\":1}", "{\"path\":\" \"}",
            "{\"path\":\"file.txt\",\"workspace\":\"elsewhere\"}",
            "{\"path\":\"file.txt\",\"startLine\":0}", "{\"path\":\"file.txt\",\"startLine\":-1}",
            "{\"path\":\"file.txt\",\"startLine\":1.0}", "{\"path\":\"file.txt\",\"startLine\":\"1\"}",
            "{\"path\":\"file.txt\",\"startLine\":null}", "{\"path\":\"file.txt\",\"startLine\":2147483648}",
            "{\"path\":\"file.txt\",\"maxLines\":0}", "{\"path\":\"file.txt\",\"maxLines\":501}",
            "{\"path\":\"file.txt\",\"maxLines\":1.0}", "{\"path\":\"file.txt\",\"maxLines\":\"2\"}",
            "{\"path\":\"file.txt\",\"maxLines\":null}"})
    void rejectsInvalidArguments(String arguments) throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(arguments).errorCode());
    }

    /** 路径穿越和绝对路径被拒绝；合法的内部归一化路径可读取。 */
    @Test
    void boundsPathsToWorkspace() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Path sibling = Files.createDirectory(directory.resolve("project-other"));
        Files.writeString(sibling.resolve("secret.txt"), "external fixture");
        Files.createDirectory(root.resolve("sub"));
        Files.writeString(root.resolve("file.txt"), "inside");
        ToolContext context = new ToolContext(binding(root));
        assertError(execute("{\"path\":\"../project-other/secret.txt\"}", context),
                ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE);
        String absolute = MAPPER.createObjectNode().put("path", sibling.resolve("secret.txt").toString()).toString();
        assertError(execute(absolute, context), ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE);
        assertEquals("inside", success(execute("{\"path\":\"sub/../file.txt\"}", context))
                .at("/lines/0/text").asText());
    }

    /** 真正的链接目标也检查范围，包括通过目录链接访问外部文件。 */
    @Test
    void rejectsExternalSymlinkTarget() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "external fixture");
        symbolicLink(root.resolve("linked"), outside);
        assertError(execute("{\"path\":\"linked/secret.txt\"}", new ToolContext(binding(root))),
                ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE);
    }

    /** 工作区内部链接正常读取，并返回真实目标的相对路径。 */
    @Test
    void acceptsInternalSymlinkTarget() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "inside");
        symbolicLink(directory.resolve("alias.txt"), directory.resolve("file.txt"));
        JsonNode page = success(execute("{\"path\":\"alias.txt\"}"));
        assertEquals("file.txt", page.path("path").asText());
        assertEquals("inside", page.at("/lines/0/text").asText());
    }

    /** 不存在、目录与无工作区分别返回清晰错误类别。 */
    @Test
    void distinguishesMissingDirectoryAndWorkspaceErrors() throws Exception {
        assertError(execute("{\"path\":\"missing.txt\"}"), ToolResult.ErrorCode.FILE_NOT_FOUND);
        Files.createDirectory(directory.resolve("folder"));
        assertError(execute("{\"path\":\"folder\"}"), ToolResult.ErrorCode.NOT_A_FILE);
        assertError(execute("{\"path\":\"file.txt\"}", new ToolContext(WorkspaceBinding.none())),
                ToolResult.ErrorCode.WORKSPACE_REQUIRED);
        Path removed = Files.createDirectory(directory.resolve("removed"));
        ToolContext stale = new ToolContext(binding(removed));
        Files.delete(removed);
        assertError(execute("{\"path\":\"file.txt\"}", stale), ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE);
    }

    /** 非法 UTF-8 与二进制 NUL 不转换为乱码或向模型回填原始字节。 */
    @Test
    void rejectsInvalidEncodingAndNulCharacters() throws Exception {
        Files.write(directory.resolve("file.txt"), new byte[]{(byte) 0xC3, 0x28});
        assertError(execute("{\"path\":\"file.txt\"}"), ToolResult.ErrorCode.INVALID_ENCODING);
        Files.writeString(directory.resolve("file.txt"), "fixture\0binary");
        assertError(execute("{\"path\":\"file.txt\"}"), ToolResult.ErrorCode.NOT_TEXT_FILE);
        Files.write(directory.resolve("file.txt"), new byte[]{(byte) 0xFF, (byte) 0xFE, 0x41, 0});
        assertError(execute("{\"path\":\"file.txt\"}"), ToolResult.ErrorCode.INVALID_ENCODING);
    }

    /** 文件恰好 4 MiB 可读，超过限制在读取前拒绝。 */
    @Test
    void enforcesFileSizeIncludingExactBoundary() throws Exception {
        String content = ("a".repeat(4095) + "\n").repeat(1024);
        Path file = directory.resolve("file.txt");
        Files.writeString(file, content);
        assertEquals(ReadFileTool.MAX_FILE_BYTES, Files.size(file));
        JsonNode page = success(execute("{\"path\":\"file.txt\",\"startLine\":1024,\"maxLines\":1}"));
        assertEquals(1024, page.at("/lines/0/line").asInt());
        assertFalse(page.path("hasMore").asBoolean());
        Files.writeString(file, content + "x");
        assertError(execute("{\"path\":\"file.txt\"}"), ToolResult.ErrorCode.FILE_TOO_LARGE);
    }

    /** 超长行有界报错；满页仅探测下一字符，不解析下一页长行。 */
    @Test
    void enforcesLineLimitOnlyOnReadLines() throws Exception {
        String longLine = "x".repeat(ReadFileTool.MAX_LINE_CHARS + 1);
        Files.writeString(directory.resolve("file.txt"), longLine);
        assertError(execute("{\"path\":\"file.txt\"}"), ToolResult.ErrorCode.LINE_TOO_LONG);
        Files.writeString(directory.resolve("file.txt"), "short\n" + longLine);
        JsonNode page = success(execute("{\"path\":\"file.txt\",\"maxLines\":1}"));
        assertEquals("short", page.at("/lines/0/text").asText());
        assertTrue(page.path("hasMore").asBoolean());
        assertError(execute("{\"path\":\"file.txt\",\"startLine\":2}"), ToolResult.ErrorCode.LINE_TOO_LONG);
        Files.writeString(directory.resolve("file.txt"), "x".repeat(ReadFileTool.MAX_LINE_CHARS));
        assertEquals(ReadFileTool.MAX_LINE_CHARS,
                success(execute("{\"path\":\"file.txt\"}", 20_000)).at("/lines/0/text").asText().length());
    }

    /** 直接调用也尊重中断；执行器保留标志并返回关联结果。 */
    @Test
    void preservesInterruption() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> new ReadFileTool().execute(
                    MAPPER.readTree("{\"path\":\"file.txt\"}"), context(16_000)));
            assertError(execute("{\"path\":\"file.txt\"}"), ToolResult.ErrorCode.INTERRUPTED);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // 不影响同一测试线程的下一项检查。
        }
    }

    /** 使用默认宿主预算执行工具。 */
    private ToolResult execute(String arguments) throws Exception { return execute(arguments, 16_000); }

    /** 使用明确预算执行工具，模拟宿主限制。 */
    private ToolResult execute(String arguments, int limit) throws Exception { return execute(arguments, context(limit)); }

    /** 通过真实注册表与执行器调用，而非绕开框架调用业务代码。 */
    private ToolResult execute(String arguments, ToolContext context) {
        return new ToolExecutor(new ToolRegistry().register(new ReadFileTool()))
                .execute(new ToolCall("read-call", "read_file", arguments), context);
    }

    /** 创建由宿主确定的工作区上下文。 */
    private ToolContext context(int limit) throws IOException { return new ToolContext(binding(directory), limit); }

    /** 与 WorkspaceResolver 一致，绑定真实目录。 */
    private WorkspaceBinding binding(Path root) throws IOException {
        return new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, root.toRealPath().toString());
    }

    /** 成功结果必须是完整 JSON，而非执行器截断后残缺内容。 */
    private JsonNode success(ToolResult result) throws IOException {
        assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output());
        assertFalse(result.truncated());
        return MAPPER.readTree(result.output());
    }

    /** 检查错误类别和 ID 关联，错误消息不泄漏临时绝对路径或外部内容。 */
    private void assertError(ToolResult result, ToolResult.ErrorCode code) {
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertEquals(code, result.errorCode());
        assertEquals("read-call", result.toMessage().toolCallId());
        assertFalse(result.output().contains(directory.toString()));
        assertFalse(result.output().contains("external fixture"));
    }

    /** Windows 未授予符号链接权限时跳过链接专项，不把未运行检查当作通过。 */
    private void symbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target.toRealPath());
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            assumeTrue(false, "Symbolic links unavailable in this test environment.");
        }
    }
}
