package io.github.jackfeicoder.codey.context;

/** 小历史只调用一次模型；旧摘要与新覆盖原文共同整理。 */
public final class SingleSummaryStrategy {
    SummaryDocument execute(CompactionPlan plan, SummaryExecution execution) {
        long started = System.nanoTime();
        execution.log("Single 第 1 次摘要请求（非流式、无工具；max_tokens=" + plan.finalOutputLimit() + "）。");
        var result = execution.await(execution.track(execution.workers().submit(() -> execution.call(plan.singleRequest()))));
        execution.log("Single 完成，用时 " + (System.nanoTime() - started) / 1000000 + " ms。");
        return result;
    }
}
