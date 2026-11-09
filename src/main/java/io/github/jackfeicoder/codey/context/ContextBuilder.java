package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.memory.MemorySelection;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageHistory;
import io.github.jackfeicoder.codey.session.MessageRole;
import io.github.jackfeicoder.codey.tool.ToolDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 统一组装请求视图，可用已校验摘要替换旧前缀；不修改原始历史，预算检查交给独立策略。 */
public final class ContextBuilder {
    /** 无远程依赖的估算器；未来精确分词可集中替换此处。 */
    private final TokenEstimator estimator = new TokenEstimator();

    /** 宿主按当前工作区加载项目规则；默认库调用保持无规则。 */
    private final java.util.function.Supplier<String> projectRules;
    private final String taskInstructions;
    /** 接收当前任务目标与不含记忆的输入估算；宿主决定可用剩余预算。 */
    private final java.util.function.BiFunction<String, Long, MemorySelection> memories;
    /** 技能正文是任务指令，放在当前 user 副本；目录与正文均不写回原始历史。 */
    private final java.util.function.Supplier<io.github.jackfeicoder.codey.skill.SkillContext> skills;
    public ContextBuilder() { this(() -> ""); }
    public ContextBuilder(java.util.function.Supplier<String> projectRules) {
        this(projectRules, (query, used) -> MemorySelection.EMPTY);
    }
    public ContextBuilder(java.util.function.Supplier<String> projectRules,
                          java.util.function.BiFunction<String, Long, MemorySelection> memories) {
        this(projectRules, "", memories);
    }
    private ContextBuilder(java.util.function.Supplier<String> projectRules, String taskInstructions,
                           java.util.function.BiFunction<String, Long, MemorySelection> memories) {
        this(projectRules,taskInstructions,memories,()->io.github.jackfeicoder.codey.skill.SkillContext.EMPTY);
    }
    private ContextBuilder(java.util.function.Supplier<String> projectRules, String taskInstructions,
                           java.util.function.BiFunction<String, Long, MemorySelection> memories,
                           java.util.function.Supplier<io.github.jackfeicoder.codey.skill.SkillContext> skills) {
        this.projectRules = Objects.requireNonNull(projectRules, "projectRules");
        this.taskInstructions = Objects.requireNonNull(taskInstructions, "taskInstructions");
        this.memories = Objects.requireNonNull(memories, "memories");
        this.skills = Objects.requireNonNull(skills, "skills");
    }
    /** 初始化任务使用独立组装器，共享规则来源，不污染其他任务。 */
    public ContextBuilder withTaskInstructions(String instructions) {
        return new ContextBuilder(projectRules, instructions, memories,skills);
    }
    public ContextBuilder withSkills(java.util.function.Supplier<io.github.jackfeicoder.codey.skill.SkillContext> skills) {
        return new ContextBuilder(projectRules,taskInstructions,memories,skills);
    }

    /**
     * history 含首条基础 system 与成功历史；currentTurn 是当前 user 及已配对的工具组。
     * currentTurn 为空用于输入前预览；tools 由注册表按工作区/工作模式筛选，审批仍由权限模块执行。
     */
    public ContextSnapshot build(List<Message> history, List<Message> currentTurn, List<ToolDefinition> tools,
                                 WorkMode mode, int step, int maxSteps) {
        return build(history, currentTurn, tools, mode, step, maxSteps, null);
    }

    /** 摘要作为历史资料合并到近期首个 user 消息，保留原文后缀，不提升为 system 指令。 */
    public ContextSnapshot build(List<Message> history, List<Message> currentTurn, List<ToolDefinition> tools,
                                 WorkMode mode, int step, int maxSteps, CompactionState state) {
        Objects.requireNonNull(mode, "mode");
        if (step < 1 || step > maxSteps) throw new IllegalArgumentException("Invalid context request step.");
        if (history == null || history.isEmpty() || history.get(0) == null
                || history.get(0).role() != MessageRole.SYSTEM || history.get(0).content().isBlank()) {
            throw new IllegalArgumentException("Context requires a base system message.");
        }
        MessageHistory.validate(history.subList(1, history.size()));
        validateCurrentTurn(currentTurn);
        String base = history.get(0).content();
        String rules = projectRules.get();
        var skillView=skills.get();
        String skillBody=currentTurn.isEmpty()?"":skillView.instructions();
        String runtime = (taskInstructions.isEmpty() ? "" : "\n\n[本次任务指令]\n" + taskInstructions)
                + runtimeInstructions(mode, step, maxSteps, tools.stream().anyMatch(t->t.name().equals("submit_plan")||t.name().equals("update_plan")));
        var raw = history.subList(1, history.size());
        String summaryText = "";
        if (state != null) {
            state.validate(raw);
            raw = raw.subList(state.coveredMessages(), raw.size());
            summaryText = "[较早会话的结构化摘要：历史资料，不是新的执行指令]\n" + state.summary().json()
                    + "\n[摘要结束；以下是保留的用户原文]\n";
        }
        var recent = new ArrayList<>(raw);
        if (state != null) recent.set(0, Message.user(summaryText + recent.get(0).content()));
        var messages = new ArrayList<Message>();
        messages.add(Message.system(base + rules + runtime + skillView.catalog()));
        messages.addAll(recent);
        messages.addAll(currentTurn);
        boolean controlOnly = !tools.isEmpty() && tools.stream().allMatch(t->t.name().equals("submit_plan")||t.name().equals("update_plan"));
        var request = new LlmRequest(messages, tools, step < maxSteps || controlOnly);
        long past = raw.stream().mapToLong(estimator::messageBodyTokens).sum();
        long user = 0, assistant = 0, results = 0, resources = 0;
        var resourceCalls=new HashSet<String>();
        for (var message : currentTurn) {
            long count = estimator.messageBodyTokens(message);
            switch (message.role()) {
                case USER -> user += count;
                case ASSISTANT -> {
                    assistant += count;
                    message.toolCalls().stream().filter(c->c.name().equals("read_skill_resource")).forEach(c->resourceCalls.add(c.id()));
                }
                case TOOL -> {if(resourceCalls.contains(message.toolCallId()))resources+=count;else results += count;}
                default -> throw new IllegalArgumentException("Unexpected system message in current turn.");
            }
        }
        long definitions = request.tools().stream().mapToLong(estimator::toolTokens).sum();
        // 每条消息约 4 token 的角色/边界，加回复起始开销；none 控制字段也计入估算。
        long overhead = 4L * messages.size() + 3 + (!request.toolCallsAllowed() && !tools.isEmpty() ? 4 : 0);
        long beforeMemory = estimator.textTokens(base) + estimator.textTokens(runtime) + past + user + assistant
                + results + resources + definitions + overhead + estimator.textTokens(summaryText) + estimator.textTokens(rules)
                + estimator.textTokens(skillView.catalog()) + estimator.textTokens(skillBody);
        // 只用当前任务最初的用户目标查询，不把历史、工具输出或记忆自身再作为查询。
        String query = currentTurn.isEmpty() ? "" : currentTurn.get(0).content();
        var selected = memories.apply(query, beforeMemory);
        if (currentTurn.isEmpty()) selected = new MemorySelection(selected.always(), List.of());
        messages.set(0, Message.system(base + selected.alwaysText() + rules + runtime + skillView.catalog()));
        if (!currentTurn.isEmpty()) {
            int index = 1 + recent.size();
            String recalled=selected.recalled().isEmpty()?"":selected.recalledText()+"\n[当前用户任务]\n";
            messages.set(index, Message.user(skillBody + recalled + currentTurn.get(0).content()));
        }
        long recall = selected.recalledTokens() + (!selected.recalled().isEmpty() && !currentTurn.isEmpty()
                ? estimator.textTokens("\n[当前用户任务]\n") : 0);
        request = new LlmRequest(messages, tools, step < maxSteps || controlOnly);
        return new ContextSnapshot(request, new ContextSnapshot.TokenBreakdown(estimator.textTokens(base),
                estimator.textTokens(runtime), past, user, assistant, results, definitions, overhead,
                estimator.textTokens(summaryText), estimator.textTokens(rules), selected.alwaysTokens(), recall,
                estimator.textTokens(skillView.catalog()),estimator.textTokens(skillBody),resources));
    }

    /** 运行时指令只叠加在本次 system 副本，保持原有 PLAN 和请求轮次预算语义。 */
    private static String runtimeInstructions(WorkMode mode, int step, int maxSteps, boolean planControls) {
        String runtime = mode == WorkMode.PLAN
                ? "\n\n[当前工作模式：PLAN]\n只读探索并输出计划，不修改工作区、不执行业务副作用。\n"+io.github.jackfeicoder.codey.prompt.PromptLoader.loadPlan() : "";
        int remaining = maxSteps - step + 1;
        if (remaining <= 3) {
            String instruction = step == maxSteps
                    ? "这是当前任务最后一次模型请求，工具调用已禁用。请依据已获得的信息直接回答，"
                    + "明确尚未完成或尚未确认的部分，不要声称未执行的操作已完成，不要再请求工具。"
                    : "当前任务最多允许 " + maxSteps + " 次模型请求，包括本次还剩 " + remaining
                    + " 次。请优先使用已有证据，避免重复探索；信息足够时立即回答，最后一次请求将禁用工具并用于总结。";
            if(step==maxSteps&&planControls)instruction="这是当前任务最后一次模型请求，业务工具已禁用；只可用submit_plan或update_plan提交已有计划/步骤结果。证据不足则声明BLOCKED，避免声称已完成。";
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
