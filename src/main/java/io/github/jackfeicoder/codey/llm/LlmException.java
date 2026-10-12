package io.github.jackfeicoder.codey.llm;

import java.util.Objects;

/** 分类错误，不携带原始请求、服务商响应或可能包含密钥的底层异常。 */
public final class LlmException extends RuntimeException {
    /** 错误发生的阶段，用于 CLI 展示和后续恢复策略。 */
    public enum Kind {
        HTTP, // 服务返回非 2xx 状态，例如认证失败或限流。
        NETWORK, // 连接、传输等网络故障。
        TIMEOUT, // 等待响应超过配置时限。
        INTERRUPTED, // 调用线程被中断，请求随之取消。
        INVALID_REQUEST, // 本地请求参数、JSON 或 HTTP 头无效。
        INVALID_RESPONSE, // 服务响应结构或数据不符合当前契约。
        MODEL_REJECTION // 服务明确未提供可用回答。
    }

    /** 错误类别，所有 LLM 异常都必须提供。 */
    private final Kind kind;
    /** HTTP 状态码；非 HTTP 错误为 null。 */
    private final Integer statusCode;

    /** 创建不关联 HTTP 状态码的分类异常；message 应为可展示的脱敏说明。 */
    public LlmException(Kind kind, String message) {
        this(kind, null, message);
    }

    /** 创建带可选状态码的异常，不保存原始请求或服务商响应正文。 */
    public LlmException(Kind kind, Integer statusCode, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.statusCode = statusCode;
    }

    /** 返回失败类别，用于区别参数错误、网络故障和模型结果问题。 */
    public Kind kind() {
        return kind;
    }

    /** 返回原始 HTTP 状态码，未收到 HTTP 错误响应时为 null。 */
    public Integer statusCode() {
        return statusCode;
    }

    /** 为后续策略提供分类提示；当前客户端不自动重试。 */
    public boolean isRetryable() {
        return kind == Kind.NETWORK || kind == Kind.TIMEOUT
                || (kind == Kind.HTTP && statusCode != null
                && (statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode <= 599)));
    }
}
