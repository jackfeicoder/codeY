package io.github.jackfeicoder.codey.config;

/** 当前模型的本地容量资料，独立于密钥和 HTTP 请求；null 表示尚未配置。 */
public record ModelCapabilities(Long contextWindow) {
    /** 首版只接入最大上下文；后续预算使用此值，不在这里推测模型规格。 */
    public ModelCapabilities {
        if (contextWindow != null && contextWindow <= 0) {
            throw new ConfigException("Model contextWindow must be a positive integer.");
        }
    }

    /** 缺文件或没有匹配条目时保留未知状态，兼容已有模型配置。 */
    public static ModelCapabilities unknown() { return new ModelCapabilities(null); }

    /** 已知值来自显式配置，尚未查询厂商 API 或向模型提问。 */
    public String source() { return contextWindow == null ? "未配置" : "models.json（本地配置）"; }
}
