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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用真实临时目录验证顺序、深度、忽略规则、路径边界和输出/扫描预算，不接触项目凭证。 */
class ListFilesToolTest {
    @TempDir Path directory;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void exposesSchemaOnlyForWorkspace() throws Exception {
        var tool = new ListFilesTool();
        var schema = tool.definition().parameters();
        assertEquals("list_files", tool.definition().name());
        assertEquals(".", schema.at("/properties/path/default").asText());
        assertEquals(10, schema.at("/properties/maxDepth/default").asInt());
        assertEquals(20, schema.at("/properties/maxDepth/maximum").asInt());
        assertEquals(200, schema.at("/properties/maxEntries/default").asInt());
        assertEquals(1000, schema.at("/properties/maxEntries/maximum").asInt());
        assertFalse(schema.path("additionalProperties").asBoolean());
        var registry = new ToolRegistry().register(tool);
        assertTrue(registry.definitions(new ToolContext(WorkspaceBinding.none())).isEmpty());
        assertEquals(1, registry.definitions(context(16000)).size());
        var result = new ToolExecutor(registry).execute(call("{}"), new ToolContext(WorkspaceBinding.none()));
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED, result.errorCode());
    }

    @Test
    void explicitDepthListsSortedTreeWithoutReadingFileContents() throws Exception {
        Files.createDirectories(directory.resolve("src/main"));
        Files.write(directory.resolve("z.bin"), new byte[]{0, -1});
        Files.writeString(directory.resolve("a.txt"), "private-file-content");
        Files.writeString(directory.resolve("src/a.txt"), "content");
        Files.writeString(directory.resolve("src/main/Deeper.java"), "content");
        var result = execute("{\"maxDepth\":2}");
        var body = success(result);
        assertEquals(List.of("a.txt", "src", "src/a.txt", "src/main", "z.bin"), paths(body));
        assertEquals("directory", body.at("/entries/1/type").asText());
        assertEquals("file", body.at("/entries/4/type").asText());
        assertEquals(2, body.at("/entries/3/depth").asInt());
        assertFalse(body.path("truncated").asBoolean());
        assertTrue(body.path("limitReason").isNull());
        assertEquals(".", body.path("path").asText());
        assertFalse(result.output().contains("private-file-content"));
        assertFalse(result.output().contains(directory.toString()));
        assertEquals("list-call", result.toMessage().toolCallId());
    }

    @Test
    void subdirectoryEntriesRemainRelativeToWorkspaceAndDepthCanBeChanged() throws Exception {
        Files.createDirectories(directory.resolve("src/main/java"));
        Files.writeString(directory.resolve("src/main/java/App.java"), "content");
        assertEquals(List.of("src", "src/main", "src/main/java", "src/main/java/App.java"),
                paths(success(execute("{\"maxDepth\":4}"))));
        var shallow = success(execute("{\"path\":\"src\",\"maxDepth\":1}"));
        assertEquals(List.of("src/main"), paths(shallow));
        assertEquals("src", shallow.path("path").asText());
        assertEquals(1, shallow.at("/entries/0/depth").asInt());
        assertEquals(List.of("src/main", "src/main/java", "src/main/java/App.java"),
                paths(success(execute("{\"path\":\"src/./main/..\"}"))));
    }

    /** 默认十层覆盖当前 Java 包路径和提示词；显式参数仍可覆盖配置默认值。 */
    @Test
    void defaultDepthReachesJavaPackagesAndPromptsAndCustomDepthChangesSchema() throws Exception {
        String javaFile = "src/main/java/io/github/jackfeicoder/codey/tool/builtin/Tool.java";
        String prompt = "src/main/resources/prompts/base.md";
        Files.createDirectories(directory.resolve(javaFile).getParent());
        Files.createDirectories(directory.resolve(prompt).getParent());
        Files.writeString(directory.resolve(javaFile), "class Tool {}");
        Files.writeString(directory.resolve(prompt), "system instructions");
        var result = success(execute("{}"));
        assertTrue(paths(result).containsAll(List.of(javaFile, prompt)));
        assertEquals(10, result.path("maxDepth").asInt());
        var custom = new ListFilesTool(3);
        var executor = new ToolExecutor(new ToolRegistry().register(custom));
        assertEquals(3, custom.definition().parameters().at("/properties/maxDepth/default").asInt());
        var shallow = success(executor.execute(call("{}"), context(16000)));
        assertFalse(paths(shallow).contains(javaFile));
        assertEquals(3, shallow.path("maxDepth").asInt());
        var deep = success(executor.execute(call("{\"maxDepth\":20}"), context(16000)));
        assertTrue(paths(deep).containsAll(List.of(javaFile, prompt)));
        assertThrows(IllegalArgumentException.class, () -> new ListFilesTool(0));
        assertThrows(IllegalArgumentException.class, () -> new ListFilesTool(21));
    }

    @Test
    void skipsCommonDirectoriesAtEveryLevelButExplicitPathCanInspectThem() throws Exception {
        for (String name : List.of(".git", "target", "node_modules", ".gradle", "build", "dist")) {
            Files.createDirectories(directory.resolve(name));
            Files.writeString(directory.resolve(name).resolve("hidden.txt"), "content");
        }
        Files.createDirectories(directory.resolve("src/target"));
        Files.writeString(directory.resolve("src/target/hidden.txt"), "content");
        Files.writeString(directory.resolve(".env.example"), "fixture");
        assertEquals(List.of(".env.example", "src"), paths(success(execute("{\"maxDepth\":8}"))));
        assertEquals(List.of("target/hidden.txt"), paths(success(execute("{\"path\":\"target\"}"))));
    }

    @Test
    void anOrdinaryFileNamedLikeIgnoredDirectoryIsStillListed() throws Exception {
        Files.writeString(directory.resolve("target"), "content");
        assertEquals(List.of("target"), paths(success(execute("{}"))));
    }

    @Test
    void emptyDirectoryAndExactEntryLimitAreNotReportedAsTruncated() throws Exception {
        var empty = success(execute("{}"));
        assertTrue(empty.path("entries").isEmpty());
        assertEquals(0, empty.path("scannedEntries").asInt());
        assertFalse(empty.path("truncated").asBoolean());
        Files.writeString(directory.resolve("one.txt"), "");
        assertFalse(success(execute("{\"maxEntries\":1}")).path("truncated").asBoolean());
        Files.writeString(directory.resolve("two.txt"), "");
        var limited = success(execute("{\"maxEntries\":1}"));
        assertEquals(List.of("one.txt"), paths(limited));
        assertTrue(limited.path("truncated").asBoolean());
        assertEquals("maxEntries", limited.path("limitReason").asText());
    }

    @Test
    void defaultEntryLimitAppliesToLargeDirectory() throws Exception {
        for (int i = 0; i < 201; i++) Files.createFile(directory.resolve(String.format("%03d.txt", i)));
        var body = success(execute("{}"));
        assertEquals(200, body.path("returnedEntries").asInt());
        assertEquals("maxEntries", body.path("limitReason").asText());
    }

    @Test
    void outputBudgetPreservesCompleteJsonAndUnicodeEntries() throws Exception {
        String name = "中文😀".repeat(8);
        Files.createFile(directory.resolve("a" + name));
        Files.createFile(directory.resolve("b" + name));
        var one = success(execute("{\"maxEntries\":1}"));
        int limit = one.toString().codePointCount(0, one.toString().length()) + 15;
        var result = execute("{}", limit);
        var body = success(result);
        assertEquals(List.of("a" + name), paths(body));
        assertEquals("outputLimit", body.path("limitReason").asText());
        assertTrue(body.path("truncated").asBoolean());
        assertTrue(result.output().codePointCount(0, result.output().length()) <= limit);
        assertFalse(result.truncated()); // 外层没有截断，截断信息属于工具 JSON 内部。
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, execute("{}", 20).errorCode());
        Files.createDirectory(directory.resolve("empty"));
        String emptyRequest = "{\"path\":\"empty\"}";
        var empty = success(execute(emptyRequest));
        int metadataOnlyBudget = empty.toString().length() + 30;
        assertTrue(success(execute(emptyRequest, metadataOnlyBudget)).path("entries").isEmpty());
        Files.createFile(directory.resolve("empty").resolve(name));
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, execute(emptyRequest, metadataOnlyBudget).errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"path\":null}", "{\"path\":1}", "{\"path\":\" \"}",
            "{\"maxDepth\":0}", "{\"maxDepth\":21}", "{\"maxDepth\":1.0}", "{\"maxDepth\":\"2\"}",
            "{\"maxDepth\":null}", "{\"maxDepth\":4294967297}", "{\"maxEntries\":0}",
            "{\"maxEntries\":1001}", "{\"maxEntries\":null}", "{\"maxEntries\":true}",
            "{\"workspace\":\"other\"}", "{\"recursive\":true}"})
    void rejectsInvalidArgumentsWithoutFilesystemTraversal(String arguments) throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(arguments).errorCode());
    }

    @Test
    void rejectsAbsoluteAndOutsidePathsAndClassifiesMissingPaths() throws Exception {
        var absolute = MAPPER.createObjectNode().put("path", directory.toString()).toString();
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, execute(absolute).errorCode());
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, execute("{\"path\":\"..\"}").errorCode());
        assertEquals(ToolResult.ErrorCode.DIRECTORY_NOT_FOUND, execute("{\"path\":\"missing\"}").errorCode());
        Files.writeString(directory.resolve("file.txt"), "content");
        assertEquals(ToolResult.ErrorCode.NOT_A_DIRECTORY, execute("{\"path\":\"file.txt\"}").errorCode());
        var lost = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.resolve("lost").toString());
        assertEquals(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE,
                new ToolExecutor(registry()).execute(call("{}"), new ToolContext(lost)).errorCode());
    }

    @Test
    void linksAreListedWithoutFollowingOutsideBrokenOrCyclicTargets() throws Exception {
        Path root = Files.createDirectory(directory.resolve("project"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Files.writeString(outside.resolve("do-not-list.txt"), "content");
        link(root.resolve("outside-link"), outside);
        link(root.resolve("loop"), root);
        link(root.resolve("broken"), outside.resolve("missing"));
        var ctx = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, root.toRealPath().toString()));
        var executor = new ToolExecutor(registry());
        var body = success(executor.execute(call("{\"maxDepth\":8}"), ctx));
        assertEquals(List.of("broken", "loop", "outside-link"), paths(body));
        for (JsonNode entry : body.path("entries")) assertEquals("symlink", entry.path("type").asText());
        assertFalse(body.path("truncated").asBoolean());
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE,
                executor.execute(call("{\"path\":\"outside-link\"}"), ctx).errorCode());
    }

    @Test
    void scanCacheIsBoundedEvenBeforeOutputEntryLimitApplies() throws Exception {
        for (int i = 0; i <= ListFilesTool.MAX_SCANNED_ENTRIES; i++) Files.createFile(directory.resolve("f" + i));
        var body = success(execute("{\"maxEntries\":1}"));
        assertEquals(ListFilesTool.MAX_SCANNED_ENTRIES, body.path("scannedEntries").asInt());
        assertEquals(1, body.path("returnedEntries").asInt());
        assertTrue(body.path("truncated").asBoolean());
    }

    @Test
    void interruptionStopsTraversalAndPreservesInterruptFlag() throws Exception {
        try {
            Thread.currentThread().interrupt();
            var result = execute("{}");
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, result.errorCode());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private ToolRegistry registry() { return new ToolRegistry().register(new ListFilesTool()); }
    private ToolCall call(String json) { return new ToolCall("list-call", "list_files", json); }
    private ToolContext context(int limit) throws IOException {
        return new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString()), limit);
    }
    private ToolResult execute(String json) throws IOException { return execute(json, 16000); }
    private ToolResult execute(String json, int limit) throws IOException { return new ToolExecutor(registry()).execute(call(json), context(limit)); }
    private JsonNode success(ToolResult result) throws Exception {
        assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output());
        assertFalse(result.truncated());
        return MAPPER.readTree(result.output());
    }
    private List<String> paths(JsonNode body) {
        List<String> paths = new ArrayList<>();
        body.path("entries").forEach(entry -> paths.add(entry.path("path").asText()));
        return paths;
    }
    private void link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException denied) { assumeTrue(false, "Symlink creation unavailable"); }
    }
}
