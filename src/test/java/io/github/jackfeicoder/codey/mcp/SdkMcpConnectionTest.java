package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.github.jackfeicoder.codey.tool.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真正启动隔离 HTTP/stdio 服务，验证固定 SDK 的握手、分页、路由、凭证和清理。 */
class SdkMcpConnectionTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @Test void streamableHttpPreservesEndpointHeadersPaginationAndErrors()throws Exception {
        var calls=new AtomicInteger();var lists=new AtomicInteger();var endpointCorrect=new AtomicBoolean(true);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/custom/mcp",exchange->{
            try {
                if(!exchange.getRequestMethod().equals("POST")){exchange.sendResponseHeaders(405,-1);return;}
                endpointCorrect.compareAndSet(true,"region=fixture".equals(exchange.getRequestURI().getRawQuery())&&"Bearer fixture-token".equals(exchange.getRequestHeaders().getFirst("Authorization")));
                var request=JSON.readTree(exchange.getRequestBody());
                if(!request.has("id")){exchange.sendResponseHeaders(202,-1);return;}
                var response=JSON.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));
                var result=response.putObject("result");String method=request.path("method").asText();
                switch(method) {
                    case "initialize" -> {
                        result.put("protocolVersion",request.path("params").path("protocolVersion").asText());
                        result.putObject("capabilities").putObject("tools");result.putObject("serverInfo").put("name","fixture").put("version","1");
                    }
                    case "tools/list" -> {
                        lists.incrementAndGet(); boolean second=request.path("params").hasNonNull("cursor");
                        var tool=result.putArray("tools").addObject().put("name",second?"other":"echo").put("description","Echo fixture");
                        tool.set("inputSchema",McpModuleTest.json(McpModuleTest.SCHEMA));if(!second)result.put("nextCursor","page2");
                    }
                    case "tools/call" -> {
                        calls.incrementAndGet();assertEquals("echo",request.path("params").path("name").asText());
                        boolean fail=request.path("params").path("arguments").path("query").asText().equals("fail");
                        result.put("isError",fail);result.putArray("content").addObject().put("type","text").put("text",fail?"fixture failure":"fixture success");
                        result.putObject("structuredContent").put("value",7);
                    }
                    default -> throw new IllegalStateException(method);
                }
                byte[] bytes=JSON.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } finally {exchange.close();}
        });server.start();
        var c=new McpServerConfig("remote","fixture","streamable-http","http://127.0.0.1:"+server.getAddress().getPort()+"/custom/mcp?region=fixture",null,
                List.of(),Map.of(),Map.of("Authorization","Bearer fixture-token"),null,true,Set.of(),Set.of(),5,3,null);
        try(var connection=SdkMcpConnection.open(c)) {
            assertEquals(2,connection.tools().size());assertEquals(2,lists.get());
            String result=connection.call("echo",McpModuleTest.json("{\"query\":\"ok\"}"));assertTrue(result.contains("fixture success"));assertTrue(result.contains("7"));
            var error=assertThrows(ToolException.class,()->connection.call("echo",McpModuleTest.json("{\"query\":\"fail\"}")));
            assertTrue(error.getMessage().contains("reported an error"));assertEquals(2,calls.get());assertTrue(endpointCorrect.get());
        } finally {server.stop(0);}
    }
    @Test void stdioUsesArgvEnvCwdAndClosesChildProcess()throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cwd=Path.of("").toAbsolutePath().normalize().toString();
        var c=new McpServerConfig("local","fixture","stdio",null,java,
                List.of("-cp",System.getProperty("java.class.path"),StdioFixture.class.getName()),Map.of("MCP_FIXTURE_VALUE","explicit-value"),Map.of(),cwd,true,Set.of(),Set.of(),5,3,null);
        long pid;
        try(var connection=SdkMcpConnection.open(c)) {
            assertEquals(1,connection.tools().size());var result=JSON.readTree(connection.call("echo",McpModuleTest.json("{\"query\":\"hello\"}")));
            pid=result.get("pid").asLong();assertEquals("explicit-value",result.get("env").asText());assertEquals(cwd,result.get("cwd").asText());
            assertEquals("hello",result.get("query").asText());assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
        }
        for(int i=0;i<40&&ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);i++)Thread.sleep(50);
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    /** 测试专用 JSONL 子进程；所有 stdout 都是协议，普通诊断使用 stderr。 */
    public static class StdioFixture {
        public static void main(String[] args)throws Exception {
            var in=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8));
            var out=new java.io.PrintWriter(System.out,true,StandardCharsets.UTF_8);
            for(String line;(line=in.readLine())!=null;) {
                var request=JSON.readTree(line);if(!request.has("id"))continue;
                var response=JSON.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));var result=response.putObject("result");
                switch(request.get("method").asText()) {
                    case "initialize" -> { result.put("protocolVersion",request.path("params").path("protocolVersion").asText());result.putObject("capabilities").putObject("tools");result.putObject("serverInfo").put("name","fixture").put("version","1"); }
                    case "tools/list" -> {var tool=result.putArray("tools").addObject().put("name","echo").put("description","Echo fixture");tool.set("inputSchema",McpModuleTest.json(McpModuleTest.SCHEMA));}
                    case "tools/call" -> {
                        var value=JSON.createObjectNode().put("pid",ProcessHandle.current().pid()).put("env",System.getenv("MCP_FIXTURE_VALUE"))
                                .put("cwd",Path.of("").toAbsolutePath().normalize().toString()).put("query",request.path("params").path("arguments").path("query").asText());
                        result.put("isError",false).putArray("content").addObject().put("type","text").put("text",value.toString());
                    }
                    default -> throw new IllegalStateException();
                }
                out.println(response);
            }
        }
    }
}
