package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具实现契约：说明能力、校验具体参数、执行并返回原始文本；不直接调用模型。 */
public interface Tool {
    /** 返回名称、描述和参数 schema，注册时保存为稳定快照。 */
    ToolDefinition definition();

    /** 操作类别由实现声明，未知工具默认需审；不是模型可填写的参数。 */
    default ToolAccess access() { return ToolAccess.UNKNOWN; }

    /** 默认工具无需特殊预处理；写工具覆盖此方法捕获确定的旧快照、新内容和预览。 */
    default PreparedToolOperation prepare(JsonNode arguments, ToolContext context) throws InterruptedException {
        return new PreparedToolOperation(access() == ToolAccess.READ_ONLY ? null
                : io.github.jackfeicoder.codey.policy.ApprovalRequest.unknown(definition().name()),
                () -> execute(arguments, context));
    }

    /** 项目文件/命令工具覆盖为 true；纯计算等工具可在普通聊天中使用。 */
    default boolean requiresWorkspace() {
        return false;
    }

    /**
     * 校验实际支持的参数及业务约束；失败应抛 ToolException(INVALID_ARGUMENTS, ...)。
     * 执行器已保证 arguments 是完整 JSON 对象；本阶段未引入通用 JSON Schema 校验器。
     */
    void validateArguments(JsonNode arguments, ToolContext context);

    /**
     * 执行已经校验的请求并返回文本，可以返回空字符串。
     * 预期业务错误抛 ToolException；阻塞操作可抛 InterruptedException，由执行器恢复中断标志。
     */
    String execute(JsonNode arguments, ToolContext context) throws InterruptedException;
}
