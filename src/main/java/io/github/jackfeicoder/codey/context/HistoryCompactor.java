package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.config.CompactionSettings;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.prompt.PromptLoader;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolDefinition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** 自适应摘要协调：保护近期原文，规划 Single/MapReduce，全部成功并验收后才更新派生状态。 */
public final class HistoryCompactor {
    /** 失败/跳过返回原视图；摘要调用独立于 Agent 的任务请求次数。 */
    public record Outcome(ContextSnapshot context, boolean changed, int summaryCalls, String reason) { }
    /** 当前选中模型的客户端，摘要独立走非流式调用。 */
    private final LlmClient client;
    /** 使用当前模型的窗口、单次输出能力与安全余量，摘要不受普通回答预留额度限制。 */
    private final ContextBudgetPolicy budget;
    /** 启用、水位、保留轮次与调用/时间限制。 */
    private final CompactionSettings settings;
    /** 候选与原请求共用同一组装及估算算法。 */
    private final ContextBuilder builder;
    /** 只报告过程与用量，不输出历史或摘要全文。 */
    private final DebugPrinter debug;
    /** 分块选择及摘要正文校验使用的本地估算器。 */
    private final TokenEstimator estimator = new TokenEstimator();

    public HistoryCompactor(LlmClient client, ContextBudgetPolicy budget, CompactionSettings settings,
                            ContextBuilder builder, DebugPrinter debug) {
        this.client = Objects.requireNonNull(client); this.budget = Objects.requireNonNull(budget);
        this.settings = Objects.requireNonNull(settings); this.builder = Objects.requireNonNull(builder); this.debug = Objects.requireNonNull(debug);
    }

    /** force 供 /compact 使用，仍受容量、完整轮次、调用数、超时和输出预算约束。 */
    public Outcome prepare(Session session, List<Message> currentTurn, List<ToolDefinition> tools,
                           WorkMode mode, int step, int maxSteps, boolean force) {
        var history = session.messages(); var previous = session.compaction();
        var original = builder.build(history, currentTurn, tools, mode, step, maxSteps, previous);
        var assessment = budget.evaluate(original);
        if (!settings.enabled()) return skipped(original, "压缩已关闭");
        if (assessment.inputBudget() == null) return skipped(original, "模型容量未知，无法安全计算摘要请求预算");
        long inputBudget = assessment.inputBudget();
        long trigger = fraction(inputBudget, settings.triggerRatio(), RoundingMode.CEILING);
        long target = fraction(inputBudget, settings.targetRatio(), RoundingMode.FLOOR);
        // 期望水位不是拒绝线：额外允许输入预算的 5%，但绝不超过硬输入预算。
        long tolerance = fraction(inputBudget, new BigDecimal("0.05"), RoundingMode.CEILING);
        long softLimit = target + Math.min(inputBudget - target, tolerance);
        if (!force && original.tokens().total() < trigger) return skipped(original, "未达到压缩水位");

        var raw = history.subList(1, history.size());
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < raw.size(); i++) if (raw.get(i).role() == MessageRole.USER) starts.add(i);
        int covered = previous == null ? 0 : previous.coveredMessages();
        int kept = settings.keepRecentTurns();
        int cut = Math.max(covered, cutoff(starts, kept));
        long fixed = fixedTokens(history, raw, cut, currentTurn, tools, mode, step, maxSteps);
        // 优先保留 3 轮，适度超过期望水位也不拆组；连最小摘要都放不下才降到 2 轮。
        if (kept == 3 && fixed + 128 + 256 > softLimit && starts.size() > 2) {
            kept = 2; cut = Math.max(covered, cutoff(starts, kept));
            fixed = fixedTokens(history, raw, cut, currentTurn, tools, mode, step, maxSteps);
        }
        if (cut == 0) return skipped(original, "没有可摘要的旧轮次，近期完整消息与当前任务保持原样");
        // 为包装留 256 个估算 token；先把 20% 内剩余空间全部提供给摘要，不要求用满。
        long targetSpace = target - fixed - 256;
        long availableSpace = targetSpace >= 128 ? targetSpace : softLimit - fixed - 256;
        long limit = Math.min(Integer.MAX_VALUE, availableSpace);
        if (settings.summaryMaxTokens() > 0) limit = Math.min(limit, settings.summaryMaxTokens());
        if (budget.maxOutputTokens() != null) limit = Math.min(limit, budget.maxOutputTokens());
        if (limit < 128) return skipped(original, "近期消息、当前任务或模型输出限制使摘要空间不足；完整原文保持不变");
        int outputLimit = (int) limit;
        if (previous != null && cut == covered && !force && estimator.textTokens(previous.summary().json()) <= outputLimit)
            return skipped(original, "相同历史前缀已压缩，不重复生成摘要");

        var execution = new SummaryExecution(client, settings, debug);
        debug.compaction("开始：估算 " + original.tokens().total() + " tok，保留最近 " + (starts.size() - countCoveredTurns(starts, cut)) + " 个完整用户轮次。");
        debug.compaction("空间分配：期望总量≤" + target + " tok，容许总量≤" + softLimit + " tok，固定/近期/当前任务≈"
                + fixed + " tok，摘要输出上限=" + outputLimit + " tok（无需用满）；模型单次输出上限="
                + (budget.maxOutputTokens() == null ? "未知，可在 models.json 配置 maxOutputTokens" : budget.maxOutputTokens()) + "。");
        try (execution) {
            String prompt = PromptLoader.loadCompact(), reducePrompt = PromptLoader.loadCompactReduce();
            var plan = new CompactionPlanner(budget, settings).plan(raw.subList(covered, cut), covered,
                    previous == null ? null : previous.summary(), outputLimit, prompt, reducePrompt);
            execution.remaining();
            debug.compaction("策略：" + plan.strategy() + "；摘要来源≈" + plan.sourceTokens() + " tok；计划调用 " + plan.expectedCalls() + " 次。");
            SummaryDocument document = plan.strategy() == CompactionPlan.Strategy.SINGLE
                    ? new SingleSummaryStrategy().execute(plan, execution)
                    : new MapReduceSummaryStrategy().execute(plan, execution, prompt, reducePrompt);
            execution.remaining();
            var state = new CompactionState(cut, CompactionState.fingerprint(raw.subList(0, cut)), document, Instant.now().toString());
            var candidate = builder.build(history, currentTurn, tools, mode, step, maxSteps, state);
            if (candidate.tokens().total() >= original.tokens().total() || candidate.tokens().total() > softLimit || !budget.evaluate(candidate).canSend())
                throw SummaryExecution.failure("压缩后仍超限或没有缩小请求，候选已放弃");
            execution.remaining(); // 指纹和最终组装耗时也计入总时限，迟到的候选不应用。
            session.applyCompaction(state); // Map 子摘要不应用；只提交一次最终派生状态。
            debug.compaction("完成：" + original.tokens().total() + " → " + candidate.tokens().total() + " tok；"
                    + (candidate.tokens().total() <= target ? "已达到期望水位。" : "已缩小且在容许范围内。"));
            return new Outcome(candidate, true, execution.calls(), "压缩成功");
        } catch (LlmException failed) {
            if (failed.kind() == LlmException.Kind.INTERRUPTED) throw failed;
            String reason = "摘要模型调用失败：" + failed.kind(); debug.compaction(reason + "；保留原摘要与完整历史。");
            return new Outcome(original, false, execution.calls(), reason);
        } catch (RuntimeException failed) {
            String reason = failed instanceof SummaryExecution.Failure ? failed.getMessage() : "摘要结构或资源校验失败";
            debug.compaction(reason + "；保留原摘要与完整历史。");
            return new Outcome(original, false, execution.calls(), reason);
        }
    }

    /** 计算不能用旧历史摘要替代的固定部分，为摘要正文留空间。 */
    private long fixedTokens(List<Message> history, List<Message> raw, int cut, List<Message> current,
                             List<ToolDefinition> tools, WorkMode mode, int step, int maxSteps) {
        var tail = new ArrayList<Message>(); tail.add(history.get(0)); tail.addAll(raw.subList(cut, raw.size()));
        return builder.build(tail, current, tools, mode, step, maxSteps).tokens().total();
    }
    private static int cutoff(List<Integer> starts, int keep) { return starts.size() > keep ? starts.get(starts.size() - keep) : 0; }
    private static long countCoveredTurns(List<Integer> starts, int cut) { return starts.stream().filter(index -> index < cut).count(); }
    private static long fraction(long value, BigDecimal ratio, RoundingMode rounding) {
        return BigDecimal.valueOf(value).multiply(ratio).setScale(0, rounding).longValueExact();
    }
    private static Outcome skipped(ContextSnapshot snapshot, String reason) { return new Outcome(snapshot, false, 0, reason); }
}
