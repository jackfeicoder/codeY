package io.github.jackfeicoder.codey.context;

import java.util.*;
import java.util.concurrent.*;

/** Map 有限并行，结果按来源顺序送入一次 Reduce；任一失败由宿主取消全部剩余任务。 */
public final class MapReduceSummaryStrategy {
    private record Mapped(int index, String json) { }
    SummaryDocument execute(CompactionPlan plan, SummaryExecution execution, String mapPrompt, String reducePrompt) {
        long started = System.nanoTime();
        execution.log("Map 开始：" + plan.blocks().size() + " 块，并发≤" + execution.concurrency()
                + "；每块 max_tokens=" + plan.mapOutputLimit() + "。");
        CompletionService<Mapped> completion = new ExecutorCompletionService<>(execution.workers());
        for (var block : plan.blocks()) {
            execution.remaining();
            var request = SummaryRequests.map(mapPrompt, block, plan.mapOutputLimit());
            execution.track(completion.submit(() -> new Mapped(block.index(), execution.call(request).json())));
        }
        var summaries = new ArrayList<>(Collections.nCopies(plan.blocks().size(), ""));
        for (int done = 1; done <= plan.blocks().size(); done++) {
            var result = execution.await(execution.next(completion));
            summaries.set(result.index(), result.json());
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
}
