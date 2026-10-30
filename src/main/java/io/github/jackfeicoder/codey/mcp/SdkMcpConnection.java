package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.*;
import io.github.jackfeicoder.codey.tool.*;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 官方 SDK 0.17.2 的适配层；使用 SDK 支持的 legacy 初始化协议，不手写 JSON-RPC。 */
public final class SdkMcpConnection implements McpConnection {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final McpSyncClient client;
    private final List<RemoteTool> tools;
    private SdkMcpConnection(McpSyncClient client,List<RemoteTool> tools) { this.client=client; this.tools=List.copyOf(tools); }
    public static McpConnection open(McpServerConfig config) {
        McpClientTransport transport;
        if(config.transport().equals("stdio")) {
            var params=ServerParameters.builder(config.command()).args(config.args()).env(config.env()).build();
            // 清理父进程密钥；显式 env 覆盖仍保留。cwd 由可信配置给定，不接受模型参数。
            var stdio=new StdioClientTransport(params,new JacksonMcpJsonMapper(JSON)) {
                @Override protected ProcessBuilder getProcessBuilder() {
                    var builder=super.getProcessBuilder();
                    builder.environment().keySet().removeIf(k -> !config.env().containsKey(k)
                            && (k.toUpperCase(Locale.ROOT).matches(".*(KEY|TOKEN|PASSWORD|SECRET).*" ) || k.startsWith("CODEY_")));
                    builder.environment().putAll(config.env());
                    if(config.cwd()!=null) builder.directory(Path.of(config.cwd()).toFile());
                    return builder;
                }
            };
            stdio.setStdErrorHandler(line -> { }); // 服务器日志可能含密钥，不直接打印到控制台。
            transport=stdio;
        } else {
            URI uri=URI.create(config.url());
            String origin=uri.getScheme()+"://"+uri.getRawAuthority();
            String endpoint=(uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
            transport=HttpClientStreamableHttpTransport.builder(origin).endpoint(endpoint)
                    .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                    .connectTimeout(Duration.ofSeconds(config.startupTimeoutSeconds()))
                    .customizeRequest(r -> config.headers().forEach(r::header)).openConnectionOnStartup(false).build();
        }
        var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(config.toolTimeoutSeconds()))
                .initializationTimeout(Duration.ofSeconds(config.startupTimeoutSeconds()))
                .clientInfo(new McpSchema.Implementation("codey","0.1.0")).build();
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(config.startupTimeoutSeconds()).toNanos();
            client.initialize();
            if(client.getServerCapabilities()==null || client.getServerCapabilities().tools()==null)
                return new SdkMcpConnection(client,List.of());
            var tools=new ArrayList<RemoteTool>(); var cursors=new HashSet<String>(); String cursor=null;
            do {
                if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new IllegalStateException();
                var page=client.listTools(cursor);
                for(var tool:page.tools()) {
                    if(tools.size()>=256) throw new IllegalArgumentException();
                    tools.add(new RemoteTool(tool.name(),tool.description(),JSON.valueToTree(tool.inputSchema())));
                }
                cursor=page.nextCursor();
                if(cursor!=null && (!cursors.add(cursor)||cursors.size()>16)) throw new IllegalArgumentException();
            } while(cursor!=null);
            return new SdkMcpConnection(client,tools);
        } catch(RuntimeException failed) { client.close(); throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED,
                "MCP connection or discovery failed; check transport, authentication and protocol compatibility."); }
    }
    @Override public List<RemoteTool> tools() { return tools; }
    @Override public String call(String rawName,com.fasterxml.jackson.databind.JsonNode args) {
        try { return McpResultMapper.map(client.callTool(new McpSchema.CallToolRequest(rawName,JSON.convertValue(args,Map.class)))); }
        catch(ToolException expected) { throw expected; }
        catch(RuntimeException failed) { throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED,
                "MCP request failed or timed out; execution outcome may be unknown. Do not automatically replay writes."); }
    }
    @Override public void close() { client.close(); }
}
