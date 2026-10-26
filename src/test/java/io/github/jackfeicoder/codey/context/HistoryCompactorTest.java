package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离模型替身验证选择/分块/验收/回滚；不读取用户配置或请求真实厂商。 */
class HistoryCompactorTest {
    private final ContextBuilder builder = new ContextBuilder();
    private final ContextBudgetPolicy budget = new ContextBudgetPolicy(new ModelCapabilities(12000L),
            new ContextBudgetSettings(1024, 1024, new BigDecimal("0.8")));
    private final CompactionSettings settings = new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.4"), 3, 512, 8, 5);
    private final List<LlmRequest> requests = new ArrayList<>();

    static SummaryDocument summary() {
        return new SummaryDocument(List.of("完成配置模块"), List.of("不要引入 Spring Boot"), List.of("记录来自历史"),
                List.of("保持模块解耦"), List.of("A.java 已修改"), List.of("测试已运行并通过"), List.of("MCP 待实现"), List.of("继续验证"));
    }
    private Session history(String body) {
        var session = new Session("base");
        for (int i = 0; i < 4; i++) session.completeTurn("old-" + i + " 不要引入 Spring Boot", Message.assistant(body));
        for (int i = 4; i < 7; i++) session.completeTurn("recent-" + i, Message.assistant("answer-" + i));
        return session;
    }
    private LlmResponse answer() { return new LlmResponse(Message.assistant(summary().json()), "stop", null); }
    private HistoryCompactor compactor(CompactionSettings cfg, LlmClient client) {
        return new HistoryCompactor(client, budget, cfg, builder, DebugPrinter.NONE);
    }
    private HistoryCompactor.Outcome prepare(HistoryCompactor compactor, Session session, boolean force) {
        return compactor.prepare(session, List.of(Message.user("continue")), List.of(), WorkMode.REACT, 1, 20, force);
    }

    @Test void keepsThreeCompleteRecentTurnsAndUsesNonPrivilegedSummaryWithoutChangingRawHistory() {
        var session = history("x".repeat(6000)); var original = session.messages();
        var outcome = prepare(compactor(settings, request -> { requests.add(request); return answer(); }), session, false);
        assertTrue(outcome.changed(), outcome.reason());
        assertEquals(original, session.messages()); assertEquals(8, session.compaction().coveredMessages());
        assertTrue(outcome.context().tokens().summary() > 0);
        assertTrue(outcome.context().tokens().total() <= 3980); // floor(9952 * .4)
        var sent = outcome.context().request().messages();
        assertEquals(Message.system("base"), sent.get(0));
        assertEquals(MessageRole.USER, sent.get(1).role()); assertTrue(sent.get(1).content().endsWith("recent-4"));
        assertEquals(Message.assistant("answer-4"), sent.get(2));
        assertFalse(sent.toString().contains("x".repeat(1000)));
        assertEquals(Message.user("continue"), sent.get(sent.size() - 1));
        for (var request : requests) {
            assertTrue(request.tools().isEmpty()); assertFalse(request.toolCallsAllowed());
            assertTrue(request.maxOutputTokens() <= 512); assertTrue(request.messages().get(0).content().contains("只生成摘要"));
            assertTrue(request.messages().get(1).content().contains("历史数据片段"));
        }
    }

    @Test void secondCompactionConsumesPreviousSummaryAndOnlyNewlyCoveredRawMessages() {
        var session = history("x".repeat(6000));
        var compactor = compactor(settings, request -> { requests.add(request); return answer(); });
        assertTrue(prepare(compactor, session, false).changed());
        requests.clear(); session.completeTurn("recent-7", Message.assistant("answer-7"));
        var outcome = prepare(compactor, session, true);
        assertTrue(outcome.changed(), outcome.reason()); assertEquals(10, session.compaction().coveredMessages());
        String source = requests.get(0).messages().get(1).content();
        assertTrue(source.contains("previousSummary")); assertTrue(source.contains("recent-4"));
        assertFalse(source.contains("x".repeat(1000))); assertFalse(source.contains("recent-7"));
        assertTrue(session.compaction().summary().constraints().contains("不要引入 Spring Boot"));
    }

    @Test void fallsBackToTwoWholeTurnsWhenThreeWouldMissTargetAndRetainsPairedToolGroups() {
        var session = new Session("base");
        for (int i = 0; i < 6; i++) session.completeAgentTurn(List.of(Message.user("q" + i),
                Message.assistantToolCalls(List.of(new ToolCall("c" + i, "read_file", "{}"))),
                Message.tool("c" + i, "x".repeat(4500)), Message.assistant("done")));
        var outcome = prepare(compactor(settings, request -> answer()), session, false);
        assertTrue(outcome.changed(), outcome.reason());
        assertEquals(16, session.compaction().coveredMessages());
        var messages = outcome.context().request().messages();
        assertEquals(2, messages.stream().filter(m -> m.role() == MessageRole.TOOL).count());
        assertEquals("c4", messages.get(2).toolCalls().get(0).id()); assertEquals("c4", messages.get(3).toolCallId());
        assertEquals("c5", messages.get(6).toolCalls().get(0).id()); assertEquals("c5", messages.get(7).toolCallId());
    }

    @Test void chunksOversizedHistoryWithinBudgetAndNeverSplitsUnicodeSurrogates() {
        var session = history("😀".repeat(3000));
        var outcome = prepare(compactor(settings, request -> { requests.add(request); return answer(); }), session, false);
        assertTrue(outcome.changed(), outcome.reason()); assertTrue(requests.size() > 1);
        var estimator = new TokenEstimator();
        for (var request : requests) {
            assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(request.messages().get(1).content()));
            assertTrue(request.messages().stream().mapToLong(estimator::messageBodyTokens).sum() + 15 <= 9952);
        }
    }

    @Test void samePrefixIsNotCompactedRepeatedlyDuringNextReactSteps() {
        var session = history("x".repeat(6000)); var calls = new AtomicInteger();
        var twoTurns = new CompactionSettings(true, settings.triggerRatio(), settings.targetRatio(), 2, 512, 8, 5);
        var compactor = compactor(twoTurns, request -> { calls.incrementAndGet(); return answer(); });
        assertTrue(prepare(compactor, session, false).changed()); int before = calls.get();
        var outcome = compactor.prepare(session, List.of(Message.user("z".repeat(25000))), List.of(), WorkMode.REACT, 2, 20, false);
        assertFalse(outcome.changed()); assertEquals(before, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "invalid", "oversized", "length", "tool"})
    void rejectsUnusableCandidatesAndKeepsOriginalHistory(String kind) {
        var session = history("x".repeat(6000)); var original = session.messages();
        var outcome = prepare(compactor(settings, request -> switch (kind) {
            case "empty" -> new LlmResponse(Message.assistant(""), "stop", null);
            case "invalid" -> new LlmResponse(Message.assistant("{secret-fixture}"), "stop", null);
            case "oversized" -> new LlmResponse(Message.assistant(new SummaryDocument(List.of("中".repeat(3000)), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of()).json()), "stop", null);
            case "length" -> new LlmResponse(Message.assistant(summary().json()), "length", null);
            default -> new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("bad", "write_file", "{}"))), "tool_calls", null);
        }), session, false);
        assertFalse(outcome.changed()); assertNull(session.compaction()); assertEquals(original, session.messages());
        assertFalse(outcome.reason().contains("secret-fixture"));
    }

    @Test void partialChunkFailureOrCallLimitNeverInstallsHalfGeneratedSummary() {
        var session = history("x".repeat(10000)); var raw = session.messages().subList(1, session.messages().size());
        var old = new CompactionState(2, CompactionState.fingerprint(raw.subList(0, 2)), summary(), Instant.now().toString());
        session.applyCompaction(old); var original = session.messages(); var calls = new AtomicInteger();
        var outcome = prepare(compactor(settings, request -> {
            if (calls.incrementAndGet() == 1) return answer();
            throw new LlmException(LlmException.Kind.HTTP, "secret-fixture");
        }), session, false);
        assertFalse(outcome.changed()); assertEquals(2, calls.get()); assertSame(old, session.compaction());
        assertEquals(original, session.messages()); assertFalse(outcome.reason().contains("secret-fixture"));
        var oneCall = new CompactionSettings(true, settings.triggerRatio(), settings.targetRatio(), 3, 512, 1, 5);
        assertFalse(prepare(compactor(oneCall, request -> answer()), session, false).changed()); assertSame(old, session.compaction());
    }

    @Test void timeoutCancelsWorkerAndRetainsState() throws Exception {
        var session = history("x".repeat(6000)); var interrupted = new CountDownLatch(1);
        var cfg = new CompactionSettings(true, settings.triggerRatio(), settings.targetRatio(), 3, 512, 8, 1);
        long start = System.nanoTime();
        var outcome = prepare(compactor(cfg, request -> {
            try { Thread.sleep(5000); } catch (InterruptedException stop) { interrupted.countDown(); }
            return answer();
        }), session, false);
        assertFalse(outcome.changed()); assertNull(session.compaction());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000);
        assertTrue(interrupted.await(1, TimeUnit.SECONDS)); assertNull(session.compaction());
    }

    @Test void unknownDisabledLowUsageOrInsufficientOldTurnsDoNotCallSummaryModel() {
        LlmClient never = request -> { fail("No summary request expected"); return answer(); };
        var session = history("small");
        assertEquals(0, prepare(compactor(settings, never), session, false).summaryCalls());
        var disabled = new CompactionSettings(false, settings.triggerRatio(), settings.targetRatio(), 3, 512, 8, 5);
        assertEquals(0, prepare(compactor(disabled, never), session, true).summaryCalls());
        assertEquals(0, prepare(new HistoryCompactor(never, ContextBudgetPolicy.unknown(), settings, builder, DebugPrinter.NONE), session, true).summaryCalls());
        var shortHistory = new Session("base"); shortHistory.completeTurn("q", Message.assistant("x".repeat(40000)));
        assertEquals(0, prepare(compactor(settings, never), shortHistory, false).summaryCalls());
    }

    @Test void rejectsCandidateThatDoesNotActuallyReduceInputAndPropagatesInterruption() {
        var session = history("small");
        assertFalse(prepare(compactor(settings, request -> answer()), session, true).changed()); assertNull(session.compaction());
        try {
            Thread.currentThread().interrupt();
            var exception = assertThrows(LlmException.class, () -> prepare(compactor(settings, request -> answer()), history("x".repeat(6000)), false));
            assertEquals(LlmException.Kind.INTERRUPTED, exception.kind());
        } finally { Thread.interrupted(); }
    }
}
