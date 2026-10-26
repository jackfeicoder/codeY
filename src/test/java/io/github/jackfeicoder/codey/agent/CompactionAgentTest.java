package io.github.jackfeicoder.codey.agent;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.context.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 摘要请求不占业务轮次；候选失败、保存失败和超限均保持完整任务提交边界。 */
class CompactionAgentTest {
    private final ContextBuilder builder = new ContextBuilder();
    private final ContextBudgetPolicy budget = new ContextBudgetPolicy(new ModelCapabilities(12000L),
            new ContextBudgetSettings(1024, 1024, new BigDecimal("0.8")));
    private final CompactionSettings settings = new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.4"), 3, 512, 8, 5);
    private final ToolContext context = new ToolContext(WorkspaceBinding.none());
    private Session history(int length) {
        var session = new Session("base");
        for (int i = 0; i < 4; i++) session.completeTurn("old" + i, Message.assistant("x".repeat(length)));
        for (int i = 0; i < 3; i++) session.completeTurn("recent" + i, Message.assistant("done"));
        return session;
    }
    private LlmResponse answer(String text) { return new LlmResponse(Message.assistant(text), "stop", null); }
    private LlmResponse summary() { return answer(new SummaryDocument(List.of("目标"), List.of("保留约束"), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of("继续")).json()); }
    private AgentLoop loop(LlmClient business, LlmClient summaries, Runnable save) {
        return new AgentLoop(business, new ToolRegistry(), 2, DebugPrinter.NONE, builder, snapshot -> { }, budget,
                new HistoryCompactor(summaries, budget, settings, builder, DebugPrinter.NONE), save);
    }

    @Test void persistsSummaryBeforeBusinessAndDoesNotConsumeBusinessStepsOrCommitPartialTurn() {
        var session = history(6000); var original = session.messages();
        var saves = new AtomicInteger(); var calls = new AtomicInteger(); var summaryCalls = new AtomicInteger();
        var result = loop(request -> {
            assertEquals(1, saves.get()); assertNotNull(session.compaction());
            assertNull(request.maxOutputTokens());
            if (calls.incrementAndGet() == 1) return new LlmResponse(Message.assistantToolCalls(
                    List.of(new ToolCall("unknown", "missing_tool", "{}"))), "tool_calls", null);
            assertEquals(MessageRole.TOOL, request.messages().get(request.messages().size() - 1).role());
            return answer("finished");
        }, request -> { summaryCalls.incrementAndGet(); return summary(); }, saves::incrementAndGet)
                .run(session, "continue", context);
        assertTrue(result.completed()); assertEquals(2, result.modelCalls()); assertEquals(1, result.toolCalls());
        assertTrue(summaryCalls.get() > 0); assertEquals(original, session.messages());
        assertEquals(4, result.turnMessages().size()); assertEquals(Message.user("continue"), result.turnMessages().get(0));
        session.completeAgentTurn(result.turnMessages());
        assertEquals(original.size() + 4, session.messages().size());
    }

    @Test void failedSummaryBelowHardLimitAllowsBusinessAndIsNotRepeatedInThisTask() {
        var session = history(6000); var summaryCalls = new AtomicInteger(); var businessCalls = new AtomicInteger();
        var result = loop(request -> {
            if (businessCalls.incrementAndGet() == 1) return new LlmResponse(Message.assistantToolCalls(
                    List.of(new ToolCall("unknown", "missing_tool", "{}"))), "tool_calls", null);
            return answer("done");
        }, request -> { summaryCalls.incrementAndGet(); return answer("invalid-json"); }, () -> fail("No summary to save"))
                .run(session, "continue", context);
        assertTrue(result.completed()); assertEquals(1, summaryCalls.get()); assertEquals(2, businessCalls.get());
        assertNull(session.compaction());
    }

    @Test void failedSummaryAboveHardLimitBlocksBusinessWithAccurateCounts() {
        var session = history(12000); var original = session.messages();
        var error = assertThrows(ContextLimitException.class, () -> loop(request -> { fail("No business call"); return answer("done"); },
                request -> answer("invalid-json"), () -> fail("No summary to save")).run(session, "continue", context));
        assertEquals(0, error.modelCalls()); assertEquals(0, error.toolCalls());
        assertEquals(original, session.messages()); assertNull(session.compaction());
    }

    @Test void saveFailureStopsBusinessWhileRetainingRawHistoryAndValidatedDerivedState() {
        var session = history(6000); var original = session.messages();
        assertThrows(SessionException.class, () -> loop(request -> { fail("No business call"); return answer("done"); },
                request -> summary(), () -> { throw new SessionException("fixture save failure"); })
                .run(session, "continue", context));
        assertEquals(original, session.messages()); assertNotNull(session.compaction());
    }
}
