package io.github.jackfeicoder.codey.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实文件的审批在副作用之前生效、会话授权、独立自动审查与 PLAN 硬约束。 */
class PermissionExecutionTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper();
    private ToolRegistry registry() {
        return new ToolRegistry().register(new ReadFileTool()).register(new ListFilesTool()).register(new SearchFileTool())
                .register(new GrepCodeTool()).register(new WriteFileTool()).register(new EditFileTool());
    }
    private WorkspaceBinding workspace() throws Exception {
        return new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString());
    }
    private ToolContext context(PermissionMode mode, WorkMode work, ApprovalHandler handler) throws Exception {
        var workspace = workspace();
        return new ToolContext(workspace, 16000, work, new PermissionService(mode, workspace, handler));
    }
    private ToolResult execute(ToolContext context, String tool, Object... pairs) {
        var args = JSON.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) args.set((String)pairs[i], JSON.valueToTree(pairs[i+1]));
        return new ToolExecutor(registry()).execute(new ToolCall("fixture-call", tool, args.toString()), context);
    }
    private void success(ToolResult result) { assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output()); }

    @Test void defaultContextIsReactAskAndNeverImplicitlyApproves() throws Exception {
        var context = new ToolContext(workspace());
        assertEquals(WorkMode.REACT, context.workMode());
        assertEquals(PermissionMode.ASK, context.permissions().mode());
        assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED, execute(context, "write_file", "path", "a", "content", "x").errorCode());
        assertFalse(Files.exists(directory.resolve("a")));
    }
    @Test void askPromptsOncePerFileAndSharesGrantAcrossWriteAndEdit() throws Exception {
        var seen = new ArrayList<ApprovalRequest>();
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> {
            assertFalse(Files.exists(request.target()));
            seen.add(request); return ApprovalHandler.Answer.APPROVE;
        });
        success(execute(context, "write_file", "path", "a.txt", "content", "old"));
        success(execute(context, "edit_file", "path", "./a.txt", "oldText", "old", "newText", "new"));
        success(execute(context, "write_file", "path", "a.txt", "content", "again", "overwrite", true));
        success(execute(context, "write_file", "path", "b.txt", "content", "b"));
        assertEquals(2, seen.size());
        assertEquals(2, context.permissions().grantCount());
        assertEquals(List.of("a.txt", "b.txt"), context.permissions().grantedFiles());
        assertEquals("again", Files.readString(directory.resolve("a.txt")));
    }
    @Test void freshSessionDoesNotInheritApprovalAndRevokeForgetsGrant() throws Exception {
        var calls = new AtomicInteger();
        ApprovalHandler handler = request -> { calls.incrementAndGet(); return ApprovalHandler.Answer.APPROVE; };
        var first = context(PermissionMode.ASK, WorkMode.REACT, handler);
        success(execute(first, "write_file", "path", "a", "content", "x"));
        success(execute(context(PermissionMode.ASK, WorkMode.REACT, handler), "edit_file", "path", "a", "oldText", "x", "newText", "y"));
        first.permissions().revoke();
        success(execute(first, "edit_file", "path", "a", "oldText", "y", "newText", "z"));
        assertEquals(3, calls.get());
    }
    @Test void denialBlocksSameTargetThroughDifferentWriteToolUntilUserRevokes() throws Exception {
        Files.writeString(directory.resolve("a"), "old");
        var asks = new AtomicInteger();
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> { asks.incrementAndGet(); return ApprovalHandler.Answer.DENY; });
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(context, "edit_file", "path", "a", "oldText", "old", "newText", "new").errorCode());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(context, "write_file", "path", "./a", "content", "x", "overwrite", true).errorCode());
        assertEquals(1, asks.get());
        assertEquals("old", Files.readString(directory.resolve("a")));
        context.permissions().revoke();
        execute(context, "edit_file", "path", "a", "oldText", "old", "newText", "new");
        assertEquals(2, asks.get());
    }
    @Test void directWriteExecuteAlsoChecksPermission() throws Exception {
        var context = new ToolContext(workspace());
        var error = assertThrows(ToolException.class, () -> new WriteFileTool().execute(JSON.readTree("{\"path\":\"a\",\"content\":\"x\"}"), context));
        assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED, error.code());
        assertFalse(Files.exists(directory.resolve("a")));
    }
    @ParameterizedTest @EnumSource(PermissionMode.class)
    void planRejectsForcedMutationEvenInFullModeAndHidesDefinitions(PermissionMode mode) throws Exception {
        var context = context(mode, WorkMode.PLAN, request -> { fail("PLAN must reject without asking"); return ApprovalHandler.Answer.APPROVE; });
        assertEquals(List.of("read_file", "list_files", "search_file", "grep_code"), registry().definitions(context).stream().map(ToolDefinition::name).toList());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(context, "write_file", "path", "a", "content", "x").errorCode());
        assertFalse(Files.exists(directory.resolve("a")));
    }
    @Test void trustedReadToolsDoNotPrompt() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "hello");
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> { fail("Read tools must not ask"); return ApprovalHandler.Answer.DENY; });
        success(execute(context, "read_file", "path", "a.txt"));
        success(execute(context, "list_files"));
        success(execute(context, "search_file", "pattern", "*.txt"));
        success(execute(context, "grep_code", "query", "hello"));
    }
    @Test void invalidOrNoopChangesDoNotPrompt() throws Exception {
        Files.writeString(directory.resolve("a"), "same");
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> { fail("Invalid/noop request must not ask"); return ApprovalHandler.Answer.DENY; });
        assertEquals(ToolResult.ErrorCode.TEXT_NOT_FOUND, execute(context, "edit_file", "path", "a", "oldText", "missing", "newText", "x").errorCode());
        assertEquals(ToolResult.ErrorCode.FILE_ALREADY_EXISTS, execute(context, "write_file", "path", "a", "content", "x").errorCode());
        success(execute(context, "write_file", "path", "a", "content", "same", "overwrite", true));
        assertEquals(0, context.permissions().grantCount());
    }
    @Test void changedFileWhileWaitingForApprovalIsNotOverwrittenOrGranted() throws Exception {
        Path file = directory.resolve("a"); Files.writeString(file, "old");
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> {
            try { Files.writeString(file, "external"); } catch (Exception error) { throw new AssertionError(error); }
            return ApprovalHandler.Answer.APPROVE;
        });
        assertEquals(ToolResult.ErrorCode.FILE_CONFLICT, execute(context, "edit_file", "path", "a", "oldText", "old", "newText", "new").errorCode());
        assertEquals("external", Files.readString(file));
        assertEquals(0, context.permissions().grantCount());
        try (var paths = Files.list(directory)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".codey-"))); }
    }
    @Test void preparedOperationKeepsOriginalArgumentsDespiteJsonMutation() throws Exception {
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> ApprovalHandler.Answer.APPROVE);
        var args = JSON.createObjectNode().put("path", "a").put("content", "approved");
        var prepared = new WriteFileTool().prepare(args, context);
        args.put("path", "b").put("content", "different");
        prepared.execute(context);
        assertEquals("approved", Files.readString(directory.resolve("a")));
        assertFalse(Files.exists(directory.resolve("b")));
    }
    @Test void previewIsBoundedAndKeepsWholeUnicodeCodePoints() throws Exception {
        Files.writeString(directory.resolve("emoji.txt"), "😀".repeat(600));
        var seen = new ArrayList<ApprovalRequest>();
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> { seen.add(request); return ApprovalHandler.Answer.APPROVE; });
        success(execute(context, "write_file", "path", "emoji.txt", "content", "😁".repeat(600), "overwrite", true));
        String preview = seen.get(0).preview();
        assertTrue(preview.contains("😀")); assertTrue(preview.contains("😁"));
        assertTrue(preview.contains("已省略")); assertTrue(preview.length() < 1800);
        var encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
        assertTrue(encoder.canEncode(preview));
    }
    @Test void autoApprovesOrdinaryCreateAndEditWithoutHuman() throws Exception {
        var context = context(PermissionMode.AUTO, WorkMode.REACT, request -> { fail("Ordinary file rule should approve"); return ApprovalHandler.Answer.DENY; });
        success(execute(context, "write_file", "path", "src/A.java", "content", "class A {}"));
        success(execute(context, "edit_file", "path", "src/A.java", "oldText", "A", "newText", "B"));
    }
    @Test void autoRechecksRiskAfterOrdinaryChangesInsteadOfReusingFileGrant() throws Exception {
        var requests = new ArrayList<ApprovalRequest>();
        var context = context(PermissionMode.AUTO, WorkMode.REACT, request -> { requests.add(request); return ApprovalHandler.Answer.DENY; });
        success(execute(context, "write_file", "path", "a", "content", "keep"));
        assertEquals(0, context.permissions().grantCount());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,
                execute(context, "write_file", "path", "a", "content", "", "overwrite", true).errorCode());
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).fileGrant());
        assertEquals("keep", Files.readString(directory.resolve("a")));
    }
    @Test void autoSensitiveAndDestructiveRequestsGoToHumanAndHideSecrets() throws Exception {
        var seen = new ArrayList<ApprovalRequest>();
        var context = context(PermissionMode.AUTO, WorkMode.REACT, request -> { seen.add(request); return ApprovalHandler.Answer.DENY; });
        Files.writeString(directory.resolve(".env"), "KEY=fixture-secret");
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(context, "write_file", "path", ".env", "content", "KEY=new-secret", "overwrite", true).errorCode());
        assertTrue(seen.get(0).sensitive());
        assertFalse(seen.get(0).preview().contains("secret"));
        Files.writeString(directory.resolve("a"), "keep");
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(context, "write_file", "path", "a", "content", "", "overwrite", true).errorCode());
        assertTrue(seen.get(1).destructive());
    }
    @Test void reviewerFailureFallsBackToHumanAndUnavailableIsNotApproval() throws Exception {
        var asks = new AtomicInteger();
        var workspace = workspace();
        var service = new PermissionService(PermissionMode.AUTO, workspace, request -> {
            asks.incrementAndGet(); return ApprovalHandler.Answer.UNAVAILABLE;
        }, request -> { throw new IllegalStateException("fixture-secret"); });
        var context = new ToolContext(workspace, 16000, WorkMode.REACT, service);
        var result = execute(context, "write_file", "path", "a", "content", "x");
        assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED, result.errorCode());
        assertFalse(result.output().contains("fixture-secret"));
        assertEquals(1, asks.get());
        assertFalse(Files.exists(directory.resolve("a")));
    }
    @Test void fullSkipsReviewButDoesNotRemoveFilesystemOrArgumentChecks() throws Exception {
        var context = context(PermissionMode.FULL, WorkMode.REACT, request -> { fail("Full should not ask"); return ApprovalHandler.Answer.DENY; });
        success(execute(context, "write_file", "path", "a", "content", "x"));
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, execute(context, "write_file", "path", "../outside", "content", "x").errorCode());
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(context, "edit_file", "path", "a", "oldText", "", "newText", "x").errorCode());
    }
    @Test void unknownRegisteredToolRequiresApprovalAndIsNotTreatedAsReadOnly() throws Exception {
        var executed = new AtomicInteger();
        Tool tool = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("external", "fixture", JSON.createObjectNode().put("type", "object")); }
            public void validateArguments(com.fasterxml.jackson.databind.JsonNode args, ToolContext context) { }
            public String execute(com.fasterxml.jackson.databind.JsonNode args, ToolContext context) { executed.incrementAndGet(); return "done"; }
        };
        var registry = new ToolRegistry().register(tool);
        var context = new ToolContext(workspace());
        var result = new ToolExecutor(registry).execute(new ToolCall("id", "external", "{}"), context);
        assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED, result.errorCode());
        assertEquals(0, executed.get());
    }
    @Test void serviceCannotBeReusedAcrossWorkspaces() throws Exception {
        var service = new PermissionService(PermissionMode.FULL, workspace(), ApprovalHandler.unavailable());
        var other = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, Files.createDirectory(directory.resolve("other")).toRealPath().toString());
        assertThrows(ToolException.class, () -> new ToolContext(other, 16000, WorkMode.REACT, service));
    }
    @Test void interruptedApprovalPreventsWritingAndLeavesNoGrant() throws Exception {
        var context = context(PermissionMode.ASK, WorkMode.REACT, request -> { throw new InterruptedException(); });
        try {
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, execute(context, "write_file", "path", "a", "content", "x").errorCode());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(directory.resolve("a")));
            assertEquals(0, context.permissions().grantCount());
        } finally { Thread.interrupted(); }
    }
}
