package io.github.jackfeicoder.codey.mcp;

import java.util.Set;

/** 用户显式配置的服务信任；不从远端工具名称、描述或 readOnlyHint 自动推断。 */
public record McpPermissions(boolean autoApproveConnect, Set<String> readOnlyTools) {
    public McpPermissions { readOnlyTools = Set.copyOf(readOnlyTools); }
    public static McpPermissions untrusted() { return new McpPermissions(false, Set.of()); }
}
