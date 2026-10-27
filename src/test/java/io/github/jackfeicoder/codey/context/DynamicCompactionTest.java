package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 动态摘要额度、保护原文优先、软水位放宽及摘要请求输入/输出共同占用窗口。 */
class DynamicCompactionTest {
    private final ContextBuilder builder = new ContextBuilder();
    private final List<LlmRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<Message> current = List.of(Message.user("continue"));
    private final CompactionSettings settings = new CompactionSettings();
    private SummaryDocument summary() { return new SummaryDocument(List.of("goal"), List.of("constraint"), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of("next")); }
    private Session history(int oldChars, int recentChars) {
        var session = new Session("base");
        for (int i = 0; i < 4; i++) session.completeTurn("old" + i, Message.assistant("x".repeat(oldChars)));
        for (int i = 0; i < 3; i++) session.completeTurn("recent" + i, Message.assistant("y".repeat(recentChars)));
        return session;
    }
    private ContextBudgetPolicy budget(long window, Long outputCap) {
        return new ContextBudgetPolicy(new ModelCapabilities(window, 1024L, 1024L, outputCap), new ContextBudgetSettings());
    }
    private HistoryCompactor compactor(ContextBudgetPolicy budget) {
        return new HistoryCompactor(request -> { requests.add(request); return new LlmResponse(Message.assistant(summary().json()), "stop", null); },
                budget, settings, builder, DebugPrinter.NONE);
    }
    private HistoryCompactor.Outcome prepare(HistoryCompactor compactor, Session session) {
        return compactor.prepare(session, current, List.of(), WorkMode.REACT, 1, 20, true);
    }

    @Test void allocatesEntireRemainderWithoutFourKOrNormalOutputReserveCapAndKeepsThreeTurns() {
        var session = history(60000, 300); var raw = session.messages(); var policy = budget(1000000, null);
        var tail = new ArrayList<Message>(); tail.add(raw.get(0)); tail.addAll(raw.subList(9, raw.size()));
        long fixed = builder.build(tail, current, List.of(), WorkMode.REACT, 1, 20).tokens().total();
        long expected = policy.evaluate(0).inputBudget() / 5 - fixed - 256;
        var result = prepare(compactor(policy), session);
        assertTrue(result.changed(), result.reason()); assertEquals(8, session.compaction().coveredMessages());
        assertEquals(expected, requests.get(0).maxOutputTokens().longValue()); assertTrue(expected > 100000);
        assertEquals(raw, session.messages());
        assertTrue(requests.get(0).messages().get(1).content().contains("这是上限而非目标长度"));
        assertTrue(result.context().tokens().total() < policy.evaluate(0).inputBudget() / 5);
    }

    @Test void modelSingleOutputCapOverridesDynamicSpaceButNotOrdinaryOutputReserve() {
        var session = history(60000, 300); var policy = budget(1000000, 32768L);
        assertTrue(prepare(compactor(policy), session).changed());
        assertEquals(32768, requests.get(0).maxOutputTokens());
        assertEquals(1024, policy.evaluate(0).outputReserveTokens());
        requests.clear();
        var explicit = new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.2"), 3, 6000, 8, 60);
        var compactor = new HistoryCompactor(request -> { requests.add(request); return new LlmResponse(Message.assistant(summary().json()), "stop", null); },
                policy, explicit, builder, DebugPrinter.NONE);
        assertTrue(prepare(compactor, history(60000, 300)).changed()); assertEquals(6000, requests.get(0).maxOutputTokens());
    }

    @Test void protectsThreeTurnsEvenIfTheyNeedModestExcessAndFallsBackOnlyWhenNoSummaryFits() {
        var policy = budget(12000, null); // input 9952, expected 1990, soft 2488
        var session = history(12000, 1800); // recent about 1800 tokens, wrapper leaves too little within 20%
        var result = prepare(compactor(policy), session);
        assertTrue(result.changed(), result.reason()); assertEquals(8, session.compaction().coveredMessages());
        assertTrue(requests.get(0).maxOutputTokens() >= 128);
        assertTrue(result.context().tokens().total() <= 2488);
        requests.clear(); session = history(12000, 2400); // three recent > soft; two still fit
        result = prepare(compactor(policy), session);
        assertTrue(result.changed(), result.reason()); assertEquals(10, session.compaction().coveredMessages());
        assertTrue(result.context().tokens().total() <= 2488);
    }

    @Test void tooLargeProtectedTasksOrTinyModelOutputCapSkipWithoutNetworkOrDataLoss() {
        var session = history(12000, 6000); var raw = session.messages();
        assertFalse(prepare(compactor(budget(12000, null)), session).changed());
        assertTrue(requests.isEmpty()); assertNull(session.compaction()); assertEquals(raw, session.messages());
        assertFalse(prepare(compactor(budget(1000000, 64L)), history(60000, 300)).changed()); assertTrue(requests.isEmpty());
    }

    @Test void summaryMayExceedOldStorageAndFourKLimitsWithoutRequiringItToFillAllowance() {
        var large = new SummaryDocument(List.of("a".repeat(40000), "b".repeat(40000)), List.of("constraint"), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals(large, SummaryDocument.parse(large.json()));
        var session = history(90000, 300); var raw = session.messages(); var policy = budget(1000000, null);
        var compactor = new HistoryCompactor(request -> new LlmResponse(Message.assistant(large.json()), "stop", null),
                policy, settings, builder, DebugPrinter.NONE);
        var result = prepare(compactor, session);
        assertTrue(result.changed(), result.reason()); assertEquals(large, session.compaction().summary()); assertEquals(raw, session.messages());
    }

    @Test void enlargedSummaryOutputIsReservedWhenChunkingInputsAndHandlesLongWindowArithmetic() {
        var session = history(650000, 300); var policy = budget(1000000, null);
        var result = prepare(compactor(policy), session);
        assertTrue(result.changed(), result.reason()); assertTrue(requests.size() > 1);
        var estimator = new TokenEstimator();
        for (var request : requests) {
            long input = request.messages().stream().mapToLong(estimator::messageBodyTokens).sum() + 15;
            assertTrue(input + request.maxOutputTokens() + 1024 <= 1000000);
            assertTrue(input <= policy.evaluate(0).inputBudget());
        }
        requests.clear();
        assertTrue(prepare(compactor(budget(Long.MAX_VALUE, null)), history(60000, 300)).changed());
        assertEquals(Integer.MAX_VALUE, requests.get(0).maxOutputTokens());
    }
}
