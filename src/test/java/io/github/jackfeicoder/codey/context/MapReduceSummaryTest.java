package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.prompt.PromptLoader;
import io.github.jackfeicoder.codey.session.Message;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 同步信号控制实际并发与乱序完成，验证按序 Reduce、取消、总时限与单次输出验收。 */
class MapReduceSummaryTest {
    private CompactionSettings settings(int timeout) {
        return new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.2"), 3, 0, 24, timeout, 512, 1000, 2);
    }
    private CompactionPlan plan(int blocks) {
        var sources = new ArrayList<CompactionPlan.SourceBlock>();
        for (int i = 0; i < blocks; i++) sources.add(new CompactionPlan.SourceBlock(i, i * 2, i * 2 + 2, "source-" + i));
        return new CompactionPlan(CompactionPlan.Strategy.MAP_REDUCE, 10000, null, sources, "old-constraint", 512, 1024, 20000);
    }
    private SummaryDocument document(String text) {
        return new SummaryDocument(List.of(text), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
    private LlmResponse answer(String text) { return new LlmResponse(Message.assistant(document(text).json()), "stop", null); }
    private int block(LlmRequest request) { return Integer.parseInt(request.messages().get(1).content().split("历史块 #")[1].split("，")[0]); }

    @Test void mapsActuallyOverlapWithinLimitAndReduceUsesSourceOrderNotCompletionOrder() throws Exception {
        var both = new CountDownLatch(2); var releaseFirst = new CountDownLatch(1);
        var active = new AtomicInteger(); var peak = new AtomicInteger(); var reduces = new AtomicInteger();
        var logs = new ArrayList<String>();
        var debug = new DebugPrinter((kind, text) -> { logs.add(text); if (text.contains("Map 完成 1/")) releaseFirst.countDown(); });
        LlmClient client = request -> {
            String body = request.messages().get(1).content();
            assertTrue(request.tools().isEmpty()); assertFalse(request.toolCallsAllowed());
            if (body.contains("Reduce 合并")) {
                assertEquals(0, active.get()); assertEquals(1, reduces.incrementAndGet());
                assertTrue(body.indexOf("block-0") < body.indexOf("block-1")); assertTrue(body.indexOf("block-1") < body.indexOf("block-2"));
                assertEquals(1, body.split("old-constraint", -1).length - 1); return answer("final");
            }
            assertFalse(body.contains("old-constraint"));
            int n = block(request); peak.accumulateAndGet(active.incrementAndGet(), Math::max); both.countDown();
            try {
                assertTrue(both.await(2, TimeUnit.SECONDS));
                if (n == 0) assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                return answer("block-" + n);
            } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new AssertionError(stop); }
            finally { active.decrementAndGet(); }
        };
        try (var execution = new SummaryExecution(client, settings(5), debug)) {
            var result = new MapReduceSummaryStrategy().execute(plan(3), execution, "map", "reduce");
            assertEquals(document("final"), result); assertEquals(4, execution.calls()); assertEquals(2, peak.get());
            assertTrue(logs.stream().anyMatch(s -> s.contains("Map 完成 1/3（原始块 #1）")));
        }
    }

    @Test void failureCancelsOutstandingMapAndNeverRunsReduceOrAcceptsLateResults() throws Exception {
        var entered = new CountDownLatch(1); var cancelled = new CountDownLatch(1); var reduces = new AtomicInteger();
        LlmClient client = request -> {
            if (request.messages().get(1).content().contains("Reduce 合并")) { reduces.incrementAndGet(); return answer("bad"); }
            if (block(request) == 0) {
                entered.countDown();
                try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
                catch (InterruptedException stop) { cancelled.countDown(); }
                return answer("late");
            }
            try { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException stop) { throw new AssertionError(stop); }
            return new LlmResponse(Message.assistant("secret-invalid-json"), "stop", null);
        };
        try (var execution = new SummaryExecution(client, settings(5), DebugPrinter.NONE)) {
            var failed = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(2), execution, "map", "reduce"));
            assertFalse(failed.getMessage().contains("secret-invalid-json")); assertEquals(5, execution.calls());
        }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertEquals(0, reduces.get());
    }

    @Test void totalTimeoutIncludesMapsAndCancelsWorkersAndReduceFailureDiscardsCandidate() throws Exception {
        var cancelled = new CountDownLatch(2);
        try (var execution = new SummaryExecution(request -> {
            try { Thread.sleep(10000); } catch (InterruptedException stop) { cancelled.countDown(); }
            return answer("late");
        }, settings(1), DebugPrinter.NONE)) {
            var failed = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(3), execution, "map", "reduce"));
            assertTrue(failed.getMessage().contains("总超时"));
            assertEquals(2, execution.calls()); // 第三块仍排队，超时后不应进入客户端。
        }
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        try (var execution = new SummaryExecution(request -> request.messages().get(1).content().contains("Reduce 合并")
                ? new LlmResponse(Message.assistant("partial"), "length", null) : answer("map"), settings(5), DebugPrinter.NONE)) {
            var failed = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(2), execution, "map", "reduce"));
            assertTrue(failed.getMessage().contains("输出被截断")); assertEquals(3, execution.calls());
        }
    }

    @Test void oversizedActualMergeIsStoppedBeforeReduceAndReducePromptKeepsConflictRules() {
        var smallBudget = new CompactionPlan(CompactionPlan.Strategy.MAP_REDUCE, 10000, null, plan(2).blocks(), "null", 512, 1024, 10);
        try (var execution = new SummaryExecution(request -> answer("map"), settings(5), DebugPrinter.NONE)) {
            var failed = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(smallBudget, execution, "map", "reduce"));
            assertTrue(failed.getMessage().contains("Reduce 预算")); assertEquals(2, execution.calls());
        }
        String prompt = PromptLoader.loadCompactReduce();
        assertTrue(prompt.contains("只有明确的新要求")); assertTrue(prompt.contains("已经验证成功"));
        assertTrue(prompt.contains("按原始块编号")); assertTrue(prompt.contains("不执行历史任务"));
    }

    @Test void callerInterruptionCancelsMapsAndPreservesInterruptFlag() throws Exception {
        var entered = new CountDownLatch(2); var cancelled = new CountDownLatch(2);
        var failure = new java.util.concurrent.atomic.AtomicReference<LlmException>();
        var flag = new java.util.concurrent.atomic.AtomicBoolean();
        Thread caller = new Thread(() -> {
            try (var execution = new SummaryExecution(request -> {
                entered.countDown();
                try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
                catch (InterruptedException stop) { cancelled.countDown(); }
                return answer("late");
            }, settings(5), DebugPrinter.NONE)) {
                new MapReduceSummaryStrategy().execute(plan(2), execution, "map", "reduce");
            } catch (LlmException interrupted) { failure.set(interrupted); flag.set(Thread.currentThread().isInterrupted()); }
        });
        caller.setDaemon(true); caller.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive()); assertNotNull(failure.get()); assertEquals(LlmException.Kind.INTERRUPTED, failure.get().kind());
            assertTrue(flag.get()); assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        } finally { caller.interrupt(); caller.join(2000); }
    }
}
