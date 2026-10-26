package io.github.jackfeicoder.codey.context;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.*;

/** 有界滚动摘要：完整旧任务进入摘要，近期 3/2 轮与当前任务保留；只在候选验收后提交状态。 */
public final class HistoryCompactor {
    /** 失败/跳过返回原视图；摘要调用独立于 Agent 的任务请求次数。 */
    public record Outcome(ContextSnapshot context, boolean changed, int summaryCalls, String reason) { }
    /** 只序列化历史资料，不处理凭证或业务 HTTP。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 当前选中模型的客户端，摘要独立走非流式调用。 */
    private final LlmClient client;
    /** 使用当前模型的输入预算、输出预留与安全余量。 */
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
        if (!force && original.tokens().total() < trigger) return skipped(original, "未达到压缩水位");

        var raw = history.subList(1, history.size());
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < raw.size(); i++) if (raw.get(i).role() == MessageRole.USER) starts.add(i);
        int covered = previous == null ? 0 : previous.coveredMessages();
        int kept = settings.keepRecentTurns();
        int cut = Math.max(covered, cutoff(starts, kept));
        long fixed = fixedTokens(history, raw, cut, currentTurn, tools, mode, step, maxSteps);
        long maxSummary = Math.min(settings.summaryMaxTokens(), assessment.outputReserveTokens());
        // 优先保留 3 轮；若目标空间不足，按完整用户轮次降到 2 轮，不拆工具组。
        if (kept == 3 && fixed + maxSummary + 256 > target && starts.size() > 2) {
            kept = 2; cut = Math.max(covered, cutoff(starts, kept));
            fixed = fixedTokens(history, raw, cut, currentTurn, tools, mode, step, maxSteps);
        }
        if (cut == 0) return skipped(original, "没有可摘要的旧轮次，近期完整消息与当前任务保持原样");
        // 为摘要包装标记留 256 个估算 token。40% 是目标，固定内容过大时以硬输入预算为底线。
        long hardSpace = inputBudget - fixed - 256;
        long targetSpace = target - fixed - 256;
        long limit = Math.min(maxSummary, hardSpace);
        if (targetSpace >= 128) limit = Math.min(limit, targetSpace);
        if (limit < 128) return skipped(original, "固定内容、近期消息或当前任务过大，摘要没有足够空间");
        int outputLimit = (int) limit;
        if (previous != null && cut == covered && !force && estimator.textTokens(previous.summary().json()) <= outputLimit)
            return skipped(original, "相同历史前缀已压缩，不重复生成摘要");

        int calls = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.timeoutSeconds());
        ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
            var thread = new Thread(action, "codey-compaction"); thread.setDaemon(true); return thread;
        });
        debug.compaction("开始：估算 " + original.tokens().total() + " tok，保留最近 " + (starts.size() - countCoveredTurns(starts, cut)) + " 个完整用户轮次。");
        try {
            String prompt = PromptLoader.loadCompact();
            var sourceNode = MAPPER.createObjectNode();
            sourceNode.set("previousSummary", MAPPER.valueToTree(previous == null ? null : previous.summary()));
            sourceNode.set("newMessages", MAPPER.valueToTree(raw.subList(covered, cut)));
            String source = MAPPER.writeValueAsString(sourceNode);
            SummaryDocument rolling = null;
            int offset = 0;
            while (offset < source.length()) {
                if (calls >= settings.maxCalls()) throw failure("摘要调用次数达到上限，候选已放弃");
                remaining(deadline);
                String prior = rolling == null ? "null" : rolling.json();
                int end = fitEnd(source, offset, prompt, prior, outputLimit, inputBudget);
                if (end <= offset) throw failure("摘要指令或已有摘要过大，单次摘要请求放不下");
                LlmRequest request = summaryRequest(prompt, prior, source.substring(offset, end), offset, outputLimit);
                remaining(deadline);
                calls++;
                debug.compaction("第 " + calls + " 次摘要请求（非流式、无工具）。");
                Future<LlmResponse> pending = worker.submit(() -> client.generate(request));
                LlmResponse response;
                try { response = pending.get(remaining(deadline), TimeUnit.NANOSECONDS); }
                catch (TimeoutException timeout) { pending.cancel(true); throw failure("摘要总超时，候选已放弃"); }
                catch (InterruptedException interrupted) {
                    pending.cancel(true); Thread.currentThread().interrupt();
                    throw new LlmException(LlmException.Kind.INTERRUPTED, "Context compaction was interrupted.");
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof LlmException llm) throw llm;
                    throw failure("摘要请求失败，候选已放弃");
                }
                if (response == null || !"stop".equals(response.finishReason()) || !response.toolCalls().isEmpty())
                    throw failure("摘要响应不完整或包含工具调用");
                rolling = SummaryDocument.parse(response.text());
                if (estimator.textTokens(rolling.json()) > outputLimit) throw failure("摘要超过 Token 上限，候选已放弃");
                offset = end;
            }
            remaining(deadline);
            var state = new CompactionState(cut, CompactionState.fingerprint(raw.subList(0, cut)), rolling, Instant.now().toString());
            var candidate = builder.build(history, currentTurn, tools, mode, step, maxSteps, state);
            if (candidate.tokens().total() >= original.tokens().total() || !budget.evaluate(candidate).canSend())
                throw failure("压缩后仍超限或没有缩小请求，候选已放弃");
            session.applyCompaction(state); // 只有所有分块成功、结构和最终预算通过后才替换。
            debug.compaction("完成：" + original.tokens().total() + " → " + candidate.tokens().total() + " tok；"
                    + (candidate.tokens().total() <= target ? "已达到目标水位。" : "已缩小且符合硬预算；保留内容使目标水位暂未达到。"));
            return new Outcome(candidate, true, calls, "压缩成功");
        } catch (LlmException failed) {
            if (failed.kind() == LlmException.Kind.INTERRUPTED) throw failed;
            String reason = "摘要模型调用失败：" + failed.kind(); debug.compaction(reason + "；保留原摘要与完整历史。");
            return new Outcome(original, false, calls, reason);
        } catch (java.io.IOException | RuntimeException failed) {
            String reason = failed instanceof CompactionFailure ? failed.getMessage() : "摘要结构或资源校验失败";
            debug.compaction(reason + "；保留原摘要与完整历史。");
            return new Outcome(original, false, calls, reason);
        } finally { worker.shutdownNow(); }
    }

    /** 二分选择当前模型能接收的文本片段；UTF-16 分界避开代理对，不要求片段本身是完整 JSON。 */
    private int fitEnd(String source, int offset, String prompt, String prior, int limit, long inputBudget) {
        int low = 1, high = source.length() - offset, best = offset;
        while (low <= high) {
            int length = low + (high - low) / 2;
            int end = offset + length;
            if (end < source.length() && Character.isLowSurrogate(source.charAt(end)) && Character.isHighSurrogate(source.charAt(end - 1))) end--;
            if (end <= offset) { low = length + 1; continue; }
            var request = summaryRequest(prompt, prior, source.substring(offset, end), offset, limit);
            long tokens = request.messages().stream().mapToLong(estimator::messageBodyTokens).sum() + 15;
            if (tokens <= inputBudget) { best = end; low = length + 1; } else high = length - 1;
        }
        return best;
    }

    /** 包装旧资料与片段位置，显式禁止工具并限制摘要输出。 */
    private static LlmRequest summaryRequest(String prompt, String prior, String fragment, int offset, int limit) {
        String input = "本次输出上限约 " + limit + " Token。\n已整理的滚动摘要 JSON：\n" + prior
                + "\n新增历史数据片段，起始字符位置 " + offset + "：\n" + fragment;
        return new LlmRequest(List.of(Message.system(prompt), Message.user(input)), List.of(), false, limit);
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
    private static long remaining(long deadline) {
        if (Thread.currentThread().isInterrupted()) throw new LlmException(LlmException.Kind.INTERRUPTED, "Context compaction was interrupted.");
        long left = deadline - System.nanoTime(); if (left <= 0) throw failure("摘要总超时，候选已放弃"); return left;
    }
    private static Outcome skipped(ContextSnapshot snapshot, String reason) { return new Outcome(snapshot, false, 0, reason); }
    private static CompactionFailure failure(String reason) { return new CompactionFailure(reason); }
    private static final class CompactionFailure extends RuntimeException { private CompactionFailure(String reason) { super(reason); } }
}
