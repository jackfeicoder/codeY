package io.github.jackfeicoder.codey.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 上下文预算的全局默认值；模型可单独覆盖 Token 预留，配置不含认证信息。 */
public record ContextBudgetSettings(long outputReserveTokens, long safetyMarginTokens, BigDecimal warningRatio) {
    public static final long DEFAULT_OUTPUT_RESERVE = 8192;
    public static final long DEFAULT_SAFETY_MARGIN = 4096;
    public static final BigDecimal DEFAULT_WARNING_RATIO = new BigDecimal("0.8");

    public ContextBudgetSettings() { this(DEFAULT_OUTPUT_RESERVE, DEFAULT_SAFETY_MARGIN, DEFAULT_WARNING_RATIO); }

    /** 不接受静默回退；比例限制小数位，避免极端精度使预算计算失去边界。 */
    public ContextBudgetSettings {
        if (outputReserveTokens <= 0) throw new ConfigException("CODEY_CONTEXT_OUTPUT_RESERVE_TOKENS must be a positive integer.");
        if (safetyMarginTokens < 0) throw new ConfigException("CODEY_CONTEXT_SAFETY_MARGIN_TOKENS must be a nonnegative integer.");
        if (warningRatio == null || warningRatio.scale() < 0 || warningRatio.scale() > 6
                || warningRatio.signum() <= 0 || warningRatio.compareTo(BigDecimal.ONE) > 0) {
            throw new ConfigException("CODEY_CONTEXT_WARNING_RATIO must be within (0, 1], with at most six decimal places.");
        }
        warningRatio = warningRatio.stripTrailingZeros();
    }

    /** 环境覆盖 .env，共用配置目录；修改后重启，不在一次任务中途变更预算。 */
    public static ContextBudgetSettings load(Path startup, Map<String, String> environment) {
        var values = new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup, environment).resolve(".env")));
        values.putAll(environment);
        BigDecimal ratio;
        try {
            String raw = values.getOrDefault("CODEY_CONTEXT_WARNING_RATIO", "0.8").strip();
            if (!raw.matches("(?:0\\.[0-9]{1,6}|1(?:\\.0{1,6})?)")) throw new NumberFormatException();
            ratio = new BigDecimal(raw);
        } catch (NumberFormatException invalid) {
            throw new ConfigException("CODEY_CONTEXT_WARNING_RATIO must be a decimal within (0, 1], with at most six decimal places.");
        }
        return new ContextBudgetSettings(integer(values, "CODEY_CONTEXT_OUTPUT_RESERVE_TOKENS", DEFAULT_OUTPUT_RESERVE),
                integer(values, "CODEY_CONTEXT_SAFETY_MARGIN_TOKENS", DEFAULT_SAFETY_MARGIN), ratio);
    }

    /** 只显示字段名和预期类型，不回显配置原文。 */
    private static long integer(Map<String, String> values, String name, long fallback) {
        if (!values.containsKey(name)) return fallback;
        try { return Long.parseLong(values.get(name).strip()); }
        catch (NumberFormatException invalid) { throw new ConfigException(name + " must be an integer within its allowed range."); }
    }
}
