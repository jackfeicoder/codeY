package io.github.jackfeicoder.codey.session;

/** 消息来源。Java 枚举与发送给模型的协议值分开表达。 */
public enum MessageRole {
    /** 系统指令与行为约束。 */
    SYSTEM("system"),
    /** 用户输入或任务要求。 */
    USER("user"),
    /** 模型生成的回答或工具调用请求。 */
    ASSISTANT("assistant"),
    /** 宿主执行工具后回填的结果。 */
    TOOL("tool");

    /** 接口协议中的小写角色名，避免直接序列化大写 Java 枚举名。 */
    private final String wireValue;

    /** 枚举常量创建时绑定对应的协议字符串。 */
    MessageRole(String wireValue) {
        this.wireValue = wireValue;
    }

    /** 返回请求 JSON 中 role 应使用的值。 */
    public String wireValue() {
        return wireValue;
    }

    /** 将接口角色字符串转换为枚举，未知或缺失角色明确报错。 */
    public static MessageRole fromWireValue(String value) {
        for (MessageRole role : values()) {
            // role 依次枚举受支持的消息来源，与协议字符串精确匹配。
            if (role.wireValue.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unsupported message role; expected system, user, assistant or tool.");
    }
}
