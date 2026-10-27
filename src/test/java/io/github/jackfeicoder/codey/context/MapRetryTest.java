package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.Message;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 按块有限重试：成功结果复用、所有 Map 验收后才能 Reduce、总预算与总时限不重置。 */
class MapRetryTest {
    private CompactionSettings settings(int calls, int seconds) {
        return new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.2"), 3, 0, calls, seconds, 512, 1000, 2);
    }
    private CompactionPlan plan() {
        return new CompactionPlan(CompactionPlan.Strategy.MAP_REDUCE, 10000, null,
                List.of(new CompactionPlan.SourceBlock(0, 0, 2, "source-0"), new CompactionPlan.SourceBlock(1, 2, 4, "source-1")),
                "old-constraint", 512, 1024, 20000);
    }
    private LlmResponse answer(String value) {
        return new LlmResponse(Message.assistant(new SummaryDocument(List.of(value), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of()).json()), "stop", null);
    }
    private int block(LlmRequest request) { return request.messages().get(1).content().contains("历史块 #0") ? 0 : 1; }
    private boolean reduce(LlmRequest request) { return request.messages().get(1).content().contains("Reduce 合并"); }

    @Test void retriesOnlyFailedBlockKeepsSuccessAndStartsReduceAfterFinalSuccessfulAttempt() {
        var counts = List.of(new AtomicInteger(), new AtomicInteger()); var reduces = new AtomicInteger();
        var requests = Collections.synchronizedList(new ArrayList<LlmRequest>()); var logs = new ArrayList<String>();
        LlmClient client = request -> {
            if (reduce(request)) {
                assertEquals(1, counts.get(0).get()); assertEquals(3, counts.get(1).get());
                String body = request.messages().get(1).content();
                assertTrue(body.contains("success-0")); assertTrue(body.contains("success-1"));
                assertTrue(body.indexOf("success-0") < body.indexOf("success-1"));
                reduces.incrementAndGet(); return answer("final");
            }
            int block = block(request), attempt = counts.get(block).incrementAndGet();
            if (block == 1) {
                requests.add(request);
                if (attempt == 1) throw new LlmException(LlmException.Kind.HTTP, 503, "secret-server-body");
                if (attempt == 2) return new LlmResponse(Message.assistant("secret-invalid-json"), "stop", null);
            }
            return answer("success-" + block);
        };
        try (var execution = new SummaryExecution(client, settings(24, 5), new DebugPrinter((kind, text) -> logs.add(text)))) {
            var result = new MapReduceSummaryStrategy().execute(plan(), execution, "map", "reduce");
            assertEquals(List.of("final"), result.goals()); assertEquals(5, execution.calls());
        }
        assertEquals(1, reduces.get()); assertEquals(3, requests.size()); assertEquals(requests.get(0), requests.get(1));
        assertEquals(requests.get(1), requests.get(2));
        assertEquals(2, logs.stream().filter(s -> s.contains("仅重试该块")).count());
        assertFalse(String.join("\n", logs).contains("secret-"));
    }

    @Test void fourthFailureAbortsWithoutRerunningSuccessfulBlockOrStartingReduce() {
        var counts = List.of(new AtomicInteger(), new AtomicInteger()); var reduces = new AtomicInteger();
        try (var execution = new SummaryExecution(request -> {
            if (reduce(request)) { reduces.incrementAndGet(); return answer("bad"); }
            int block = block(request); counts.get(block).incrementAndGet();
            if (block == 1) throw new LlmException(LlmException.Kind.HTTP, 429, "secret-rate-limit");
            return answer("success");
        }, settings(24, 5), DebugPrinter.NONE)) {
            var error = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(), execution, "map", "reduce"));
            assertTrue(error.getMessage().contains("连续失败 4 次")); assertTrue(error.getMessage().contains("已重试 3 次"));
            assertTrue(error.getMessage().contains("HTTP 429")); assertFalse(error.getMessage().contains("secret-rate-limit"));
            assertEquals(5, execution.calls());
        }
        assertEquals(1, counts.get(0).get()); assertEquals(4, counts.get(1).get()); assertEquals(0, reduces.get());
    }

    @Test void nonRetryableAuthenticationFailureStopsImmediately() {
        var failedCalls = new AtomicInteger(); var reduces = new AtomicInteger();
        try (var execution = new SummaryExecution(request -> {
            if (reduce(request)) { reduces.incrementAndGet(); return answer("bad"); }
            if (block(request) == 1) { failedCalls.incrementAndGet(); throw new LlmException(LlmException.Kind.HTTP, 401, "secret-key"); }
            return answer("success");
        }, settings(24, 5), DebugPrinter.NONE)) {
            var error = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(), execution, "map", "reduce"));
            assertTrue(error.getMessage().contains("HTTP 401")); assertFalse(error.getMessage().contains("secret-key"));
        }
        assertEquals(1, failedCalls.get()); assertEquals(0, reduces.get());
    }

    @Test void retriesRespectTotalCallBudgetAndKeepOneSlotForReduceWithoutOvercounting() {
        var calls = new AtomicInteger(); var failing = new AtomicInteger();
        try (var execution = new SummaryExecution(request -> {
            calls.incrementAndGet(); assertFalse(reduce(request));
            if (block(request) == 1) { failing.incrementAndGet(); throw new LlmException(LlmException.Kind.NETWORK, "secret"); }
            return answer("success");
        }, settings(4, 5), DebugPrinter.NONE)) {
            var error = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(), execution, "map", "reduce"));
            assertTrue(error.getMessage().contains("调用预算不足")); assertEquals(3, calls.get()); assertEquals(3, execution.calls());
            assertEquals(2, failing.get());
        }
    }

    @Test void backoffDoesNotResetDeadlineOrSpendCallsWithoutSendingRequest() {
        var calls = new AtomicInteger();
        try (var execution = new SummaryExecution(request -> {
            calls.incrementAndGet(); assertFalse(reduce(request));
            if (block(request) == 1) throw new LlmException(LlmException.Kind.TIMEOUT, "request timeout");
            return answer("success");
        }, settings(24, 1), DebugPrinter.NONE)) {
            var error = assertThrows(SummaryExecution.Failure.class,
                    () -> new MapReduceSummaryStrategy().execute(plan(), execution, "map", "reduce"));
            assertTrue(error.getMessage().contains("时限") || error.getMessage().contains("超时"));
            assertTrue(calls.get() < 5); assertEquals(calls.get(), execution.calls());
        }
    }
}
