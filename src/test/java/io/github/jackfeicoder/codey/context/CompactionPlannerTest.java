package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.session.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 只用本地资料验证 Token 分流、任务/工具边界、大消息分片与 Reduce 预算，不调用厂商。 */
class CompactionPlannerTest {
    private CompactionSettings settings(int threshold, int chunk, int calls) {
        return new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.2"), 3, 0, calls, 5, threshold, chunk, 2);
    }
    private ContextBudgetPolicy budget(long window) {
        return new ContextBudgetPolicy(new ModelCapabilities(window, 128L, 128L), new ContextBudgetSettings());
    }
    private List<Message> turns(int count, String text) {
        var messages = new ArrayList<Message>();
        for (int i = 0; i < count; i++) { messages.add(Message.user("q" + i)); messages.add(Message.assistant(text + i)); }
        return messages;
    }
    @Test void routesBySourceTokensInsteadOfTurnCountAndAdaptsToSingleRequestCapacity() {
        var small = new CompactionPlanner(budget(100000), settings(64000, 64000, 24))
                .plan(turns(70, "small"), 0, null, 4096, "prompt", "reduce");
        assertEquals(CompactionPlan.Strategy.SINGLE, small.strategy()); assertEquals(1, small.expectedCalls());
        var large = new CompactionPlanner(budget(100000), settings(512, 1000, 24))
                .plan(turns(1, "x".repeat(10000)), 0, null, 4096, "prompt", "reduce");
        assertEquals(CompactionPlan.Strategy.MAP_REDUCE, large.strategy()); assertTrue(large.blocks().size() > 1);
        var narrow = budget(3200);
        var adapted = new CompactionPlanner(narrow, settings(64000, 64000, 24))
                .plan(turns(1, "x".repeat(10000)), 0, null, 700, "prompt", "reduce");
        assertEquals(CompactionPlan.Strategy.MAP_REDUCE, adapted.strategy());
        for (var block : adapted.blocks()) assertTrue(SummaryRequests.tokens(SummaryRequests.map("prompt", block, adapted.mapOutputLimit()))
                <= narrow.inputBudgetForOutput(adapted.mapOutputLimit()));
    }
    @Test void preservesWholeTaskAndToolGroupsWheneverTheyFitAndKeepsSourceOrder() {
        var messages = new ArrayList<Message>();
        for (int i = 0; i < 6; i++) messages.addAll(List.of(Message.user("q" + i), Message.assistantToolCalls(List.of(
                new io.github.jackfeicoder.codey.tool.ToolCall("c" + i, "read_file", "{}"))), Message.tool("c" + i, "x".repeat(600)), Message.assistant("done")));
        var plan = new CompactionPlanner(budget(100000), settings(512, 800, 24)).plan(messages, 20, null, 4096, "prompt", "reduce");
        assertEquals(CompactionPlan.Strategy.MAP_REDUCE, plan.strategy());
        int end = 20;
        for (var block : plan.blocks()) {
            assertEquals(end, block.firstMessage()); assertEquals(0, (block.endMessage() - 20) % 4);
            assertTrue(block.content().contains("toolCallId")); end = block.endMessage();
        }
        assertEquals(44, end); assertThrows(UnsupportedOperationException.class, () -> plan.blocks().clear());
    }
    @Test void oversizedToolGroupSplitsOnlySummaryDataAndPreservesUnicode() {
        var messages = List.of(Message.user("task"), Message.assistantToolCalls(List.of(
                new io.github.jackfeicoder.codey.tool.ToolCall("read", "read_file", "{}"))), Message.tool("read", "😀".repeat(1200)), Message.assistant("done"));
        var plan = new CompactionPlanner(budget(100000), settings(512, 1000, 24)).plan(messages, 0, null, 4096, "prompt", "reduce");
        assertTrue(plan.blocks().stream().anyMatch(b -> b.content().contains("连续资料片段")));
        for (var block : plan.blocks()) {
            assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(block.content()));
            var request = SummaryRequests.map("prompt", block, plan.mapOutputLimit());
            assertEquals(List.of(MessageRole.SYSTEM, MessageRole.USER), request.messages().stream().map(Message::role).toList());
            assertTrue(request.tools().isEmpty());
        }
        assertEquals("😀".repeat(1200), messages.get(2).content());
    }
    @Test void budgetsAllChildrenForOneReduceAndRejectsInsufficientCallOrMergeCapacity() {
        var cfg = settings(512, 600, 24); var policy = budget(20000);
        var plan = new CompactionPlanner(policy, cfg).plan(turns(8, "x".repeat(900)), 0, null, 3500, "prompt", "reduce");
        long envelope = SummaryRequests.tokens(SummaryRequests.reduce("reduce", "null", plan.blocks(), Collections.nCopies(plan.blocks().size(), "null"), 3500));
        assertTrue((long) plan.mapOutputLimit() * plan.blocks().size() + envelope < plan.reduceInputBudget());
        assertThrows(SummaryExecution.Failure.class, () -> new CompactionPlanner(policy, settings(512, 600, 2))
                .plan(turns(8, "x".repeat(900)), 0, null, 3500, "prompt", "reduce"));
        assertThrows(SummaryExecution.Failure.class, () -> new CompactionPlanner(budget(2000), cfg)
                .plan(turns(8, "x".repeat(900)), 0, null, 1000, "prompt", "reduce"));
    }
}
