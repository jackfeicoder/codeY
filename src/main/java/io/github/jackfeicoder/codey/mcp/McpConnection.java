package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 协议与工具注册隔离；生产用官方 SDK，测试可注入隔离连接。 */
public interface McpConnection extends AutoCloseable {
    record RemoteTool(String name, String description, JsonNode schema) { }
    List<RemoteTool> tools();
    String call(String rawName, JsonNode arguments);
    @Override void close();
    @FunctionalInterface interface Factory { McpConnection open(McpServerConfig config); }
}
