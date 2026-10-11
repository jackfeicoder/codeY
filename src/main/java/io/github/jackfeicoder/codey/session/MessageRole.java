package io.github.jackfeicoder.codey.session;

/** 消息来源。Java 枚举与发送给模型的协议值分开表达。 */
public enum MessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireValue;

    MessageRole(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static MessageRole fromWireValue(String value) {
        for (MessageRole role : values()) {
            if (role.wireValue.equals(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unsupported message role; expected system, user, assistant or tool.");
    }
}
