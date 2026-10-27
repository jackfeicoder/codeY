package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.config.CompactionSettings;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 一次压缩共享的线程池、实际调用计数和总截止时间；线程只产出候选，永不更新会话。 */
final class SummaryExecution implements AutoCloseable {
    /** 仅响应质量错误适合按块重试；预算、总时限等全局错误必须停止。 */
    static final class Failure extends RuntimeException {
        private final boolean retryable;
        Failure(String message, boolean retryable) { super(message); this.retryable = retryable; }
        boolean retryable() { return retryable; }
    }
    private final LlmClient client;
    private final CompactionSettings settings;
    private final DebugPrinter debug;
    private final long deadline;
    private final ExecutorService workers;
    private final List<Future<?>> futures = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    SummaryExecution(LlmClient client, CompactionSettings settings, DebugPrinter debug) {
        this.client = client; this.settings = settings; this.debug = debug;
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.timeoutSeconds());
        workers = Executors.newFixedThreadPool(settings.concurrency(), action -> {
            var thread = new Thread(action, "codey-compaction"); thread.setDaemon(true); return thread;
        });
    }
    int calls() { return calls.get(); }
    int concurrency() { return settings.concurrency(); }
    void log(String message) { debug.compaction(message); }
    ExecutorService workers() { return workers; }
    <T> Future<T> track(Future<T> future) { futures.add(future); return future; }
    /** 进入客户端才计数；取消排队的块不计为已请求。 */
    SummaryDocument call(LlmRequest request) { return call(request, 0); }
    /** Map 为最终 Reduce 保留调用名额；CAS 保证并发抢占预算不超发，也不虚报次数。 */
    SummaryDocument call(LlmRequest request, int reservedCalls) {
        remaining();
        while (true) {
            int previous = calls.get();
            if (previous >= settings.maxCalls() - reservedCalls) throw failure("摘要总调用预算不足，已为 Reduce 保留名额");
            if (calls.compareAndSet(previous, previous + 1)) break;
        }
        var response = client.generate(request);
        remaining(); return validate(response, request.maxOutputTokens());
    }
    /** 短退避也受原总截止时间约束；只暂停失败块，不阻塞主线程收集其他成功块。 */
    void pauseBeforeRetry(int retry) {
        long millis = 250L << (retry - 1);
        if (TimeUnit.MILLISECONDS.toNanos(millis) >= remaining()) throw failure("摘要剩余总时限不足以等待 Map 重试");
        try { TimeUnit.MILLISECONDS.sleep(millis); }
        catch (InterruptedException stop) { throw interrupted(); }
        remaining();
    }
    <T> T await(Future<T> future) {
        try { return future.get(remaining(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException timeout) { future.cancel(true); throw failure("摘要总超时（" + settings.timeoutSeconds() + " 秒），候选已放弃"); }
        catch (InterruptedException interrupted) { future.cancel(true); throw interrupted(); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof LlmException llm) throw llm;
            if (failed.getCause() instanceof Failure known) throw known;
            throw failure("摘要请求失败，候选已放弃");
        }
    }
    <T> Future<T> next(CompletionService<T> completion) {
        try {
            var next = completion.poll(remaining(), TimeUnit.NANOSECONDS);
            if (next == null) throw failure("摘要总超时（" + settings.timeoutSeconds() + " 秒），候选已放弃");
            return next;
        } catch (InterruptedException interrupted) { throw interrupted(); }
    }
    long remaining() {
        if (Thread.currentThread().isInterrupted()) throw interrupted();
        long left = deadline - System.nanoTime();
        if (left <= 0) throw failure("摘要总超时（" + settings.timeoutSeconds() + " 秒），候选已放弃");
        return left;
    }
    private static LlmException interrupted() {
        Thread.currentThread().interrupt(); return new LlmException(LlmException.Kind.INTERRUPTED, "Context compaction was interrupted.");
    }
    /** 不回显正文、工具参数或未经校验的远端字符串。 */
    private static SummaryDocument validate(LlmResponse response, int limit) {
        String details = details(response, limit);
        if (response == null) throw responseFailure("摘要模型未返回响应" + details);
        if (response.isTruncated()) throw responseFailure("摘要输出被截断" + details
                + "；可检查摘要剩余空间、CODEY_CONTEXT_SUMMARY_MAX_TOKENS 和模型 maxOutputTokens（部分模型包含推理用量）");
        if (!response.toolCalls().isEmpty()) throw responseFailure("摘要返回了工具调用，候选已放弃" + details);
        if (!"stop".equals(response.finishReason())) throw responseFailure("摘要结束原因不支持，候选已放弃" + details);
        if (response.text().isBlank()) throw responseFailure("摘要正文为空" + details);
        SummaryDocument document;
        try { document = SummaryDocument.parse(response.text()); }
        catch (IllegalArgumentException invalid) { throw responseFailure("摘要 JSON 结构或长度校验失败" + details); }
        if (new TokenEstimator().textTokens(document.json()) > limit) throw responseFailure("摘要正文超过估算 Token 上限，候选已放弃" + details);
        return document;
    }
    private static String details(LlmResponse response, int limit) {
        String finish = "unknown";
        if (response != null) finish = switch (response.finishReason()) {
            case "stop", "length", "tool_calls", "function_call", "content_filter" -> response.finishReason();
            default -> "other";
        };
        Long completion = response == null || response.usage() == null ? null : response.usage().completionTokens();
        return "（finish_reason=" + finish + ", tool_calls=" + (response == null ? "unknown" : response.toolCalls().size())
                + ", 正文字符=" + (response == null ? "unknown" : response.text().codePointCount(0, response.text().length()))
                + ", max_tokens=" + limit + ", completion_tokens=" + (completion == null ? "unknown" : completion) + "）";
    }
    static Failure failure(String message) { return new Failure(message, false); }
    private static Failure responseFailure(String message) { return new Failure(message, true); }
    @Override public void close() { for (var future : futures) future.cancel(true); workers.shutdownNow(); }
}
