package io.github.jackfeicoder.codey.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 主线程工具注册表：稳定保存定义/实现/调度能力；读取工作线程只使用已固定的注册项快照。 */
public final class ToolRegistry {
    /** 按注册顺序保存，定义展示顺序确定；名称区分大小写。 */
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();
    /** 宿主扩展的任务清理器；正常、预算耗尽和异常退出均调用。 */
    private final java.util.List<Runnable> taskCleanups = new java.util.ArrayList<>();

    /** 动态工具整批校验后注册，冲突时不留下半批工具。 */
    public void registerAll(List<? extends Tool> batch) {
        ToolRegistry staging = new ToolRegistry();
        for (Tool tool : batch) {
            staging.register(tool);
            if (tools.containsKey(tool.definition().name())) throw new IllegalArgumentException("Tool registration conflict.");
        }
        tools.putAll(staging.tools);
    }

    /** 仅卸载相同实例，避免旧连接误删后续注册的新工具。 */
    public void unregister(Tool tool) {
        var entry = tools.get(tool.definition().name());
        if (entry != null && entry.implementation() == tool) tools.remove(tool.definition().name());
    }

    public void onTaskFinished(Runnable cleanup) { taskCleanups.add(Objects.requireNonNull(cleanup)); }
    public void removeTaskCleanup(Runnable cleanup) { taskCleanups.remove(cleanup); }
    public void finishTask() { for (Runnable cleanup : List.copyOf(taskCleanups)) cleanup.run(); }

    /** 注册时固定元数据，即使实现之后改变 definition() 也不改变已注册说明。 */
    public record RegisteredTool(ToolDefinition definition, Tool implementation, boolean requiresWorkspace, ToolAccess access,
                                 ToolExecutionMode executionMode) {
        public RegisteredTool(ToolDefinition definition,Tool implementation,boolean requiresWorkspace,ToolAccess access) {
            this(definition,implementation,requiresWorkspace,access,ToolExecutionMode.SERIAL);
        }
    }

    /** 返回 this 便于装配多个工具；重复名称明确报错，不静默覆盖。 */
    public ToolRegistry register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        ToolDefinition definition = Objects.requireNonNull(tool.definition(), "definition");
        RegisteredTool entry = new RegisteredTool(definition, tool, tool.requiresWorkspace(), Objects.requireNonNull(tool.access()),
                Objects.requireNonNull(tool.executionMode()));
        if (entry.executionMode() == ToolExecutionMode.PARALLEL_READ && entry.access() != ToolAccess.READ_ONLY)
            throw new IllegalArgumentException("Parallel execution requires a trusted read-only tool.");
        if (tools.putIfAbsent(definition.name(), entry) != null) {
            throw new IllegalArgumentException("Tool is already registered: " + definition.name());
        }
        return this;
    }

    /** 查找绑定的定义与实现，未知工具交给执行器产生错误结果。 */
    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 返回所有定义的只读快照，主要供装配检查使用。 */
    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }

    /** 根据宿主工作区状态筛选定义，后续发给模型时使用这份可用工具列表。 */
    public List<ToolDefinition> definitions(ToolContext context) {
        Objects.requireNonNull(context, "context");
        return tools.values().stream().filter(entry -> !entry.requiresWorkspace() || context.hasWorkspace())
                .filter(entry -> context.workMode() != io.github.jackfeicoder.codey.agent.WorkMode.PLAN || entry.access() == ToolAccess.READ_ONLY || entry.access()==ToolAccess.PLAN_CONTROL)
                .filter(entry -> entry.implementation().available(context))
                .map(RegisteredTool::definition).toList();
    }
}
