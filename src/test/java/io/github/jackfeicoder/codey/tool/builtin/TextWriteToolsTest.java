package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceTextFiles;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 在临时工作区验证真实写入、原文保留、失败不修改和提交冲突，不调用真实模型。 */
class TextWriteToolsTest {
    @TempDir Path directory;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void definitionsExposeActualFieldsAndHideBothToolsWithoutWorkspace() throws Exception {
        var write = new WriteFileTool().definition();
        var edit = new EditFileTool().definition();
        assertEquals("write_file", write.name());
        assertEquals("edit_file", edit.name());
        assertFalse(write.parameters().at("/properties/overwrite/default").asBoolean());
        assertFalse(edit.parameters().at("/properties/replaceAll/default").asBoolean());
        assertFalse(write.parameters().path("additionalProperties").asBoolean());
        assertEquals(3, edit.parameters().path("required").size());
        assertTrue(registry().definitions(new ToolContext(WorkspaceBinding.none())).isEmpty());
        assertEquals(2, registry().definitions(context()).size());
    }

    @Test
    void createsParentsAndUtf8FileAndReturnsOnlyMetadata() throws Exception {
        var body = success(write("src/中文/Note.md", "# 你好😀\n", false));
        assertEquals("# 你好😀\n", Files.readString(directory.resolve("src/中文/Note.md")));
        assertEquals("src/中文/Note.md", body.path("path").asText());
        assertEquals("create", body.path("operation").asText());
        assertTrue(body.path("changed").asBoolean());
        assertEquals(Files.size(directory.resolve("src/中文/Note.md")), body.path("bytesWritten").asLong());
        assertEquals(64, body.path("sha256").asText().length());
        assertFalse(body.toString().contains("你好"));
        assertNoTemporaryFiles();
    }

    @Test
    void allowsEmptyFileAndExplicitFullReplacementButNeverAppends() throws Exception {
        success(write("file.txt", "", false));
        assertEquals(0, Files.size(directory.resolve("file.txt")));
        Files.writeString(directory.resolve("file.txt"), "old tail");
        var result = write("file.txt", "new", true);
        assertEquals("overwrite", success(result).path("operation").asText());
        assertEquals("new", Files.readString(directory.resolve("file.txt")));
        assertEquals("call-id", result.toMessage().toolCallId());
        assertNoTemporaryFiles();
    }

    @Test
    void defaultsOverwriteToFalseAndPreservesExistingContents() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "keep");
        var result = execute("write_file", "{\"path\":\"file.txt\",\"content\":\"replace\"}");
        assertEquals(ToolResult.ErrorCode.FILE_ALREADY_EXISTS, result.errorCode());
        assertEquals("keep", Files.readString(directory.resolve("file.txt")));
        assertNoTemporaryFiles();
    }

    @Test
    void identicalFullWriteIsReportedAsUnchangedWithoutRewritingFile() throws Exception {
        Path file = directory.resolve("file.txt");
        Files.writeString(file, "same");
        FileTime time = FileTime.fromMillis(1000000);
        Files.setLastModifiedTime(file, time);
        var body = success(write("file.txt", "same", true));
        assertFalse(body.path("changed").asBoolean());
        assertEquals(0, body.path("bytesWritten").asInt());
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    @Test
    void editsOnlyRequestedFragmentAndPreservesBomWhitespaceAndCrlf() throws Exception {
        String original = "\uFEFFclass A {\r\n  int count = 8;  \r\n}\r\n";
        Files.writeString(directory.resolve("A.java"), original);
        var body = success(edit("A.java", "int count = 8;", "int count = 20;", false));
        assertEquals(original.replace("int count = 8;", "int count = 20;"), Files.readString(directory.resolve("A.java")));
        assertEquals(1, body.path("replacements").asInt());
        assertEquals("edit", body.path("operation").asText());
        assertNoTemporaryFiles();
    }

    @Test
    void insertsAtAnchorAndDeletesWithEmptyNewText() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "header\nfooter\n");
        success(edit("a.txt", "header\n", "header\n新增😀\n", false));
        assertEquals("header\n新增😀\nfooter\n", Files.readString(directory.resolve("a.txt")));
        success(edit("a.txt", "新增😀\n", "", false));
        assertEquals("header\nfooter\n", Files.readString(directory.resolve("a.txt")));
    }

    @Test
    void ambiguousOrMissingMatchesDoNotTouchFile() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "login login\nLOGIN\n");
        assertEquals(ToolResult.ErrorCode.AMBIGUOUS_MATCH, edit("a.txt", "login", "auth", false).errorCode());
        assertEquals(ToolResult.ErrorCode.TEXT_NOT_FOUND, edit("a.txt", "Login", "auth", false).errorCode());
        assertEquals("login login\nLOGIN\n", Files.readString(directory.resolve("a.txt")));
        assertNoTemporaryFiles();
    }

    @Test
    void replacesAllOnlyWhenExplicitAndTreatsPatternsAsLiteral() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "[x] [x]\r\n[X]\r\n");
        var body = success(edit("a.txt", "[x]", "$1\\value", true));
        assertEquals(2, body.path("replacements").asInt());
        assertEquals("$1\\value $1\\value\r\n[X]\r\n", Files.readString(directory.resolve("a.txt")));
    }

    @Test
    void matchesMultipleLinesExactlyAndCountsNonoverlappingOccurrences() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "aaa\r\nnext");
        assertEquals(ToolResult.ErrorCode.TEXT_NOT_FOUND, edit("a.txt", "aaa\nnext", "x", false).errorCode());
        success(edit("a.txt", "aaa\r\nnext", "aaaaa", false));
        assertEquals(2, success(edit("a.txt", "aa", "b", true)).path("replacements").asInt());
        assertEquals("bba", Files.readString(directory.resolve("a.txt")));
    }

    @Test
    void editRequiresExistingFileAndDoesNotCreateParents() throws Exception {
        assertEquals(ToolResult.ErrorCode.FILE_NOT_FOUND, edit("missing/a.txt", "old", "new", false).errorCode());
        assertFalse(Files.exists(directory.resolve("missing")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "{\"path\":\"a\"}", "{\"path\":\"\",\"content\":\"x\"}",
            "{\"path\":\"a\",\"content\":null}", "{\"path\":\"a\",\"content\":1}",
            "{\"path\":\"a\",\"content\":\"x\",\"overwrite\":\"true\"}",
            "{\"path\":\"a\",\"content\":\"x\",\"overwrite\":null}",
            "{\"path\":\"a\",\"content\":\"x\",\"workspace\":\"x\"}",
            "{\"path\":\"a\",\"content\":\"x\",\"path\":\"b\"}",
            "{\"path\":\"a\",\"content\":\"x\"} {}"
    })
    void rejectsInvalidWriteArgumentsWithoutSideEffects(String json) throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute("write_file", json).errorCode());
        assertFalse(Files.exists(directory.resolve("a")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"path\":\"a\",\"oldText\":\"a\"}",
            "{\"path\":\"a\",\"oldText\":\"\",\"newText\":\"x\"}",
            "{\"path\":\"a\",\"oldText\":\"x\",\"newText\":\"x\"}",
            "{\"path\":\"a\",\"oldText\":null,\"newText\":\"x\"}",
            "{\"path\":\"a\",\"oldText\":\"a\",\"newText\":null}",
            "{\"path\":\"a\",\"oldText\":\"a\",\"newText\":\"b\",\"replaceAll\":1}",
            "{\"path\":\"a\",\"oldText\":\"a\",\"newText\":\"b\",\"replaceAll\":null}",
            "{\"path\":\"a\",\"oldText\":\"a\",\"newText\":\"b\",\"regex\":true}"
    })
    void rejectsInvalidEditArgumentsWithoutChangingFile(String json) throws Exception {
        Files.writeString(directory.resolve("a"), "a");
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute("edit_file", json).errorCode());
        assertEquals("a", Files.readString(directory.resolve("a")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "../../outside.txt"})
    void rejectsLexicalEscapeForBothTools(String path) throws Exception {
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, write(path, "x", true).errorCode());
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, edit(path, "x", "y", false).errorCode());
    }

    @Test
    void rejectsAbsoluteAndAlternateStreamPaths() throws Exception {
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, write("file.txt:stream", "x", false).errorCode());
        assertTrue(List.of(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, ToolResult.ErrorCode.INVALID_ARGUMENTS)
                .contains(write(directory.resolve("a.txt").toString(), "x", false).errorCode()));
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, write("a\nb.txt", "x", false).errorCode());
    }

    @Test
    void rejectsDirectoriesAndFileAsParent() throws Exception {
        Files.createDirectory(directory.resolve("folder"));
        Files.writeString(directory.resolve("a"), "x");
        assertEquals(ToolResult.ErrorCode.NOT_A_FILE, write("folder", "x", true).errorCode());
        assertEquals(ToolResult.ErrorCode.NOT_A_FILE, edit("folder", "x", "y", false).errorCode());
        assertEquals(ToolResult.ErrorCode.NOT_A_DIRECTORY, write("a/b", "x", false).errorCode());
    }

    @Test
    void rejectsLinkTargetsAndLinkedParentsIncludingInternalLinks() throws Exception {
        Path actual = Files.createDirectory(directory.resolve("actual"));
        Files.writeString(actual.resolve("a.txt"), "keep");
        link(directory.resolve("alias"), actual);
        link(directory.resolve("alias.txt"), actual.resolve("a.txt"));
        for (String path : List.of("alias/new.txt", "alias/a.txt", "alias.txt")) {
            assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, write(path, "x", true).errorCode());
            assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, edit(path, "keep", "new", false).errorCode());
        }
        assertEquals("keep", Files.readString(actual.resolve("a.txt")));
        assertFalse(Files.exists(actual.resolve("new.txt")));
    }

    @Test
    void rejectsExternalAndDanglingLinks() throws Exception {
        Path outside = Files.createTempDirectory(directory.getParent(), "codey-write-outside-");
        try {
            Files.writeString(outside.resolve("keep.txt"), "keep");
            link(directory.resolve("outside"), outside);
            link(directory.resolve("dangling"), directory.resolve("absent"));
            assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, write("outside/keep.txt", "x", true).errorCode());
            assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, write("dangling", "x", true).errorCode());
            assertEquals("keep", Files.readString(outside.resolve("keep.txt")));
        } finally {
            Files.deleteIfExists(directory.resolve("outside"));
            Files.deleteIfExists(outside.resolve("keep.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsInvalidUtf8AndBinaryExistingFilesWithoutChangingBytes() throws Exception {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xC3, 0x28}, new byte[]{'a', 0, 'b'})) {
            Path file = directory.resolve("a.bin");
            Files.write(file, bytes);
            var expected = bytes[0] == 'a' ? ToolResult.ErrorCode.NOT_TEXT_FILE : ToolResult.ErrorCode.INVALID_ENCODING;
            assertEquals(expected, write("a.bin", "text", true).errorCode());
            assertEquals(expected, edit("a.bin", "a", "x", false).errorCode());
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

    @Test
    void rejectsNulAndMalformedUnicodeInputs() throws Exception {
        assertEquals(ToolResult.ErrorCode.NOT_TEXT_FILE, write("a", "a\0b", false).errorCode());
        assertEquals(ToolResult.ErrorCode.INVALID_ENCODING, write("a", "bad\uD800", false).errorCode());
        assertEquals(ToolResult.ErrorCode.INVALID_ENCODING, edit("a", "x", "\uDC00", false).errorCode());
        assertFalse(Files.exists(directory.resolve("a")));
    }

    @Test
    void enforcesInputUtf8ByteBudgetAndExecutorJsonBudgetSeparately() throws Exception {
        assertEquals(ToolResult.ErrorCode.FILE_TOO_LARGE, write("a", "中".repeat(22000), false).errorCode());
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, write("a", "x".repeat(65536), false).errorCode());
        assertFalse(Files.exists(directory.resolve("a")));
    }

    @Test
    void refusesLargeOriginalAndExpandedEditBeforeWriting() throws Exception {
        Path file = directory.resolve("a.txt");
        Files.writeString(file, "a".repeat(WorkspaceTextFiles.MAX_FILE_BYTES + 1));
        assertEquals(ToolResult.ErrorCode.FILE_TOO_LARGE, write("a.txt", "small", true).errorCode());
        assertEquals(ToolResult.ErrorCode.FILE_TOO_LARGE, edit("a.txt", "a", "b", true).errorCode());
        Files.writeString(file, "a".repeat(100000));
        assertEquals(ToolResult.ErrorCode.FILE_TOO_LARGE, edit("a.txt", "a", "x".repeat(1000), true).errorCode());
        assertEquals(100000, Files.size(file));
        assertNoTemporaryFiles();
    }

    @Test
    void checksOutputBudgetBeforeCreationOrEdit() throws Exception {
        var tiny = new ToolContext(context().workspace(), 16);
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                execute("write_file", "{\"path\":\"new/a\",\"content\":\"x\"}", tiny).errorCode());
        assertFalse(Files.exists(directory.resolve("new")));
        Files.writeString(directory.resolve("a"), "before");
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                execute("edit_file", "{\"path\":\"a\",\"oldText\":\"before\",\"newText\":\"after\"}", tiny).errorCode());
        assertEquals("before", Files.readString(directory.resolve("a")));
    }

    @Test
    void commitDetectsSameSizeContentChangeEvenWhenMtimeRestored() throws Exception {
        Files.writeString(directory.resolve("a"), "before");
        var target = WorkspaceTextFiles.target(context(), Path.of("a"));
        var original = WorkspaceTextFiles.snapshot(target);
        Files.writeString(directory.resolve("a"), "edited");
        Files.setLastModifiedTime(directory.resolve("a"), original.attributes().lastModifiedTime());
        assertEquals(ToolResult.ErrorCode.FILE_CONFLICT, assertThrows(ToolException.class,
                () -> WorkspaceTextFiles.commit(target, original, "ours".getBytes(StandardCharsets.UTF_8))).code());
        assertEquals("edited", Files.readString(directory.resolve("a")));
        assertNoTemporaryFiles();
    }

    @Test
    void commitDoesNotOverwriteConcurrentCreationOrRecreateDeletedFile() throws Exception {
        var target = WorkspaceTextFiles.target(context(), Path.of("a"));
        Files.writeString(directory.resolve("a"), "other");
        assertEquals(ToolResult.ErrorCode.FILE_CONFLICT, assertThrows(ToolException.class,
                () -> WorkspaceTextFiles.commit(target, null, new byte[]{'x'})).code());
        var original = WorkspaceTextFiles.snapshot(target);
        Files.delete(directory.resolve("a"));
        assertEquals(ToolResult.ErrorCode.FILE_CONFLICT, assertThrows(ToolException.class,
                () -> WorkspaceTextFiles.commit(target, original, new byte[]{'x'})).code());
        assertFalse(Files.exists(directory.resolve("a")));
        assertNoTemporaryFiles();
    }

    @Test
    void checksWorkspaceStateAndInterruptionForBothTools() throws Exception {
        for (String name : List.of("write_file", "edit_file")) {
            String json = name.equals("write_file") ? "{\"path\":\"a\",\"content\":\"x\"}"
                    : "{\"path\":\"a\",\"oldText\":\"x\",\"newText\":\"y\"}";
            assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED, execute(name, json, new ToolContext(WorkspaceBinding.none())).errorCode());
            var unavailable = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.resolve("absent").toString()));
            assertEquals(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, execute(name, json, unavailable).errorCode());
            try {
                Thread.currentThread().interrupt();
                assertEquals(ToolResult.ErrorCode.INTERRUPTED, execute(name, json).errorCode());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
        assertFalse(Files.exists(directory.resolve("a")));
    }

    private ToolResult write(String path, String text, boolean overwrite) throws Exception {
        return execute("write_file", MAPPER.createObjectNode().put("path", path).put("content", text).put("overwrite", overwrite).toString());
    }
    private ToolResult edit(String path, String oldText, String newText, boolean all) throws Exception {
        return execute("edit_file", MAPPER.createObjectNode().put("path", path).put("oldText", oldText).put("newText", newText).put("replaceAll", all).toString());
    }
    private ToolResult execute(String name, String json) throws Exception { return execute(name, json, context()); }
    private ToolResult execute(String name, String json, ToolContext context) {
        return new ToolExecutor(registry()).execute(new ToolCall("call-id", name, json), context);
    }
    private ToolRegistry registry() { return new ToolRegistry().register(new WriteFileTool()).register(new EditFileTool()); }
    private ToolContext context() throws Exception {
        var workspace = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString());
        return new ToolContext(workspace, ToolContext.DEFAULT_MAX_OUTPUT_CHARS, io.github.jackfeicoder.codey.agent.WorkMode.REACT,
                new io.github.jackfeicoder.codey.policy.PermissionService(io.github.jackfeicoder.codey.policy.PermissionMode.FULL,
                        workspace, io.github.jackfeicoder.codey.policy.ApprovalHandler.unavailable()));
    }
    private JsonNode success(ToolResult result) throws Exception {
        assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output());
        assertFalse(result.truncated());
        return MAPPER.readTree(result.output());
    }
    private void assertNoTemporaryFiles() throws Exception {
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith(".codey-")));
        }
    }
    private void link(Path path, Path target) throws Exception {
        try { Files.createSymbolicLink(path, target); }
        catch (java.io.IOException | UnsupportedOperationException denied) {
            assumeTrue(false, "Symbolic links are not available in this environment.");
        }
    }
}
