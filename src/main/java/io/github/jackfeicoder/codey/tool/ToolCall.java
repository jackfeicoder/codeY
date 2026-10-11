package io.github.jackfeicoder.codey.tool;

/**
 * 模型提出的一条完整工具调用请求，不代表工具已经执行。
 * arguments 保留原始 JSON 文本，JSON 解析和参数校验留给后续调用与工具层。
 */
public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Tool call id is required.");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool call name is required.");
        }
        if (arguments == null || arguments.isBlank()) {
            throw new IllegalArgumentException("Tool call arguments are required; use {} for no arguments.");
        }
    }
}
