package io.github.jackfeicoder.codey.policy;

import java.util.Locale;

/** 用户选择的审批预设；不改变工具的实际文件/网络能力。 */
public enum PermissionMode {
    ASK("请求批准"), AUTO("帮我批准（规则版）"), FULL("完全访问（跳过常规审批）");
    /** 面向用户的简短名称，避免 auto/full 被误解为已有沙箱实现。 */
    private final String label;
    PermissionMode(String label) { this.label = label; }
    public String label() { return label; }
    public String value() { return name().toLowerCase(Locale.ROOT); }
    /** 无效值报告固定用法，不回显配置中可能误填的凭证。 */
    public static PermissionMode parse(String text) {
        if (text != null) for (var mode : values()) if (mode.value().equalsIgnoreCase(text.strip())) return mode;
        throw new IllegalArgumentException("权限模式应为 ask、auto 或 full。");
    }
}
