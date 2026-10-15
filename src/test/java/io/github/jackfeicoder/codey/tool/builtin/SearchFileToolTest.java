package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceFiles;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用临时文件验证名称/路径定位、无正文读取、遍历边界和完整结果，不接触真实配置。 */
class SearchFileToolTest {
    /** 所有测试文件及目录都位于独立临时路径。 */
    @TempDir Path directory;
    /** 参数与结果 JSON 使用同一测试编码器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 工具 schema 明确定位职责，普通聊天不可见、不可执行。 */
    @Test
    void exposesOnlyFileLocationArgumentsAndRequiresWorkspace() throws Exception {
        var tool = new SearchFileTool();
        var schema = tool.definition().parameters();
        assertEquals("search_file", tool.definition().name());
        assertEquals(List.of("pattern"), MAPPER.convertValue(schema.path("required"), List.class));
        assertFalse(schema.path("properties").has("maxDepth"));
        assertEquals(100, schema.at("/properties/maxResults/default").asInt());
        assertFalse(schema.at("/properties/caseSensitive/default").asBoolean());
        assertFalse(schema.path("additionalProperties").asBoolean());
        var none = new ToolContext(WorkspaceBinding.none());
        assertTrue(registry().definitions(none).isEmpty());
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED,
                new ToolExecutor(registry()).execute(call("{\"pattern\":\"*\"}"), none).errorCode());
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED, assertThrows(ToolException.class,
                () -> tool.execute(MAPPER.readTree("{\"pattern\":\"*\"}"), none)).code());
    }

    /** 精确文件名递归定位，不依赖预先 list_files，也不根据文件内容匹配。 */
    @Test
    void exactNameFindsAllMatchingFilesAndNeverReadsContents() throws Exception {
        file("src/main/java/AgentLoop.java", "secret-text-only");
        file("AgentLoop.java", "different content");
        file("wrong.txt", "AgentLoop.java");
        Files.createDirectories(directory.resolve("folder/AgentLoop.java"));
        var result = execute("{\"pattern\":\"AgentLoop.java\"}");
        var body = success(result);
        assertEquals(List.of("AgentLoop.java", "src/main/java/AgentLoop.java"), paths(body));
        assertEquals(2, body.path("returnedFiles").asInt());
        assertFalse(body.path("truncated").asBoolean());
        assertFalse(result.output().contains("secret-text-only"));
        assertFalse(result.output().contains(directory.toString()));
        assertEquals("search-id", result.toMessage().toolCallId());
        Files.write(directory.resolve("binary.java"), new byte[]{0, -1, -2});
        assertEquals(List.of("binary.java"), paths(success(execute("{\"pattern\":\"binary.java\"}"))));
    }

    /** 星号和问号限定文件名，双星目录段匹配零层与多层；普通单星不跨目录。 */
    @Test
    void matchesFileAndPathWildcardsWithZeroLevelDoubleStar() throws Exception {
        file("App.java", "");
        file("src/App.java", "");
        file("src/main/App.java", "");
        file("src/main/ConfigLoader.java", "");
        file("src/main/Config1.java", "");
        assertEquals(List.of("src/main/Config1.java", "src/main/ConfigLoader.java"),
                paths(success(execute("{\"pattern\":\"*Config*.java\"}"))));
        assertEquals(List.of("src/main/Config1.java"), paths(success(execute("{\"pattern\":\"Config?.java\"}"))));
        assertEquals(List.of("App.java", "src/App.java", "src/main/App.java"),
                paths(success(execute("{\"pattern\":\"**/App.java\"}"))));
        assertEquals(List.of("src/App.java", "src/main/App.java"), paths(success(execute("{\"pattern\":\"src/**/App.java\"}"))));
        assertEquals(List.of("src/App.java"), paths(success(execute("{\"pattern\":\"src/*.java\"}"))));
        assertEquals(5, paths(success(execute("{\"pattern\":\"**\"}"))).size());
    }

    /** 末尾双星表示目录内任意层文件，不应命中同名的普通文件。 */
    @Test
    void terminalDoubleStarRequiresFileBelowDirectoryPrefix() throws Exception {
        file("src", "a regular file, not a directory");
        file("lib/A.java", "");
        file("lib/deep/B.java", "");
        assertTrue(paths(success(execute("{\"pattern\":\"src/**\"}"))).isEmpty());
        assertEquals(List.of("lib/A.java", "lib/deep/B.java"), paths(success(execute("{\"pattern\":\"lib/**\"}"))));
    }

    /** 带路径模式相对搜索起点匹配，输出仍是工作区相对路径；支持 Windows 模式分隔符。 */
    @Test
    void scopesPatternsToDirectoryAndNormalizesSeparators() throws Exception {
        file("src/main/resources/prompts/base.md", "");
        file("other/prompts/base.md", "");
        var args = MAPPER.createObjectNode().put("pattern", "**\\prompts\\*.md").put("path", "src/./main/..");
        var body = success(execute(args.toString()));
        assertEquals("src", body.path("path").asText());
        assertEquals("**/prompts/*.md", body.path("pattern").asText());
        assertEquals(List.of("src/main/resources/prompts/base.md"), paths(body));
        assertTrue(paths(success(execute("{\"pattern\":\"src/**/*.md\",\"path\":\"src\"}"))).isEmpty());
    }

    /** 大小写行为与平台无关，匹配保留实际文件名；问号按完整 Unicode 字符计算。 */
    @Test
    void supportsExplicitCaseAndUnicodeWithoutRegexInterpretation() throws Exception {
        file("目录/配置😀.JAVA", "");
        assertEquals(List.of("目录/配置😀.JAVA"), paths(success(execute("{\"pattern\":\"配置?.java\"}"))));
        assertTrue(paths(success(execute("{\"pattern\":\"配置?.java\",\"caseSensitive\":true}"))).isEmpty());
        file("a[1](draft).md", "");
        assertEquals(List.of("a[1](draft).md"), paths(success(execute("{\"pattern\":\"a[1](draft).md\"}"))));
    }

    /** 文件定位深入超过原先二十层的目录，工具定义与结果均不暴露深度参数。 */
    @Test
    void searchesBeyondTwentyLevelsWithoutDepthParameter() throws Exception {
        String path = "d/".repeat(64) + "Tool.java";
        file(path, "");
        var body = success(execute("{\"pattern\":\"Tool.java\"}"));
        assertEquals(List.of(path), paths(body));
        assertFalse(body.has("maxDepth"));
        assertFalse(body.path("truncated").asBoolean());
        assertTrue(body.path("limitReason").isNull());
        assertEquals(List.of(path), paths(success(execute("{\"pattern\":\"**/Tool.java\"}"))));
    }

    /** 过滤规则与 list_files 共用，显式指定被忽略目录仍可定位内部文件。 */
    @Test
    void skipsIgnoredDirectoriesButAllowsExplicitSearchRoot() throws Exception {
        for (String name : WorkspaceFiles.IGNORED_DIRECTORY_NAMES) file(name + "/Found.java", "");
        file("src/target/Found.java", "");
        file("src/Found.java", "");
        assertEquals(List.of("src/Found.java"), paths(success(execute("{\"pattern\":\"Found.java\"}"))));
        assertEquals(List.of("target/Found.java"), paths(success(execute("{\"pattern\":\"Found.java\",\"path\":\"target\"}"))));
    }

    /** 空结果与恰好到条数上限保持完整；发现额外命中才报告截断。 */
    @Test
    void distinguishesNoMatchesExactLimitAndExtraMatches() throws Exception {
        var empty = success(execute("{\"pattern\":\"*.java\"}"));
        assertTrue(paths(empty).isEmpty());
        assertFalse(empty.path("truncated").asBoolean());
        assertTrue(empty.path("limitReason").isNull());
        file("a.java", "");
        assertFalse(success(execute("{\"pattern\":\"*.java\",\"maxResults\":1}")).path("truncated").asBoolean());
        file("b.java", "");
        var limited = success(execute("{\"pattern\":\"*.java\",\"maxResults\":1}"));
        assertEquals(List.of("a.java"), paths(limited));
        assertEquals("maxResults", limited.path("limitReason").asText());
    }

    /** 输出上限内保留完整 JSON 与 Unicode；连第一条路径都放不下时返回明确错误。 */
    @Test
    void outputLimitDoesNotBreakJsonOrPretendNoMatches() throws Exception {
        String name = "中文😀".repeat(20);
        file("a" + name, "");
        file("b" + name, "");
        var one = success(execute("{\"pattern\":\"*\",\"maxResults\":1}"));
        int budget = one.toString().codePointCount(0, one.toString().length()) + 30;
        var result = execute("{\"pattern\":\"*\"}", budget);
        var body = success(result);
        assertEquals(List.of("a" + name), paths(body));
        assertEquals("outputLimit", body.path("limitReason").asText());
        assertTrue(result.output().codePointCount(0, result.output().length()) <= budget);
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, execute("{\"pattern\":\"*\"}", 20).errorCode());
        Files.createDirectory(directory.resolve("empty"));
        var empty = success(execute("{\"pattern\":\"*\",\"path\":\"empty\"}"));
        int smallBudget = empty.toString().length() + 20;
        file("empty/" + name, "");
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                execute("{\"pattern\":\"*\",\"path\":\"empty\"}", smallBudget).errorCode());
    }

    /** 错误参数在遍历前拒绝，不把 query 内容搜索混入文件定位接口。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"pattern\":null}", "{\"pattern\":1}", "{\"pattern\":\" \"}",
            "{\"pattern\":\"*\",\"path\":null}", "{\"pattern\":\"*\",\"path\":1}", "{\"pattern\":\"*\",\"path\":\" \"}",
            "{\"pattern\":\"*\",\"maxDepth\":0}", "{\"pattern\":\"*\",\"maxDepth\":21}", "{\"pattern\":\"*\",\"maxDepth\":1.0}",
            "{\"pattern\":\"*\",\"maxDepth\":20}",
            "{\"pattern\":\"*\",\"maxResults\":0}", "{\"pattern\":\"*\",\"maxResults\":1001}", "{\"pattern\":\"*\",\"maxResults\":4294967297}",
            "{\"pattern\":\"*\",\"maxResults\":null}", "{\"pattern\":\"*\",\"maxResults\":\"2\"}",
            "{\"pattern\":\"*\",\"caseSensitive\":\"false\"}", "{\"pattern\":\"*\",\"caseSensitive\":null}",
            "{\"pattern\":\"*\",\"query\":\"key\"}", "{\"pattern\":\"*\",\"workspace\":\"other\"}",
            "{\"pattern\":\"../*.java\"}", "{\"pattern\":\"/src/*.java\"}", "{\"pattern\":\"C:*.java\"}",
            "{\"pattern\":\"src//*.java\"}", "{\"pattern\":\"**App.java\"}"})
    void rejectsInvalidArguments(String json) throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(json).errorCode());
    }

    /** 模式长度和控制字符同样受限，不把输入直接送进正则引擎。 */
    @Test
    void rejectsOversizedPatternsAndControlCharacters() throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,
                execute(MAPPER.createObjectNode().put("pattern", "a".repeat(257)).toString()).errorCode());
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,
                execute(MAPPER.createObjectNode().put("pattern", "a\nb").toString()).errorCode());
    }

    /** 搜索路径限制在真实工作区；缺失目录、文件起点及失效工作区分别给出错误。 */
    @Test
    void validatesSearchDirectoryAndWorkspaceBoundary() throws Exception {
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, execute("{\"pattern\":\"*\",\"path\":\"..\"}").errorCode());
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE,
                execute(MAPPER.createObjectNode().put("pattern", "*").put("path", directory.toString()).toString()).errorCode());
        assertEquals(ToolResult.ErrorCode.DIRECTORY_NOT_FOUND, execute("{\"pattern\":\"*\",\"path\":\"missing\"}").errorCode());
        file("a.txt", "");
        assertEquals(ToolResult.ErrorCode.NOT_A_DIRECTORY, execute("{\"pattern\":\"*\",\"path\":\"a.txt\"}").errorCode());
        var lost = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.resolve("lost").toString()));
        assertEquals(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE,
                new ToolExecutor(registry()).execute(call("{\"pattern\":\"*\"}"), lost).errorCode());
    }

    /** 发现的链接不返回也不递归；显式起点的链接仍必须解析到工作区以内。 */
    @Test
    void excludesLinksAndRejectsOutsideLinkedSearchRoot() throws Exception {
        Path project = Files.createDirectory(directory.resolve("project"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(project.resolve("real.java"), "");
        Files.writeString(outside.resolve("outside.java"), "");
        link(project.resolve("file-link.java"), outside.resolve("outside.java"));
        link(project.resolve("directory-link"), outside);
        link(project.resolve("cycle"), project);
        link(project.resolve("broken"), outside.resolve("missing"));
        var context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, project.toRealPath().toString()));
        var executor = new ToolExecutor(registry());
        assertEquals(List.of("real.java"), paths(success(executor.execute(call("{\"pattern\":\"*\"}"), context))));
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE,
                executor.execute(call("{\"pattern\":\"*\",\"path\":\"directory-link\"}"), context).errorCode());
    }

    /** 无命中时也受扫描预算约束，空 files 不意味着整个工作区都不存在目标。 */
    @Test
    void scanLimitIsReportedEvenWithoutAnyMatch() throws Exception {
        for (int i = 0; i <= WorkspaceFiles.MAX_SCANNED_ENTRIES; i++) Files.createFile(directory.resolve("f" + i));
        var body = success(execute("{\"pattern\":\"absent.java\"}"));
        assertTrue(paths(body).isEmpty());
        assertEquals(WorkspaceFiles.MAX_SCANNED_ENTRIES, body.path("scannedEntries").asInt());
        assertEquals("scanLimit", body.path("limitReason").asText());
    }

    /** 已取消任务不开始扫描，执行器保留中断标志。 */
    @Test
    void interruptionStopsSearchAndPreservesFlag() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, execute("{\"pattern\":\"*\"}").errorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    /** 在临时工作区创建文件。 */
    private void file(String path, String text) throws IOException {
        Path file = directory.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
    /** 每项测试独立装配执行器，不直接绕过参数解析。 */
    private ToolRegistry registry() { return new ToolRegistry().register(new SearchFileTool()); }
    /** 固定关联 ID，检查结果可回填到原始工具调用。 */
    private ToolCall call(String json) { return new ToolCall("search-id", "search_file", json); }
    /** 默认输出预算。 */
    private ToolResult execute(String json) throws IOException { return execute(json, ToolContext.DEFAULT_MAX_OUTPUT_CHARS); }
    /** 可以缩小宿主预算来验证 JSON 不被外层截断。 */
    private ToolResult execute(String json, int budget) throws IOException {
        var context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString()), budget);
        return new ToolExecutor(registry()).execute(call(json), context);
    }
    /** 成功结果必须是完整 JSON，外层不能通过截断破坏结构。 */
    private JsonNode success(ToolResult result) throws Exception {
        assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output());
        assertFalse(result.truncated());
        return MAPPER.readTree(result.output());
    }
    /** 提取命中路径便于检查顺序与路径范围。 */
    private List<String> paths(JsonNode body) {
        var paths = new ArrayList<String>();
        body.path("files").forEach(file -> paths.add(file.asText()));
        return paths;
    }
    /** 部分 Windows 环境缺少创建链接权限，使用标准测试跳过机制。 */
    private void link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException denied) { assumeTrue(false, "Symlink creation unavailable"); }
    }
}
