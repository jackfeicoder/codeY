package io.github.jackfeicoder.codey.tool;

/**
 * 模型提出的一条完整工具调用请求，不代表工具已经执行。
 * arguments 保留原始 JSON 文本；LLM 适配器校验语法，工具层后续校验具体参数。
 * @param id 模型生成的调用 ID，用于关联 tool 结果消息
 * @param name 要调用的工具名称，例如 read_file
 * @param arguments 参数的 JSON 字符串，无参数时为 {}
 */
public record ToolCall(String id, String name, String arguments) {
    /** 校验调用标识、工具名与参数文本；此数据对象本身不执行工具。 */
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
