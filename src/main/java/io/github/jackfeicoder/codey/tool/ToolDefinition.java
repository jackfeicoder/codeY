package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 提供给模型的工具说明，与 Java 执行对象分开；协议适配器后续映射成请求 tools 字段。
 * @param name 注册名称，例如 read_file，最多 64 个字母、数字、下划线或连字符
 * @param description 用自然语言说明何时使用、作用及重要限制
 * @param parameters 参数 JSON Schema，顶层必须声明 type: object
 */
public record ToolDefinition(String name, String description, JsonNode parameters) {
    /** 校验基础元数据并复制 schema；这里只校验顶层，不宣称验证完整 JSON Schema 标准。 */
    public ToolDefinition {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Tool name must contain 1–64 letters, digits, underscores or hyphens.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description is required.");
        }
        if (parameters == null || !parameters.isObject() || !"object".equals(parameters.path("type").asText())) {
            throw new IllegalArgumentException("Tool parameters must be an object JSON Schema.");
        }
        parameters = parameters.deepCopy(); // record 固定引用，JSON 节点仍需要主动复制。
    }

    /** 返回 schema 的副本，外部修改不会改变已注册的定义。 */
    @Override
    public JsonNode parameters() {
        return parameters.deepCopy();
    }
}
