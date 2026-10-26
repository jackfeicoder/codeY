package io.github.jackfeicoder.codey.context;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ContextBudgetSettings;
import io.github.jackfeicoder.codey.config.ModelCapabilities;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 纯预算策略：窗口减去输出预留和安全余量；只判断，不裁剪消息或请求模型摘要。 */
public final class ContextBudgetPolicy {
    /** 80% 等比例是预警水位，超过输入预算才阻止；UNKNOWN 不伪造容量。 */
    public enum State { NORMAL, WARNING, EXCEEDED, UNKNOWN }

    /** 剩余空间 = 输入预算 - 估算输入，超限时为负；所有已知预算均为原始整数 Token。 */
    public record Assessment(State state, long estimatedInput, Long contextWindow, Long inputBudget,
                             long outputReserveTokens, long safetyMarginTokens,
                             Long warningThreshold, Long remainingTokens) {
        public boolean canSend() { return state != State.EXCEEDED; }
        public String label() {
            return switch (state) {
                case NORMAL -> "正常";
                case WARNING -> "接近输入预算";
                case EXCEEDED -> "超过输入预算";
                case UNKNOWN -> "容量未知，未执行容量检查";
            };
        }
    }

    private final Long window;
    private final Long inputBudget;
    private final long outputReserve;
    private final long safetyMargin;
    private final Long warningThreshold;

    /** 选择模型时固定策略并验证；模型覆盖值优先于全局设置，错误不推迟到 HTTP 调用。 */
    public ContextBudgetPolicy(ModelCapabilities model, ContextBudgetSettings settings) {
        Objects.requireNonNull(model, "model"); Objects.requireNonNull(settings, "settings");
        window = model.contextWindow();
        outputReserve = model.outputReserveTokens() == null ? settings.outputReserveTokens() : model.outputReserveTokens();
        safetyMargin = model.safetyMarginTokens() == null ? settings.safetyMarginTokens() : model.safetyMarginTokens();
        if (window == null) { inputBudget = null; warningThreshold = null; return; }
        // 先做减法与比较，避免 reserve + margin 在 Long 最大边界溢出。
        if (outputReserve >= window || safetyMargin >= window - outputReserve) {
            throw new ConfigException("Context output reserve plus safety margin must be smaller than contextWindow; adjust model overrides or CODEY_CONTEXT_* settings.");
        }
        inputBudget = window - outputReserve - safetyMargin;
        warningThreshold = BigDecimal.valueOf(inputBudget).multiply(settings.warningRatio())
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** 无容量的库调用仍估算但跳过容量检查，不在核心偷偷读取宿主环境。 */
    public static ContextBudgetPolicy unknown() {
        return new ContextBudgetPolicy(ModelCapabilities.unknown(), new ContextBudgetSettings());
    }

    public Assessment evaluate(ContextSnapshot context) { return evaluate(context.tokens().total()); }

    /** 等于输入预算仍允许发送；使用整数和 BigDecimal，不让 K/M 显示舍入参与判断。 */
    public Assessment evaluate(long estimatedInput) {
        if (estimatedInput < 0) throw new IllegalArgumentException("Estimated input must be nonnegative.");
        State state = inputBudget == null ? State.UNKNOWN : estimatedInput > inputBudget ? State.EXCEEDED
                : estimatedInput >= warningThreshold ? State.WARNING : State.NORMAL;
        return new Assessment(state, estimatedInput, window, inputBudget, outputReserve, safetyMargin,
                warningThreshold, inputBudget == null ? null : inputBudget - estimatedInput);
    }
}
