package io.github.jackfeicoder.codey.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 单线程工具注册表：稳定保存名称/定义/实现，拒绝重复注册，不执行工具。 */
public final class ToolRegistry {
    /** 按注册顺序保存，定义展示顺序确定；名称区分大小写。 */
    private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

    /** 注册时固定元数据，即使实现之后改变 definition() 也不改变已注册说明。 */
    public record RegisteredTool(ToolDefinition definition, Tool implementation, boolean requiresWorkspace, ToolAccess access) { }

    /** 返回 this 便于装配多个工具；重复名称明确报错，不静默覆盖。 */
    public ToolRegistry register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        ToolDefinition definition = Objects.requireNonNull(tool.definition(), "definition");
        RegisteredTool entry = new RegisteredTool(definition, tool, tool.requiresWorkspace(), Objects.requireNonNull(tool.access()));
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
                .filter(entry -> context.workMode() != io.github.jackfeicoder.codey.agent.WorkMode.PLAN || entry.access() == ToolAccess.READ_ONLY)
                .map(RegisteredTool::definition).toList();
    }
}
