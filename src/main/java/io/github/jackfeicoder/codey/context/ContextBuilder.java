package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageHistory;
import io.github.jackfeicoder.codey.session.MessageRole;
import io.github.jackfeicoder.codey.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 统一组装普通 Agent 请求并估算输入；只创建请求视图，暂不压缩、裁剪或执行 token 预算拦截。 */
public final class ContextBuilder {
    /** 无远程依赖的估算器；未来精确分词可集中替换此处。 */
    private final TokenEstimator estimator = new TokenEstimator();

    /**
     * history 含首条基础 system 与成功历史；currentTurn 是当前 user 及已配对的工具组。
     * currentTurn 为空用于输入前预览；tools 由注册表按工作区/工作模式筛选，审批仍由权限模块执行。
     */
    public ContextSnapshot build(List<Message> history, List<Message> currentTurn, List<ToolDefinition> tools,
                                 WorkMode mode, int step, int maxSteps) {
        Objects.requireNonNull(mode, "mode");
        if (step < 1 || step > maxSteps) throw new IllegalArgumentException("Invalid context request step.");
        if (history == null || history.isEmpty() || history.get(0) == null
                || history.get(0).role() != MessageRole.SYSTEM || history.get(0).content().isBlank()) {
            throw new IllegalArgumentException("Context requires a base system message.");
        }
        MessageHistory.validate(history.subList(1, history.size()));
        validateCurrentTurn(currentTurn);
        String base = history.get(0).content();
        String runtime = runtimeInstructions(mode, step, maxSteps);
        var messages = new ArrayList<Message>();
        messages.add(Message.system(base + runtime));
        messages.addAll(history.subList(1, history.size()));
        messages.addAll(currentTurn);
        var request = new LlmRequest(messages, tools, step < maxSteps);
        long past = history.stream().skip(1).mapToLong(estimator::messageBodyTokens).sum();
        long user = 0, assistant = 0, results = 0;
        for (var message : currentTurn) {
            long count = estimator.messageBodyTokens(message);
            switch (message.role()) {
                case USER -> user += count;
                case ASSISTANT -> assistant += count;
                case TOOL -> results += count;
                default -> throw new IllegalArgumentException("Unexpected system message in current turn.");
            }
        }
        long definitions = request.tools().stream().mapToLong(estimator::toolTokens).sum();
        // 每条消息约 4 token 的角色/边界，加回复起始开销；none 控制字段也计入估算。
        long overhead = 4L * messages.size() + 3 + (!request.toolCallsAllowed() && !tools.isEmpty() ? 4 : 0);
        return new ContextSnapshot(request, new ContextSnapshot.TokenBreakdown(estimator.textTokens(base),
                estimator.textTokens(runtime), past, user, assistant, results, definitions, overhead));
    }

    /** 运行时指令只叠加在本次 system 副本，保持原有 PLAN 和请求轮次预算语义。 */
    private static String runtimeInstructions(WorkMode mode, int step, int maxSteps) {
        String runtime = mode == WorkMode.PLAN
                ? "\n\n[当前工作模式：PLAN]\n只读探索并输出计划，不修改文件、不执行副作用操作。用户可用 /mode react 切换后实施。" : "";
        int remaining = maxSteps - step + 1;
        if (remaining <= 3) {
            String instruction = step == maxSteps
                    ? "这是当前任务最后一次模型请求，工具调用已禁用。请依据已获得的信息直接回答，"
                    + "明确尚未完成或尚未确认的部分，不要声称未执行的操作已完成，不要再请求工具。"
                    : "当前任务最多允许 " + maxSteps + " 次模型请求，包括本次还剩 " + remaining
                    + " 次。请优先使用已有证据，避免重复探索；信息足够时立即回答，最后一次请求将禁用工具并用于总结。";
            runtime += "\n\n[运行时任务预算]\n" + instruction;
        }
        return runtime;
    }

    /** 请求边界允许尚无最终回答，但每组 assistant 调用必须已有且只有一个对应结果。 */
    private static void validateCurrentTurn(List<Message> messages) {
        if (messages == null) throw new IllegalArgumentException("Current turn is required.");
        if (messages.isEmpty()) return;
        Message first = messages.get(0);
        if (first == null || first.role() != MessageRole.USER || first.content().isBlank()) throw invalidTurn();
        var seen = new HashSet<String>();
        int index = 1;
        while (index < messages.size()) {
            Message assistant = messages.get(index++);
            if (assistant == null || assistant.role() != MessageRole.ASSISTANT || assistant.toolCalls().isEmpty()) throw invalidTurn();
            var pending = new HashSet<String>();
            for (var call : assistant.toolCalls()) {
                if (!seen.add(call.id())) throw invalidTurn();
                pending.add(call.id());
            }
            int size = pending.size();
            for (int result = 0; result < size; result++) {
                if (index >= messages.size()) throw invalidTurn();
                Message tool = messages.get(index++);
                if (tool == null || tool.role() != MessageRole.TOOL || !pending.remove(tool.toolCallId())) throw invalidTurn();
            }
        }
    }

    private static IllegalArgumentException invalidTurn() {
        return new IllegalArgumentException("Context requires one open user turn with paired tool results.");
    }
}
