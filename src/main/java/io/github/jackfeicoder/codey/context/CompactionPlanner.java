package io.github.jackfeicoder.codey.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.CompactionSettings;
import io.github.jackfeicoder.codey.session.*;
import java.util.*;

/** 纯本地规划：按来源 Token 选择策略，优先任务/完整工具组边界，并预算 Reduce 总输入。 */
public final class CompactionPlanner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ContextBudgetPolicy budget;
    private final CompactionSettings settings;
    private final TokenEstimator estimator = new TokenEstimator();
    private record Unit(int first, int end, String text) { }

    public CompactionPlanner(ContextBudgetPolicy budget, CompactionSettings settings) {
        this.budget = Objects.requireNonNull(budget); this.settings = Objects.requireNonNull(settings);
    }

    /** newMessages 是本次新覆盖的已完成原始记录；旧摘要不重复送入每个 Map。 */
    public CompactionPlan plan(List<Message> newMessages, int firstMessage, SummaryDocument previous, int finalLimit,
                               String prompt, String reducePrompt) {
        String prior = previous == null ? "null" : previous.json();
        var source = MAPPER.createObjectNode();
        source.set("previousSummary", MAPPER.valueToTree(previous)); source.set("newMessages", MAPPER.valueToTree(newMessages));
        long inputBudget = budget.inputBudgetForOutput(finalLimit);
        var single = SummaryRequests.single(prompt, source.toString(), finalLimit);
        long sourceTokens = estimator.textTokens(source.toString());
        if ((sourceTokens <= settings.singleMaxSourceTokens() || newMessages.isEmpty()) && SummaryRequests.tokens(single) <= inputBudget)
            return new CompactionPlan(CompactionPlan.Strategy.SINGLE, sourceTokens, single, List.of(), prior, 0, finalLimit, inputBudget);
        if (newMessages.isEmpty()) throw failure("旧摘要本身超过单次输入预算，原摘要保持不变");

        var units = new ArrayList<Unit>();
        // 完整用户任务优先，超大任务才退到消息/assistant+全部 tool 结果边界。
        for (int start = 0; start < newMessages.size();) {
            checkInterrupted();
            int end = start + 1;
            while (end < newMessages.size() && newMessages.get(end).role() != MessageRole.USER) end++;
            Unit task = unit(newMessages, start, end, firstMessage);
            if (fits(task.text(), prompt, finalLimit, inputBudget)) units.add(task);
            else for (int item = start; item < end;) {
                int next = item + 1;
                if (!newMessages.get(item).toolCalls().isEmpty()) next += newMessages.get(item).toolCalls().size();
                units.add(unit(newMessages, item, next, firstMessage)); item = next;
            }
            start = end;
        }
        var blocks = new ArrayList<CompactionPlan.SourceBlock>();
        String pending = ""; int pendingFirst = 0, pendingEnd = 0;
        for (var unit : units) {
            checkInterrupted();
            String combined = pending.isEmpty() ? unit.text() : pending + "\n" + unit.text();
            if (fits(combined, prompt, finalLimit, inputBudget)) {
                if (pending.isEmpty()) pendingFirst = unit.first();
                pending = combined; pendingEnd = unit.end(); continue;
            }
            if (!pending.isEmpty()) { add(blocks, pendingFirst, pendingEnd, pending); pending = ""; }
            if (fits(unit.text(), prompt, finalLimit, inputBudget)) {
                pending = unit.text(); pendingFirst = unit.first(); pendingEnd = unit.end(); continue;
            }
            // 超大消息组作为资料文本分片，不创建孤立的业务 tool 消息，原记录不变。
            int offset = 0;
            while (offset < unit.text().length()) {
                checkInterrupted();
                int end = fitEnd(unit, offset, prompt, finalLimit, inputBudget);
                if (end <= offset) throw failure("摘要指令或模型输出预留过大，最小资料片段也放不下");
                add(blocks, unit.first(), unit.end(), fragment(unit, offset, end)); offset = end;
            }
        }
        if (!pending.isEmpty()) add(blocks, pendingFirst, pendingEnd, pending);
        if (blocks.size() + 1 > settings.maxCalls()) throw failure("Map 加 Reduce 超过摘要总调用数上限，规划已停止");
        long reduceOverhead = SummaryRequests.tokens(SummaryRequests.reduce(reducePrompt, prior, blocks,
                Collections.nCopies(blocks.size(), "null"), finalLimit));
        // 留足框架及启发式分项舍入余量；子摘要按最终发送的规范 JSON 计数。
        long each = (inputBudget - reduceOverhead - 1024) / blocks.size();
        int mapLimit = (int) Math.min(finalLimit, each);
        if (mapLimit < 128) throw failure("旧摘要与 Reduce 包装占用过大，子摘要没有足够合并空间");
        return new CompactionPlan(CompactionPlan.Strategy.MAP_REDUCE, sourceTokens, null, blocks, prior, mapLimit, finalLimit, inputBudget);
    }

    private Unit unit(List<Message> messages, int start, int end, int base) {
        String text = "原始消息范围 [" + (base + start) + "," + (base + end) + ")：\n" + MAPPER.valueToTree(messages.subList(start, end));
        return new Unit(base + start, base + end, text);
    }
    private boolean fits(String text, String prompt, int limit, long inputBudget) {
        return estimator.textTokens(text) <= settings.chunkTokens()
                && SummaryRequests.tokens(SummaryRequests.map(prompt, new CompactionPlan.SourceBlock(999, 0, Integer.MAX_VALUE, text), limit)) <= inputBudget;
    }
    private int fitEnd(Unit unit, int offset, String prompt, int limit, long inputBudget) {
        int low = 1, high = unit.text().length() - offset, best = offset;
        while (low <= high) {
            int length = low + (high - low) / 2, end = offset + length;
            if (end < unit.text().length() && Character.isLowSurrogate(unit.text().charAt(end)) && Character.isHighSurrogate(unit.text().charAt(end - 1))) end--;
            if (end <= offset) { low = length + 1; continue; }
            if (fits(fragment(unit, offset, end), prompt, limit, inputBudget)) { best = end; low = length + 1; }
            else high = length - 1;
        }
        return best;
    }
    private static String fragment(Unit unit, int start, int end) {
        return "原始消息范围 [" + unit.first() + "," + unit.end() + ") 的连续资料片段，字符范围 [" + start + "," + end + ")：\n" + unit.text().substring(start, end);
    }
    private void add(List<CompactionPlan.SourceBlock> blocks, int first, int end, String text) {
        if (blocks.size() >= settings.maxCalls() - 1) throw failure("Map 加 Reduce 超过摘要总调用数上限，规划已停止");
        blocks.add(new CompactionPlan.SourceBlock(blocks.size(), first, end, text));
    }
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new io.github.jackfeicoder.codey.llm.LlmException(
                io.github.jackfeicoder.codey.llm.LlmException.Kind.INTERRUPTED, "Context compaction was interrupted.");
    }
    private static SummaryExecution.Failure failure(String message) { return SummaryExecution.failure(message); }
}
