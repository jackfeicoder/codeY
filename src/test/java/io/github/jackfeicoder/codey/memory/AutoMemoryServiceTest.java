package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.tool.ToolCall;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 单次无工具提取、失败隔离、原文依据、作用域与幂等标记；不访问真实供应商。 */
class AutoMemoryServiceTest {
    @TempDir Path root;
    private final String sessionId = UUID.randomUUID().toString();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private MemoryService memory() { return new MemoryService(new JsonMemoryStore(root), new MemorySettings()); }
    private AutoMemoryService automatic() { return new AutoMemoryService(memory(), root, new MemoryExtractionSettings()); }
    private String json(String action, String scope, String content, String evidence, String related) {
        var mapper = new ObjectMapper(); var result = mapper.createObjectNode();
        var item = result.putArray("memories").addObject().put("action", action).put("scope", scope)
                .put("title", "偏好").put("content", content).put("evidence", evidence);
        item.putArray("tags").add("偏好");
        if (related == null) item.putNull("relatedId"); else item.put("relatedId", related);
        return result.toString();
    }
    private LlmClient client(String json) {
        return request -> { requests.add(request); return new LlmResponse(Message.assistant(json), "stop", null); };
    }
    private AutoMemoryService.Outcome run(AutoMemoryService auto, int turn, String input, LlmClient client) {
        return auto.afterSavedTurn(WorkspaceBinding.none(), sessionId, turn, input, client, ContextBudgetPolicy.unknown(), ignored -> { });
    }
    @Test void positiveIntentUsesNonStreamingNoToolsAndSavesOnlyOnDemandWithProvenance() {
        String input = "以后都用中文回答";
        var result = run(automatic(), 2, input, client(json("ADD", "USER", "默认用中文回答", input, null)));
        assertEquals(1, requests.size()); assertEquals(1, result.savedIds().size());
        var request = requests.get(0);
        assertTrue(request.tools().isEmpty()); assertFalse(request.toolCallsAllowed()); assertEquals(1024, request.maxOutputTokens());
        assertTrue(request.messages().get(0).content().contains("长期记忆提取"));
        var entry = memory().list(WorkspaceBinding.none()).get(0);
        assertEquals(MemoryLoading.ON_DEMAND, entry.loading()); assertEquals(sessionId, entry.sourceSessionId());
        assertEquals("默认用中文回答", entry.content());
    }
    @Test void noSignalAndDisabledDoNotCreateFilesOrCallModel() {
        assertFalse(run(automatic(), 2, "你好", client("{}")).attempted());
        var disabled = new AutoMemoryService(memory(), root, new MemoryExtractionSettings(false, 10, 4096, 1024));
        assertFalse(run(disabled, 2, "以后都用中文回答", client("{}")).attempted());
        assertTrue(requests.isEmpty()); assertFalse(Files.exists(root.resolve("memory")));
    }
    @Test void repeatedSourceIsNeverReplayedIncludingAfterDeletionAndServiceRestart() throws Exception {
        String input = "以后都用中文回答"; var response = client(json("ADD", "USER", "中文回答", input, null));
        var result = run(automatic(), 2, input, response); String id = result.savedIds().get(0);
        assertFalse(run(automatic(), 2, input, response).attempted()); assertEquals(1, requests.size());
        memory().forget(WorkspaceBinding.none(), id);
        assertFalse(run(automatic(), 2, input, response).attempted()); assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
        var fresh = run(automatic(), 4, input, response); assertEquals(1, fresh.savedIds().size());
        assertEquals(2, requests.size());
    }
    @Test void exactDuplicateIsSkippedAndExistingAlwaysIsNeverReplaced() {
        String input = "以后都用中文回答";
        var old = memory().remember(WorkspaceBinding.none(), MemoryScope.USER, MemoryLoading.ALWAYS, null, "中文回答", List.of(), null, null);
        var result = run(automatic(), 2, input, client(json("ADD", "USER", "中文回答", input, null)));
        assertTrue(result.savedIds().isEmpty()); assertEquals(List.of(old), memory().list(WorkspaceBinding.none()));
        assertTrue(result.detail().contains("重复跳过 1"));
    }
    @Test void correctionIsReviewEvenWhenModelIncorrectlyProposesAdd() {
        String input = "之前说错了，以后都简短回答";
        var old = memory().remember(WorkspaceBinding.none(), MemoryScope.USER, MemoryLoading.ALWAYS, null, "详细回答", List.of(), null, null);
        var result = run(automatic(), 2, input, client(json("ADD", "USER", "简短回答", input, null)));
        assertEquals(1, result.reviews().size()); assertTrue(result.savedIds().isEmpty());
        assertEquals(List.of(old), memory().list(WorkspaceBinding.none()));
    }
    @Test void explicitReviewReferencesOnlyProvidedMemoryAndDoesNotUpdateIt() {
        String input = "请记住我以后都用中文回答";
        var old = memory().remember(WorkspaceBinding.none(), MemoryScope.USER, MemoryLoading.ALWAYS, null, "英文回答", List.of(), null, null);
        var result = run(automatic(), 2, input, client(json("REVIEW", "USER", "中文回答", input, old.id())));
        assertEquals(1, result.reviews().size()); assertEquals(List.of(old), memory().list(WorkspaceBinding.none()));
    }
    @Test void fabricatedEvidenceScopeLeakUnknownIdAndCredentialsAreRejected() {
        String input = "以后都用中文回答";
        var cases = List.of(json("ADD", "USER", "new", "虚构依据", null),
                json("ADD", "PROJECT", "new", input, null), json("REVIEW", "USER", "new", input, UUID.randomUUID().toString()),
                json("ADD", "USER", "api_key=SECRET", input, null), "{\"memories\":[],\"unexpected\":true}");
        int turn = 2;
        for (String candidate : cases) {
            var result = run(automatic(), turn++, input, client(candidate));
            assertTrue(result.savedIds().isEmpty()); assertTrue(result.reviews().isEmpty());
        }
        assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
    }
    @Test void projectMemoryIsScopedToCurrentWorkspaceNotPromotedToGlobal() throws Exception {
        var project = Files.createDirectory(root.resolve("project"));
        var workspace = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, project.toString());
        String input = "请记住这个项目保留旧缓存以兼容客户端";
        var result = automatic().afterSavedTurn(workspace, sessionId, 2, input,
                client(json("ADD", "PROJECT", "保留旧缓存以兼容客户端", input, null)), ContextBudgetPolicy.unknown(), ignored -> { });
        assertEquals(1, result.savedIds().size()); assertEquals(1, memory().list(workspace).size());
        assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
        var bad = automatic().afterSavedTurn(workspace, sessionId, 4, input,
                client(json("ADD", "USER", "项目知识", input, null)), ContextBudgetPolicy.unknown(), ignored -> { });
        assertTrue(bad.savedIds().isEmpty()); assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
    }
    @Test void emptyDecisionFailureAndTruncatedResponseNeverWriteOrRetry() {
        String input = "请记住这个临时路径";
        assertTrue(run(automatic(), 2, input, client("{\"memories\":[]}")).savedIds().isEmpty());
        LlmClient failure = request -> { requests.add(request); throw new LlmException(LlmException.Kind.HTTP, "SECRET failure"); };
        var failed = run(automatic(), 4, input, failure);
        assertFalse(failed.detail().contains("SECRET")); assertTrue(failed.detail().contains("主任务已保存"));
        assertFalse(run(automatic(), 4, input, failure).attempted());
        LlmClient truncated = request -> new LlmResponse(Message.assistant(json("ADD", "USER", "new", input, null)), "length", null);
        assertTrue(run(automatic(), 6, input, truncated).savedIds().isEmpty());
        LlmClient tool = request -> new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("x", "write_file", "{}"))), "tool_calls", null);
        assertTrue(run(automatic(), 8, input, tool).savedIds().isEmpty());
        assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
    }
    @Test void outputAndModelInputLimitsPreventUnboundedRequests() {
        String input = "以后都用中文回答";
        var tiny = new ContextBudgetPolicy(new ModelCapabilities(128L, 1L, 1L), new ContextBudgetSettings());
        var result = automatic().afterSavedTurn(WorkspaceBinding.none(), sessionId, 2, input,
                client("{\"memories\":[]}"), tiny, ignored -> { });
        assertTrue(requests.isEmpty()); assertTrue(result.detail().contains("预算"));
    }
    @Test void timeoutCancelsOnlyExtractionAndIsNotRetried() throws Exception {
        var cancelled = new CountDownLatch(1);
        var shortTimeout = new AutoMemoryService(memory(), root, new MemoryExtractionSettings(true, 1, 4096, 1024));
        LlmClient slow = request -> {
            requests.add(request);
            try { Thread.sleep(30000); } catch (InterruptedException interrupted) { cancelled.countDown(); Thread.currentThread().interrupt(); }
            return new LlmResponse(Message.assistant("{\"memories\":[]}"), "stop", null);
        };
        var outcome = run(shortTimeout, 2, "以后都用中文回答", slow);
        assertTrue(outcome.detail().contains("超时")); assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertFalse(run(shortTimeout, 2, "以后都用中文回答", slow).attempted()); assertEquals(1, requests.size());
    }
    @Test void unspecifiedScopeDefaultsToProjectAndAmbiguousNoWorkspaceOnlyShowsReview() throws Exception {
        String input = "请记住保留旧缓存的兼容背景";
        var result = run(automatic(), 2, input, client(json("ADD", "USER", "保留旧缓存的兼容背景", input, null)));
        assertTrue(result.savedIds().isEmpty()); assertEquals(1, result.reviews().size());
        var dir = Files.createDirectory(root.resolve("p"));
        var workspace = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, dir.toString());
        var globalLeak = automatic().afterSavedTurn(workspace, sessionId, 4, input,
                client(json("ADD", "USER", "保留旧缓存的兼容背景", input, null)), ContextBudgetPolicy.unknown(), ignored -> { });
        assertTrue(globalLeak.savedIds().isEmpty());
        var scoped = automatic().afterSavedTurn(workspace, sessionId, 6, input,
                client(json("ADD", "PROJECT", "保留旧缓存的兼容背景", input, null)), ContextBudgetPolicy.unknown(), ignored -> { });
        assertEquals(1, scoped.savedIds().size()); assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
    }
    @Test void invalidLaterCandidateRejectsWholeGroupBeforeAnyWrite() throws Exception {
        String input = "以后都用中文回答";
        var mapper = new ObjectMapper(); var document = mapper.createObjectNode(); var array = document.putArray("memories");
        array.add(mapper.readTree(json("ADD", "USER", "中文回答", input, null)).path("memories").get(0));
        array.add(mapper.readTree(json("ADD", "USER", "虚构", "没有这段原文", null)).path("memories").get(0));
        var outcome = run(automatic(), 2, input, client(document.toString()));
        assertTrue(outcome.savedIds().isEmpty()); assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
    }
}
