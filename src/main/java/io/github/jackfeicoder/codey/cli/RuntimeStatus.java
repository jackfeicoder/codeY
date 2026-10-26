package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.PermissionMode;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.config.ModelCapabilities;

/** 被动状态模型，不保存 API Key，不影响工具权限；用量属于上次模型请求。 */
public final class RuntimeStatus {
    /** 左侧显示当前工作模式与审批预设，默认 ReAct + ask。 */
    private WorkMode workMode = WorkMode.REACT;
    private PermissionMode permissionMode = PermissionMode.ASK;
    /** 仅配置别名/模型 ID，不包含 URL、请求或凭证。 */
    private String model = "未选择模型";
    /** 可空，服务未报告时保持未知，不使用 totalTokens 代替。 */
    private Long lastInputTokens;
    /** 本地配置的模型最大上下文；尚未扣除输出预留，也不代表已实现预算保护。 */
    private Long contextWindow;
    public void modes(WorkMode work, PermissionMode permission) { workMode = work; permissionMode = permission; }
    public void model(String value) { model(value, ModelCapabilities.unknown()); }
    /** 切换时同时替换容量并清除旧用量，避免小模型沿用上一个大模型的上限。 */
    public void model(String value, ModelCapabilities capabilities) {
        model = value; contextWindow = capabilities.contextWindow(); lastInputTokens = null;
    }
    public void usage(LlmResponse.TokenUsage usage) { lastInputTokens = usage == null ? null : usage.promptTokens(); }
    public WorkMode workMode() { return workMode; }
    public PermissionMode permissionMode() { return permissionMode; }
    public String model() { return model; }
    public Long lastInputTokens() { return lastInputTokens; }
    public Long contextWindow() { return contextWindow; }
    public String contextWindowText() { return contextWindow == null ? "?" : contextWindow.toString(); }
    public String left() { return workMode.value() + " · " + permissionMode.value(); }
    public String right() {
        return model + " | 上次输入 " + (lastInputTokens == null ? "?" : lastInputTokens) + " tok / 上限 " + contextWindowText();
    }
}
