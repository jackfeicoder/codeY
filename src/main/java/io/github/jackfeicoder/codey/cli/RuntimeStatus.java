package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.PermissionMode;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.config.ModelCapabilities;
import io.github.jackfeicoder.codey.context.ContextSnapshot;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 被动状态模型：优先展示上下文估算，另存最近实际输入用量；不影响工具权限。 */
public final class RuntimeStatus {
    /** 左侧显示当前工作模式与审批预设，默认 ReAct + ask。 */
    private WorkMode workMode = WorkMode.REACT;
    private PermissionMode permissionMode = PermissionMode.ASK;
    /** 仅配置别名/模型 ID，不包含 URL、请求或凭证。 */
    private String model = "未选择模型";
    /** 可空，服务未报告时保持未知，不使用 totalTokens 代替。 */
    private Long lastInputTokens;
    /** 本地配置的模型最大上下文；底栏仍展示总窗口，输入预算在 /context 单独列出。 */
    private Long contextWindow;
    /** 最近请求或输入前预览；统计不持久化，不加入模型请求。 */
    private ContextSnapshot context;
    private ContextBudgetPolicy.Assessment budget;
    public void modes(WorkMode work, PermissionMode permission) { workMode = work; permissionMode = permission; }
    public void model(String value) { model(value, ModelCapabilities.unknown()); }
    /** 切换时同时替换容量并清除旧用量，避免小模型沿用上一个大模型的上限。 */
    public void model(String value, ModelCapabilities capabilities) {
        model = value; contextWindow = capabilities.contextWindow(); lastInputTokens = null; context = null; budget = null;
    }
    public void usage(LlmResponse.TokenUsage usage) { lastInputTokens = usage == null ? null : usage.promptTokens(); }
    public WorkMode workMode() { return workMode; }
    public PermissionMode permissionMode() { return permissionMode; }
    public String model() { return model; }
    public Long lastInputTokens() { return lastInputTokens; }
    public Long contextWindow() { return contextWindow; }
    public void context(ContextSnapshot value) { context = value; budget = null; }
    public ContextSnapshot context() { return context; }
    public void budget(ContextBudgetPolicy.Assessment value) { budget = value; }
    public ContextBudgetPolicy.Assessment budget() { return budget; }
    public Long usedTokens() {
        if (context == null) return lastInputTokens;
        return context.tokens().total();
    }
    public String usedTokensText() { return (context == null ? "" : "≈") + formatTokens(usedTokens()); }
    public String contextWindowText() { return formatTokens(contextWindow); }
    /** 用原始整数计算占比，不使用已经缩写/舍入的 K/M 数值；未知保持未知。 */
    public String usagePercentText() {
        if (usedTokens() == null || contextWindow == null) return "?%";
        return BigDecimal.valueOf(usedTokens()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(contextWindow), 1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 十进制缩写：K=1000、M=1000000；K 保留最多一位、M 最多两位，不影响原始统计值。 */
    static String formatTokens(Long value) {
        if (value == null) return "?";
        if (value < 1000) return value.toString();
        BigDecimal count = BigDecimal.valueOf(value);
        if (value < 1000000) {
            BigDecimal kilo = count.divide(BigDecimal.valueOf(1000), 1, RoundingMode.HALF_UP);
            // 避免在百万边界附近舍入成 1000K。
            if (kilo.compareTo(BigDecimal.valueOf(1000)) < 0) return kilo.stripTrailingZeros().toPlainString() + "K";
        }
        return count.divide(BigDecimal.valueOf(1000000), 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "M";
    }
    public String left() { return workMode.value() + " · " + permissionMode.value(); }
    public String right() {
        return model + " | 已用 " + usedTokensText() + " / " + contextWindowText() + " (" + usagePercentText() + ")";
    }
}
