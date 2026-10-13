package io.github.jackfeicoder.codey.tool;

import java.util.Objects;

/** 工具的预期业务失败；错误说明应为可展示文本，不带原始异常或凭证。 */
public final class ToolException extends RuntimeException {
    /** 供 ToolResult 记录的错误类别。 */
    private final ToolResult.ErrorCode code;

    /** 创建由执行器转换为错误结果的异常。 */
    public ToolException(ToolResult.ErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Tool error message is required.");
        }
    }

    /** 返回可供调用方修正参数或判断失败阶段的类别。 */
    public ToolResult.ErrorCode code() {
        return code;
    }
}
