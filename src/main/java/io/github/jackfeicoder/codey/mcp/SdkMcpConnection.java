package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.*;
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
    private final McpDiagnostics diagnostics;
    private SdkMcpConnection(McpSyncClient client,List<RemoteTool> tools,McpDiagnostics diagnostics) { this.client=client; this.tools=List.copyOf(tools); this.diagnostics=diagnostics; }
    public static McpConnection open(McpServerConfig config) {
        return open(config,config::headers);
    }
    /** 每个 HTTP 请求动态取凭证；刷新只发生在发送之前，不重放失败业务调用。 */
    public static McpConnection open(McpServerConfig config,java.util.function.Supplier<Map<String,String>> headers) {
        long started=System.nanoTime();
        var diagnostics=new McpDiagnostics(config);
        McpClientTransport transport;
        if(config.transport().equals("stdio")) {
            var params=ServerParameters.builder(config.command()).args(config.args()).env(config.env()).build();
            // 清理父进程密钥；显式 env 覆盖仍保留。cwd 由可信配置给定，不接受模型参数。
            var stdio=new StdioClientTransport(params,diagnostics.mapper()) {
                @Override public reactor.core.publisher.Mono<Void> connect(java.util.function.Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>,reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler) {
                    return super.connect(handler).doOnError(diagnostics::startFailed).doOnSuccess(ignored -> {
                        // SDK 不暴露 Process；只观察真实 waitFor 结束，不依据错误文字猜测退出。
                        var watcher=new Thread(()->{try { awaitForExit(); diagnostics.exited(); } catch(RuntimeException ignoredExit) { }},"codey-mcp-exit");
                        watcher.setDaemon(true); watcher.start();
                    });
                }
                @Override protected ProcessBuilder getProcessBuilder() {
                    var builder=super.getProcessBuilder();
                    builder.environment().keySet().removeIf(k -> !config.env().containsKey(k)
                            && (k.toUpperCase(Locale.ROOT).matches(".*(KEY|TOKEN|PASSWORD|SECRET).*" ) || k.startsWith("CODEY_")));
                    builder.environment().putAll(config.env());
                    if(config.cwd()!=null) builder.directory(Path.of(config.cwd()).toFile());
                    return builder;
                }
            };
            stdio.setStdErrorHandler(diagnostics::stderr);
            transport=stdio;
        } else {
            URI uri=URI.create(config.url());
            String origin=uri.getScheme()+"://"+uri.getRawAuthority();
            String endpoint=(uri.getRawPath().isEmpty()?"/":uri.getRawPath())+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
            transport=HttpClientStreamableHttpTransport.builder(origin).endpoint(endpoint)
                    .jsonMapper(diagnostics.mapper())
                    .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                    .connectTimeout(Duration.ofSeconds(config.startupTimeoutSeconds()))
                    .httpRequestCustomizer((request,method,address,body,context) -> headers.get().forEach(request::setHeader))
                    .openConnectionOnStartup(false).build();
        }
        var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(config.toolTimeoutSeconds()))
                .initializationTimeout(Duration.ofSeconds(config.startupTimeoutSeconds()))
                .clientInfo(new McpSchema.Implementation("codey","0.1.0")).build();
        String stage="initialize";
        try {
            long deadline=System.nanoTime()+Duration.ofSeconds(config.startupTimeoutSeconds()).toNanos();
            client.initialize();
            if(client.getServerCapabilities()==null || client.getServerCapabilities().tools()==null)
                return new SdkMcpConnection(client,List.of(),diagnostics);
            stage="discover";
            var tools=new ArrayList<RemoteTool>(); var cursors=new HashSet<String>(); String cursor=null;
            do {
                if(Thread.currentThread().isInterrupted()) throw diagnostics.explicit(ToolResult.ErrorCode.INTERRUPTED,stage,null,started);
                if(System.nanoTime()>deadline) throw diagnostics.explicit(ToolResult.ErrorCode.MCP_STARTUP_TIMEOUT,stage,null,started);
                var page=client.listTools(cursor);
                for(var tool:page.tools()) {
                    if(tools.size()>=256) throw new IllegalArgumentException();
                    tools.add(new RemoteTool(tool.name(),tool.description(),JSON.valueToTree(tool.inputSchema())));
                }
                cursor=page.nextCursor();
                if(cursor!=null && (!cursors.add(cursor)||cursors.size()>16)) throw new IllegalArgumentException();
            } while(cursor!=null);
            return new SdkMcpConnection(client,tools,diagnostics);
        } catch(RuntimeException failed) {
            // 必须先生成诊断，再清理；否则主动关闭进程会被误判成“子进程意外退出”。
            ToolException diagnostic=failed instanceof ToolException t?t:diagnostics.failure(stage,null,started,failed);
            try { client.close(); } catch(RuntimeException ignored) { }
            rethrowAuth(failed); throw diagnostic;
        }
    }
    @Override public List<RemoteTool> tools() { return tools; }
    @Override public String call(String rawName,com.fasterxml.jackson.databind.JsonNode args) {
        long started=System.nanoTime(); diagnostics.beginCall();
        try { return McpResultMapper.map(client.callTool(new McpSchema.CallToolRequest(rawName,JSON.convertValue(args,Map.class)))); }
        catch(ToolException expected) {
            if(expected.code()==ToolResult.ErrorCode.MCP_REMOTE_ERROR || expected.code()==ToolResult.ErrorCode.MCP_INVALID_RESPONSE)
                throw diagnostics.reported("call",rawName,started,expected);
            throw expected;
        }
        catch(RuntimeException failed) { rethrowAuth(failed); throw diagnostics.failure("call",rawName,started,failed); }
    }
    /** SDK 可能包裹请求定制器异常；只透传本地认证错误，不回显任意远端响应。 */
    private static void rethrowAuth(RuntimeException failure) {
        Throwable cause=failure;
        for(int i=0;i<10&&cause!=null;i++,cause=cause.getCause())
            if(cause instanceof ToolException tool&&(tool.code()==ToolResult.ErrorCode.AUTH_REQUIRED||tool.code()==ToolResult.ErrorCode.AUTH_FAILED))throw tool;
    }
    @Override public void close() { client.close(); }
}
