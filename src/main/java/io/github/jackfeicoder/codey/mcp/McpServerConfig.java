package io.github.jackfeicoder.codey.mcp;

import java.util.*;

/** 一个用户配置的 MCP 服务；不把凭证放入工具描述、日志或会话。 */
public record McpServerConfig(String name, String description, String transport, String url,
        String command, List<String> args, Map<String,String> env, Map<String,String> headers,
        String cwd, boolean enabled, Set<String> enabledTools, Set<String> disabledTools,
        int startupTimeoutSeconds, int toolTimeoutSeconds, Long estimatedTokens) {
    public McpServerConfig {
        args = List.copyOf(args); env = Map.copyOf(env); headers = Map.copyOf(headers);
        enabledTools = Set.copyOf(enabledTools); disabledTools = Set.copyOf(disabledTools);
    }
    /** 空允许列表表示全部，禁用优先；只限制本服务器工具。 */
    public boolean permits(String rawName) {
        return (enabledTools.isEmpty() || enabledTools.contains(rawName)) && !disabledTools.contains(rawName);
    }
    @Override public String toString() { return "McpServerConfig[" + name + ", " + transport + "]"; }
}
