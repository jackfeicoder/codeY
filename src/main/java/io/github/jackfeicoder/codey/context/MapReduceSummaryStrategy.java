package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.llm.LlmException;
import java.util.*;
import java.util.concurrent.*;

/** 有限并行 Map；只重试失败块，成功结果保留在本次执行内；全部成功后按来源顺序 Reduce。 */
public final class MapReduceSummaryStrategy {
    /** 首次调用后最多再试三次；第四次仍失败才达到本块的重试上限。 */
    public static final int MAX_MAP_RETRIES = 3;
    private record Mapped(int index, int attempt, String json, RuntimeException failure) { }
    SummaryDocument execute(CompactionPlan plan, SummaryExecution execution, String mapPrompt, String reducePrompt) {
        long started = System.nanoTime();
        execution.log("Map 开始：" + plan.blocks().size() + " 块，并发≤" + execution.concurrency()
                + "；每块 max_tokens=" + plan.mapOutputLimit() + "；失败块最多重试 " + MAX_MAP_RETRIES + " 次。");
        CompletionService<Mapped> completion = new ExecutorCompletionService<>(execution.workers());
        for (var block : plan.blocks()) {
            execution.remaining();
            submit(plan, block, 1, execution, completion, mapPrompt);
        }
        var summaries = new ArrayList<>(Collections.nCopies(plan.blocks().size(), ""));
        int done = 0;
        while (done < plan.blocks().size()) {
            var result = execution.await(execution.next(completion));
            if (result.failure() != null) {
                var failed = result.failure();
                if (failed instanceof LlmException llm && llm.kind() == LlmException.Kind.INTERRUPTED) throw llm;
                String reason = describe(failed);
                if (!retryable(failed)) throw SummaryExecution.failure("Map #" + result.index() + " 失败，停止压缩：" + reason);
                if (result.attempt() > MAX_MAP_RETRIES) throw SummaryExecution.failure("Map #" + result.index()
                        + " 连续失败 " + result.attempt() + " 次（已重试 " + MAX_MAP_RETRIES + " 次），停止压缩：" + reason);
                execution.remaining();
                execution.log("Map #" + result.index() + " 第 " + result.attempt() + " 次尝试失败：" + reason
                        + "；仅重试该块 " + result.attempt() + "/" + MAX_MAP_RETRIES + "，其他成功子摘要保留。");
                submit(plan, plan.blocks().get(result.index()), result.attempt() + 1, execution, completion, mapPrompt);
                continue;
            }
            summaries.set(result.index(), result.json());
            done++;
            execution.log("Map 完成 " + done + "/" + plan.blocks().size() + "（原始块 #" + result.index() + "）。");
        }
        execution.log("Map 全部完成，用时 " + (System.nanoTime() - started) / 1000000 + " ms。");
        var reduce = SummaryRequests.reduce(reducePrompt, plan.previousSummary(), plan.blocks(), summaries, plan.finalOutputLimit());
        if (SummaryRequests.tokens(reduce) > plan.reduceInputBudget()) throw SummaryExecution.failure("子摘要合并输入超过 Reduce 预算，候选已放弃");
        execution.remaining(); started = System.nanoTime();
        execution.log("Reduce 开始：按原始顺序合并 " + summaries.size() + " 份子摘要；max_tokens=" + plan.finalOutputLimit() + "。");
        var result = execution.await(execution.track(execution.workers().submit(() -> execution.call(reduce))));
        execution.log("Reduce 完成，用时 " + (System.nanoTime() - started) / 1000000 + " ms。");
        return result;
    }

    private void submit(CompactionPlan plan, CompactionPlan.SourceBlock block, int attempt, SummaryExecution execution,
                        CompletionService<Mapped> completion, String prompt) {
        var request = SummaryRequests.map(prompt, block, plan.mapOutputLimit());
        execution.track(completion.submit(() -> {
            try {
                if (attempt > 1) execution.pauseBeforeRetry(attempt - 1);
                return new Mapped(block.index(), attempt, execution.call(request, 1).json(), null);
            } catch (RuntimeException failed) { return new Mapped(block.index(), attempt, null, failed); }
        }));
    }
    private static boolean retryable(RuntimeException failed) {
        if (failed instanceof SummaryExecution.Failure known) return known.retryable();
        return failed instanceof LlmException llm && (llm.isRetryable() || llm.kind() == LlmException.Kind.INVALID_RESPONSE
                || llm.kind() == LlmException.Kind.MODEL_REJECTION);
    }
    /** 不回显异常原文，响应验收错误只包含程序生成的诊断信息。 */
    private static String describe(RuntimeException failed) {
        if (failed instanceof LlmException llm) return llm.kind() + (llm.statusCode() == null ? "" : " " + llm.statusCode());
        return failed instanceof SummaryExecution.Failure ? failed.getMessage() : "摘要请求异常";
    }
}
