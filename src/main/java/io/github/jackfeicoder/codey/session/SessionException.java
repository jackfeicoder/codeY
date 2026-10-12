package io.github.jackfeicoder.codey.session;

/** 本地会话操作失败；消息只包含定位信息，不附带原始 JSON、消息或凭证。 */
public final class SessionException extends RuntimeException {
    /** message 应包含错误类别或 ID，避免回显文件正文。 */
    public SessionException(String message) {
        super(message);
    }
}
