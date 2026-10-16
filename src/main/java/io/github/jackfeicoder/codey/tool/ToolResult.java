package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.session.Message;

/**
 * 统一执行结果，关联原始调用 ID；成功与失败都能转换成一条 tool 消息。
 * @param callId 对应 ToolCall.id，不能换成随机 ID
 * @param name 请求的工具名称，未知工具也保留名称用于定位
 * @param status 明确区分成功和错误，避免把错误文本误当成功
 * @param output 工具输出或可展示错误说明
 * @param errorCode 成功时为 null，错误时必须存在
 * @param durationMillis 从查找/校验到完成的耗时，不表示已实现执行超时
 * @param truncated 输出是否被宿主截断
 */
public record ToolResult(String callId, String name, Status status, String output,
                         ErrorCode errorCode, long durationMillis, boolean truncated) {
    /** 结果状态；失败不会自动触发重试。 */
    public enum Status { SUCCESS, ERROR }
    /** 首版统一错误类别，后续路径/审批策略可按真实需求扩展。 */
    public enum ErrorCode {
        UNKNOWN_TOOL, INVALID_ARGUMENTS, WORKSPACE_REQUIRED, WORKSPACE_UNAVAILABLE, EXECUTION_FAILED, INTERRUPTED,
        PATH_OUTSIDE_WORKSPACE, FILE_NOT_FOUND, NOT_A_FILE, FILE_NOT_READABLE, FILE_READ_FAILED,
        FILE_TOO_LARGE, LINE_TOO_LONG, INVALID_ENCODING, NOT_TEXT_FILE, OUTPUT_LIMIT_TOO_SMALL,
        DIRECTORY_NOT_FOUND, NOT_A_DIRECTORY, DIRECTORY_NOT_READABLE, DIRECTORY_READ_FAILED,
        FILE_ALREADY_EXISTS, FILE_NOT_WRITABLE, FILE_WRITE_FAILED, FILE_CONFLICT, TEXT_NOT_FOUND, AMBIGUOUS_MATCH
    }
    /** 只用于结果正文编码，不复用模型响应解析器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 检查关联信息和成功/错误的一致性，输出允许为空字符串。 */
    public ToolResult {
        if (callId == null || callId.isBlank() || name == null || name.isBlank() || status == null
                || output == null || durationMillis < 0
                || (status == Status.SUCCESS && errorCode != null) || (status == Status.ERROR && errorCode == null)) {
            throw new IllegalArgumentException("Invalid tool result.");
        }
    }

    /** 编码结构化正文并使用原 callId 生成 tool 消息；不自动追加历史或再次调用模型。 */
    public Message toMessage() {
        ObjectNode body = MAPPER.createObjectNode(); // 状态和正文一起回填，便于模型识别错误/截断。
        body.put("status", status == Status.SUCCESS ? "success" : "error");
        body.put("output", output);
        if (errorCode == null) body.putNull("errorCode");
        else body.put("errorCode", errorCode.name());
        body.put("durationMillis", durationMillis);
        body.put("truncated", truncated);
        try {
            return Message.tool(callId, MAPPER.writeValueAsString(body));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Failed to encode a tool result.");
        }
    }
}
