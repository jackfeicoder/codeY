package io.github.jackfeicoder.codey.config;

import java.net.URI;
import java.time.Duration;

/**
 * 一套已校验的模型配置，由 ConfigLoader 组装后交给 LLM 客户端。
 * record 自动提供字段访问方法；构造器负责保证配置有效。
 *
 * @param baseUrl 服务商 API 基础地址，客户端在其后追加 /chat/completions
 * @param apiKey 认证密钥；NONE 模式下统一置为空，避免后续误用
 * @param model 服务商要求的模型 ID，与 Profile 别名是不同概念
 * @param timeout 一次请求等待完整响应的时间上限
 * @param authMode 是否使用 Bearer 认证
 */
public record LlmConfig(URI baseUrl, String apiKey, String model, Duration timeout, AuthMode authMode) {
    /** 兼容原四参数用法，默认采用 Bearer 认证。 */
    public LlmConfig(URI baseUrl, String apiKey, String model, Duration timeout) {
        this(baseUrl, apiKey, model, timeout, AuthMode.BEARER);
    }

    /** 紧凑构造器：校验地址、认证、模型与超时，字段赋值由 record 完成。 */
    public LlmConfig {
        if (baseUrl == null || baseUrl.getHost() == null
                || !("https".equalsIgnoreCase(baseUrl.getScheme())
                || "http".equalsIgnoreCase(baseUrl.getScheme()))
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new ConfigException("CODEY_LLM_BASE_URL must be an HTTP(S) base URL without credentials, query or fragment.");
        }
        if (authMode == null) {
            throw new ConfigException("CODEY_LLM_AUTH must be bearer or none.");
        }
        if (authMode == AuthMode.BEARER
                && (apiKey == null || apiKey.isBlank() || "YOUR_API_KEY".equals(apiKey))) {
            throw new ConfigException("CODEY_LLM_API_KEY is required; replace the example placeholder.");
        }
        if (authMode == AuthMode.NONE) {
            // 无认证模式不保留输入的密钥，让后续客户端只按认证方式发送请求。
            apiKey = "";
        }
        if (model == null || model.isBlank() || "YOUR_MODEL".equals(model)) {
            throw new ConfigException("CODEY_LLM_MODEL is required; replace the example placeholder.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new ConfigException("CODEY_LLM_TIMEOUT_SECONDS must be a positive integer.");
        }
    }

    /** 覆盖 record 默认输出，避免打印配置对象时泄露 API Key。 */
    @Override
    public String toString() {
        return "LlmConfig[baseUrl=" + baseUrl + ", apiKey=<redacted>, model="
                + model + ", timeout=" + timeout + ", authMode=" + authMode + "]";
    }
}
