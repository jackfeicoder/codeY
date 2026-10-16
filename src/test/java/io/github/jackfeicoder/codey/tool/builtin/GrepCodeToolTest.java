package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.filesystem.Utf8TextReader;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceFiles;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 真实临时文件验证字面搜索、位置、共享文本规则、扫描/输出预算和工作区边界。 */
class GrepCodeToolTest {
    /** 每项测试独立的工作区，不读取真实项目配置。 */
    @TempDir Path directory;
    /** 参数与结果正文编码器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** schema 只有内容查询与范围字段，无深度、正则或语义开关。 */
    @Test
    void definitionAndWorkspaceVisibilityMatchContentSearch() throws Exception {
        var definition = new GrepCodeTool().definition();
        assertEquals("grep_code", definition.name());
        var schema = definition.parameters();
        assertEquals("query", schema.path("required").get(0).asText());
        assertEquals(50, schema.at("/properties/maxResults/default").asInt());
        assertEquals(500, schema.at("/properties/maxResults/maximum").asInt());
        assertFalse(schema.path("properties").has("maxDepth"));
        assertFalse(schema.path("properties").has("regex"));
        assertEquals("*", schema.at("/properties/filePattern/default").asText());
        var none = new ToolContext(WorkspaceBinding.none());
        assertTrue(registry().definitions(none).isEmpty());
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED,
                new ToolExecutor(registry()).execute(call("{\"query\":\"login\"}"), none).errorCode());
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED, assertThrows(ToolException.class,
                () -> new GrepCodeTool().execute(MAPPER.readTree("{\"query\":\"login\"}"), none)).code());
    }

    /** 匹配正文而非文件名，行号从一开始，同一行重复关键词只返回一项。 */
    @Test
    void findsContentWithPathsAndLineNumbersButDoesNotMatchFileNames() throws Exception {
        file("a.txt", "heading\n login login \nlast");
        file("src/Auth.java", "void LOGIN() {}\n");
        file("login.java", "nothing relevant\n");
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(List.of("a.txt", "src/Auth.java"), paths(body));
        assertEquals(2, body.path("returnedMatches").asInt());
        assertEquals(2, body.at("/matches/0/line").asLong());
        assertEquals(" login login ", body.at("/matches/0/text").asText());
        assertEquals(1, body.at("/matches/1/line").asLong());
        assertEquals(3, body.path("searchedFiles").asInt());
        assertTrue(body.path("complete").asBoolean());
        assertFalse(body.path("truncated").asBoolean());
        assertEquals("grep-id", execute("{\"query\":\"login\"}").toMessage().toolCallId());
    }

    /** 精确包含匹配不展开正则或同义词；空格保留，忽略大小写由参数明确控制。 */
    @Test
    void matchesLiteralTextAndPreservesWhitespaceAndUnicode() throws Exception {
        file("Auth.java", "LOGIN\nlogin\nauthenticate\nlogin|auth\n 登陆😀 \n");
        assertEquals(2, success(execute("{\"query\":\"login\",\"caseSensitive\":true}")).path("returnedMatches").asInt());
        assertEquals(3, success(execute("{\"query\":\"login\"}")).path("returnedMatches").asInt());
        var literal = success(execute("{\"query\":\"login|auth\"}"));
        assertEquals(1, literal.path("returnedMatches").asInt());
        assertEquals(4, literal.at("/matches/0/line").asLong());
        var unicode = success(execute("{\"query\":\" 登陆😀 \"}"));
        assertEquals(" 登陆😀 ", unicode.at("/matches/0/text").asText());
        assertEquals(5, unicode.at("/matches/0/line").asLong());
    }

    /** BOM、LF/CRLF/CR 与末尾换行沿用读取工具的规则。 */
    @Test
    void sharesUtf8BomAndMixedLineEndingRulesWithReadFile() throws Exception {
        file("mixed.txt", "\uFEFFlogin\r\n  LOGIN  \rlogin\n");
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(3, body.path("returnedMatches").asInt());
        assertEquals("login", body.at("/matches/0/text").asText());
        assertEquals("  LOGIN  ", body.at("/matches/1/text").asText());
        assertEquals(3, body.at("/matches/2/line").asLong());
        assertEquals(Files.size(directory.resolve("mixed.txt")), body.path("readBytes").asLong());
    }

    /** 文件筛选只限定范围，模式相对所选目录匹配；直接文件路径无需先遍历目录。 */
    @Test
    void scopesToDirectoryOrFileAndSupportsFilePattern() throws Exception {
        file("src/main/Auth.JAVA", "login");
        file("src/main/view.jsx", "login");
        file("other/Auth.java", "login");
        var body = success(execute("{\"query\":\"login\",\"path\":\"src\",\"filePattern\":\"**/*.java\"}"));
        assertEquals(List.of("src/main/Auth.JAVA"), paths(body));
        assertEquals(1, body.path("scannedFiles").asInt());
        assertEquals(List.of("src/main/view.jsx"), paths(success(execute("{\"query\":\"login\",\"path\":\"src/main/view.jsx\"}"))));
        var excluded = success(execute("{\"query\":\"login\",\"path\":\"src/main/Auth.JAVA\",\"filePattern\":\"*.java\",\"caseSensitive\":true}"));
        assertTrue(paths(excluded).isEmpty());
        assertEquals(0, excluded.path("scannedFiles").asInt());
    }

    /** 目录查找不设人为深度上限，匹配路径仍相对工作区。 */
    @Test
    void searchesPastTwentyDirectoryLevels() throws Exception {
        String path = "d/".repeat(32) + "A.java";
        file(path, "login");
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(List.of(path), paths(body));
        assertFalse(body.has("maxDepth"));
        assertTrue(body.path("complete").asBoolean());
    }

    /** 过滤目录与其他工具一致，显式起点可以检查默认忽略目录。 */
    @Test
    void ignoresBuildDirectoriesButAllowsExplicitPath() throws Exception {
        for (String name : WorkspaceFiles.IGNORED_DIRECTORY_NAMES) file(name + "/a.txt", "login");
        file("src/target/a.txt", "login");
        file("src/a.txt", "login");
        assertEquals(List.of("src/a.txt"), paths(success(execute("{\"query\":\"login\"}"))));
        assertEquals(List.of("target/a.txt"), paths(success(execute("{\"query\":\"login\",\"path\":\"target\"}"))));
    }

    /** 遇到晚出现的坏编码或 NUL 时撤回该文件匹配，其他文件的匹配保留。 */
    @Test
    void recordsSkippedFilesAndRollsBackMatchesFromInvalidFiles() throws Exception {
        file("a-valid.txt", "login");
        String prefix = "login\n" + "x\n".repeat(5000);
        file("b-binary.dat", prefix + "\0");
        byte[] bad = (prefix + "end").getBytes(StandardCharsets.UTF_8);
        bad[bad.length - 1] = (byte) 0xff;
        Files.write(directory.resolve("c-invalid.txt"), bad);
        file("d-long.txt", "login" + "x".repeat(Utf8TextReader.MAX_LINE_CHARS));
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(List.of("a-valid.txt"), paths(body));
        assertEquals(4, body.path("scannedFiles").asInt());
        assertEquals(1, body.path("searchedFiles").asInt());
        assertEquals(3, body.path("skippedFiles").asInt());
        assertEquals(1, body.at("/skippedReasons/NOT_TEXT_FILE").asInt());
        assertEquals(1, body.at("/skippedReasons/INVALID_ENCODING").asInt());
        assertEquals(1, body.at("/skippedReasons/LINE_TOO_LONG").asInt());
        assertFalse(body.path("complete").asBoolean());
        assertFalse(body.path("truncated").asBoolean());
    }

    /** 单文件大小先检查，不读取超大文件，也不把跳过当成已完整无命中。 */
    @Test
    void skipsOversizedFileWithoutReadingItsContents() throws Exception {
        Path large = directory.resolve("large.txt");
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(Utf8TextReader.MAX_FILE_BYTES + 1L); }
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(1, body.at("/skippedReasons/FILE_TOO_LARGE").asInt());
        assertEquals(0, body.path("readBytes").asLong());
        assertFalse(body.path("complete").asBoolean());
    }

    /** 空目录和完全无命中可以明确完成，恰好达到匹配上限也不误报。 */
    @Test
    void distinguishesNoMatchExactResultLimitAndExtraMatch() throws Exception {
        var empty = success(execute("{\"query\":\"login\"}"));
        assertTrue(empty.path("complete").asBoolean());
        file("a.txt", "login\nother\n");
        assertFalse(success(execute("{\"query\":\"login\",\"maxResults\":1}")).path("truncated").asBoolean());
        file("b.txt", "LOGIN\n");
        var limited = success(execute("{\"query\":\"login\",\"maxResults\":1}"));
        assertEquals(List.of("a.txt"), paths(limited));
        assertEquals("maxResults", limited.path("limitReason").asText());
        assertEquals(1, limited.path("partialFiles").asInt());
        assertFalse(limited.path("complete").asBoolean());
    }

    /** JSON 和完整行一起计入宿主预算，成功结果不会被外层截成无效结构。 */
    @Test
    void outputLimitKeepsWholeMatchingLinesAndJson() throws Exception {
        file("a.txt", "login " + "中😀".repeat(450) + "\nlogin " + "中😀".repeat(450));
        var result = execute("{\"query\":\"login\"}", 1900);
        var body = success(result);
        assertEquals(1, body.path("returnedMatches").asInt());
        assertEquals("outputLimit", body.path("limitReason").asText());
        assertTrue(result.output().codePointCount(0, result.output().length()) <= 1900);
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, execute("{\"query\":\"login\"}", 10).errorCode());
        file("long.txt", "login" + "x".repeat(3000));
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                execute("{\"query\":\"login\",\"path\":\"long.txt\"}", 1900).errorCode());
    }

    /** 候选文件预算独立于目录项预算，空文件也计入候选文件数。 */
    @Test
    void candidateFileLimitIsReported() throws Exception {
        for (int i = 0; i <= GrepCodeTool.MAX_FILES; i++) file(String.format("%04d.txt", i), "");
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(GrepCodeTool.MAX_FILES, body.path("searchedFiles").asInt());
        assertEquals("fileLimit", body.path("limitReason").asText());
    }

    /** 跨文件字节总预算按实际读取计算；精确耗尽时不额外探测 EOF。 */
    @Test
    void totalByteLimitBoundsSearchEvenWithoutAnyMatches() throws Exception {
        String text = ("x".repeat(1023) + "\n").repeat(4096);
        for (int i = 0; i < 9; i++) file(i + ".txt", text);
        var body = success(execute("{\"query\":\"login\"}"));
        assertEquals(GrepCodeTool.MAX_TOTAL_BYTES, body.path("readBytes").asLong());
        assertEquals("byteLimit", body.path("limitReason").asText());
        assertEquals(1, body.path("partialFiles").asInt());
        assertTrue(paths(body).isEmpty());
        assertFalse(body.path("complete").asBoolean());
    }

    /** 名称筛选没有候选时，目录元数据扫描也保持有界。 */
    @Test
    void scanLimitIsVisibleEvenWhenFilePatternMatchesNothing() throws Exception {
        for (int i = 0; i <= WorkspaceFiles.MAX_SCANNED_ENTRIES; i++) Files.createFile(directory.resolve("f" + i));
        var body = success(execute("{\"query\":\"login\",\"filePattern\":\"*.java\"}"));
        assertEquals("scanLimit", body.path("limitReason").asText());
        assertEquals(WorkspaceFiles.MAX_SCANNED_ENTRIES, body.path("scannedEntries").asInt());
        assertEquals(0, body.path("scannedFiles").asInt());
    }

    /** 无正文搜索参数被忽略或强制转换，maxDepth 与 regex 明确拒绝。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "{\"query\":null}", "{\"query\":1}", "{\"query\":\" \"}",
            "{\"query\":\"login\",\"path\":null}", "{\"query\":\"login\",\"path\":1}", "{\"query\":\"login\",\"path\":\" \"}",
            "{\"query\":\"login\",\"filePattern\":null}", "{\"query\":\"login\",\"filePattern\":1}", "{\"query\":\"login\",\"filePattern\":\"\"}",
            "{\"query\":\"login\",\"filePattern\":\"../*\"}", "{\"query\":\"login\",\"caseSensitive\":null}", "{\"query\":\"login\",\"caseSensitive\":1}",
            "{\"query\":\"login\",\"maxResults\":0}", "{\"query\":\"login\",\"maxResults\":501}", "{\"query\":\"login\",\"maxResults\":1.0}",
            "{\"query\":\"login\",\"maxResults\":\"1\"}", "{\"query\":\"login\",\"maxResults\":null}", "{\"query\":\"login\",\"maxResults\":4294967297}",
            "{\"query\":\"login\",\"maxDepth\":20}", "{\"query\":\"login\",\"regex\":true}", "{\"query\":\"login\",\"workspace\":\"other\"}"})
    void rejectsInvalidArguments(String arguments) throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(arguments).errorCode());
    }

    /** 多行和超长查询不进入扫描，返回受控错误而不回显原始输入。 */
    @Test
    void rejectsMultilineControlAndOversizedQueries() throws Exception {
        for (String query : List.of("a\nb", "a\rb", "a\0b", "x".repeat(257))) {
            assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,
                    execute(MAPPER.createObjectNode().put("query", query).toString()).errorCode());
        }
    }

    /** 相对路径与真实路径均受工作区边界限制。 */
    @Test
    void validatesPathWorkspaceAndMissingScope() throws Exception {
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, execute("{\"query\":\"login\",\"path\":\"..\"}").errorCode());
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE,
                execute(MAPPER.createObjectNode().put("query", "login").put("path", directory.toString()).toString()).errorCode());
        assertEquals(ToolResult.ErrorCode.FILE_NOT_FOUND, execute("{\"query\":\"login\",\"path\":\"missing\"}").errorCode());
        var lost = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.resolve("lost").toString()));
        assertEquals(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE,
                new ToolExecutor(registry()).execute(call("{\"query\":\"login\"}"), lost).errorCode());
    }

    /** 遍历不进入外部/循环链接；显式指向工作区外的文件或目录也拒绝。 */
    @Test
    void skipsDiscoveredLinksAndRejectsOutsideScope() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(project.resolve("inside.txt"), "login");
        Files.writeString(outside.resolve("outside.txt"), "login");
        link(project.resolve("external.txt"), outside.resolve("outside.txt"));
        link(project.resolve("external-dir"), outside);
        link(project.resolve("cycle"), project);
        var context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, project.toRealPath().toString()));
        var executor = new ToolExecutor(registry());
        assertEquals(List.of("inside.txt"), paths(success(executor.execute(call("{\"query\":\"login\"}"), context))));
        for (String path : List.of("external.txt", "external-dir")) {
            assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE,
                    executor.execute(call(MAPPER.createObjectNode().put("query", "login").put("path", path).toString()), context).errorCode());
        }
    }

    /** 已取消时不开始读取或搜索，不清除中断标志。 */
    @Test
    void interruptionIsPreserved() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, execute("{\"query\":\"login\"}").errorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    /** 创建临时 UTF-8 文件，返回实际路径供特殊字节测试使用。 */
    private void file(String path, String text) throws IOException {
        Path file = directory.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }
    /** 独立注册内容搜索，测试不依赖其他工具。 */
    private ToolRegistry registry() { return new ToolRegistry().register(new GrepCodeTool()); }
    /** 保留稳定关联 ID。 */
    private ToolCall call(String json) { return new ToolCall("grep-id", "grep_code", json); }
    /** 默认宿主输出预算。 */
    private ToolResult execute(String json) throws IOException { return execute(json, ToolContext.DEFAULT_MAX_OUTPUT_CHARS); }
    /** 缩小预算验证完整输出。 */
    private ToolResult execute(String json, int limit) throws IOException {
        return new ToolExecutor(registry()).execute(call(json), new ToolContext(
                new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString()), limit));
    }
    /** 所有成功结果都必须保持可解析 JSON。 */
    private JsonNode success(ToolResult result) throws Exception {
        assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output()); assertFalse(result.truncated());
        return MAPPER.readTree(result.output());
    }
    /** 只提取内容命中的路径，不把文件名匹配当作正文命中。 */
    private List<String> paths(JsonNode body) {
        var result = new ArrayList<String>(); body.path("matches").forEach(match -> result.add(match.path("path").asText())); return result;
    }
    /** 缺少系统链接权限时按测试框架规则跳过。 */
    private void link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException denied) { assumeTrue(false, "Symlink creation unavailable"); }
    }
}
