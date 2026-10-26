package io.github.jackfeicoder.codey.config;

/** 当前模型的本地容量资料，独立于密钥和 HTTP 请求；null 表示尚未配置。 */
public record ModelCapabilities(Long contextWindow, Long outputReserveTokens, Long safetyMarginTokens) {
    /** 旧三字段 models.json 和 Java 构造继续使用全局预算默认值。 */
    public ModelCapabilities(Long contextWindow) { this(contextWindow, null, null); }

    /** 覆盖值是本地预算参数，不宣称是厂商最大输出，也不发送到 HTTP。 */
    public ModelCapabilities {
        if (contextWindow != null && contextWindow <= 0) {
            throw new ConfigException("Model contextWindow must be a positive integer.");
        }
        if (outputReserveTokens != null && outputReserveTokens <= 0) throw new ConfigException("Model outputReserveTokens must be a positive integer.");
        if (safetyMarginTokens != null && safetyMarginTokens < 0) throw new ConfigException("Model safetyMarginTokens must be a nonnegative integer.");
        if (contextWindow != null && ((outputReserveTokens != null && outputReserveTokens >= contextWindow)
                || (safetyMarginTokens != null && safetyMarginTokens >= contextWindow)
                || (outputReserveTokens != null && safetyMarginTokens != null
                && safetyMarginTokens >= contextWindow - outputReserveTokens))) {
            throw new ConfigException("Model budget reserves must leave positive input space.");
        }
    }

    /** 缺文件或没有匹配条目时保留未知状态，兼容已有模型配置。 */
    public static ModelCapabilities unknown() { return new ModelCapabilities(null); }

    /** 已知值来自显式配置，尚未查询厂商 API 或向模型提问。 */
    public String source() { return contextWindow == null ? "未配置" : "models.json（本地配置）"; }
}
