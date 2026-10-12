package io.github.jackfeicoder.codey.prompt;

/** 内置提示词加载失败；与模型调用和模型配置错误分别处理。 */
public final class PromptException extends RuntimeException {
    /** message 说明缺失或空白的资源路径，不包含提示词全文。 */
    public PromptException(String message) {
        super(message);
    }

    /** cause 保留底层读取异常，便于调试；CLI 仅展示 message。 */
    public PromptException(String message, Throwable cause) {
        super(message, cause);
    }
}
