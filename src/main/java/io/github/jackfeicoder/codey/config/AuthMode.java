package io.github.jackfeicoder.codey.config;

/** 模型服务的认证方式，决定请求是否携带密钥。 */
public enum AuthMode {
    /** 将 API Key 放在 Authorization: Bearer 请求头中。 */
    BEARER,
    /** 不发送认证头，适用于明确无需认证的本地服务。 */
    NONE
}
