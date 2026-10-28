package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 常驻指令和召回数据不同角色；原始历史、摘要、总预算与每轮调用保持一致。 */
class MemoryContextTest {
    @TempDir Path root;
    private MemoryService service() { return new MemoryService(new JsonMemoryStore(root), new MemorySettings()); }
    private MemoryEntry save(MemoryLoading mode, String text) {
        return service().remember(WorkspaceBinding.none(), MemoryScope.USER, mode, null, text, List.of(), null, null);
    }
    @Test void pinnedInSystemRecallInUserAndNeitherPersistsInRawMessages() {
        save(MemoryLoading.ALWAYS, "PINNED_LANGUAGE_RULE"); save(MemoryLoading.ON_DEMAND, "Java面试示例");
        var builder = new ContextBuilder(() -> "\nPROJECT_RULE", (query, used) -> service().select(WorkspaceBinding.none(), query, 3000));
        var history = List.of(Message.system("base")); var turn = List.of(Message.user("Java"));
        var snapshot = builder.build(history, turn, List.of(), WorkMode.REACT, 1, 20);
        assertTrue(snapshot.request().messages().get(0).content().contains("PINNED_LANGUAGE_RULE"));
        assertFalse(snapshot.request().messages().get(0).content().contains("Java面试示例"));
        assertTrue(snapshot.request().messages().get(1).content().contains("Java面试示例"));
        assertEquals("Java", turn.get(0).content()); assertEquals("base", history.get(0).content());
        assertTrue(snapshot.tokens().alwaysMemory() > 0); assertTrue(snapshot.tokens().recalledMemory() > 0);
        var plain = new ContextBuilder(() -> "\nPROJECT_RULE").build(history, turn, List.of(), WorkMode.REACT, 1, 20);
        assertEquals(plain.tokens().total() + snapshot.tokens().alwaysMemory() + snapshot.tokens().recalledMemory(), snapshot.tokens().total());
    }
    @Test void previewIncludesAlwaysButDoesNotRetrieveUsingOldUserHistory() {
        save(MemoryLoading.ALWAYS, "PINNED"); save(MemoryLoading.ON_DEMAND, "Java示例");
        var builder = new ContextBuilder(() -> "", (query, used) -> service().select(WorkspaceBinding.none(), query, 3000));
        var snapshot = builder.build(List.of(Message.system("base"), Message.user("Java"), Message.assistant("done")), List.of(), List.of(), WorkMode.REACT, 1, 20);
        assertTrue(snapshot.tokens().alwaysMemory() > 0); assertEquals(0, snapshot.tokens().recalledMemory());
    }
    @Test void hostRemainingBudgetDropsRecallAndPinnedStillParticipatesInHardLimit() {
        save(MemoryLoading.ALWAYS, "PINNED"); save(MemoryLoading.ON_DEMAND, "Java示例");
        var policy = new ContextBudgetPolicy(new ModelCapabilities(100L, 1L, 1L), new ContextBudgetSettings());
        var builder = new ContextBuilder(() -> "", (query, used) -> service().select(WorkspaceBinding.none(), query,
                Math.max(0, policy.evaluate(used).inputBudget() - used)));
        var snapshot = builder.build(List.of(Message.system("base")), List.of(Message.user("Java")), List.of(), WorkMode.REACT, 1, 20);
        assertEquals(0, snapshot.tokens().recalledMemory()); assertFalse(policy.evaluate(snapshot).canSend());
    }
    @Test void everyReactRoundUsesInitialGoalAndCompleteResultDoesNotSaveInjectedView() {
        save(MemoryLoading.ALWAYS, "PINNED"); save(MemoryLoading.ON_DEMAND, "Java示例");
        var queries = new ArrayList<String>(); var requests = new ArrayList<LlmRequest>();
        var builder = new ContextBuilder(() -> "", (query, used) -> { queries.add(query); return service().select(WorkspaceBinding.none(), query, 3000); });
        var client = (LlmClient) request -> {
            requests.add(request);
            if (requests.size() == 1) return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("call", "missing", "{}"))), "tool_calls", null);
            return new LlmResponse(Message.assistant("done"), "stop", null);
        };
        var session = new Session("base");
        var result = new AgentLoop(client, new ToolRegistry(), 20, io.github.jackfeicoder.codey.observability.DebugPrinter.NONE, builder, x -> { })
                .run(session, "Java", new ToolContext(WorkspaceBinding.none()));
        assertEquals(List.of("Java", "Java"), queries);
        assertTrue(requests.stream().allMatch(r -> r.messages().get(0).content().contains("PINNED")));
        assertTrue(requests.stream().allMatch(r -> r.messages().get(1).content().contains("Java示例")));
        session.completeAgentTurn(result.turnMessages());
        assertEquals("Java", session.messages().get(1).content());
        assertTrue(session.messages().stream().noneMatch(m -> m.content() != null && m.content().contains("PINNED")));
    }
    @Test void taskInstructionsRetainMemoryProviderAndSummaryRetainsBothMemoryClasses() {
        save(MemoryLoading.ALWAYS, "PINNED"); save(MemoryLoading.ON_DEMAND, "Java示例");
        var builder = new ContextBuilder(() -> "RULE", (q, used) -> service().select(WorkspaceBinding.none(), q, 3000)).withTaskInstructions("init");
        var raw = List.of(Message.user("old"), Message.assistant("done"), Message.user("recent"), Message.assistant("done"));
        var summary = new SummaryDocument(List.of("goal"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var state = new CompactionState(2, CompactionState.fingerprint(raw.subList(0, 2)), summary, java.time.Instant.now().toString());
        var history = new ArrayList<Message>(); history.add(Message.system("base")); history.addAll(raw);
        var snapshot = builder.build(history, List.of(Message.user("Java")), List.of(), WorkMode.PLAN, 1, 20, state);
        assertTrue(snapshot.tokens().summary() > 0); assertTrue(snapshot.tokens().alwaysMemory() > 0); assertTrue(snapshot.tokens().recalledMemory() > 0);
        assertEquals(5, history.size()); assertTrue(snapshot.request().messages().get(0).content().contains("PLAN"));
    }
}
