package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实隔离 stdio 故障测试，不读取实际 mcp.json、凭据或邮箱。 */
class McpDiagnosticsTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static McpServerConfig config(String mode) {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        return new McpServerConfig("fixture","fixture","stdio",null,java,
                List.of("-cp",System.getProperty("java.class.path"),Fixture.class.getName(),mode),Map.of(),Map.of(),null,true,Set.of(),Set.of(),4,1,null);
    }
    ToolException failure(String mode) {
        try(var connection=SdkMcpConnection.open(config(mode))) {
            return assertThrows(ToolException.class,()->connection.call("probe",JSON.createObjectNode()));
        }
    }
    @Test void callTimeoutShowsBudgetStageAndExceptionWithoutRawText()throws Exception {
        var error=failure("stall"); assertEquals(ToolResult.ErrorCode.MCP_CALL_TIMEOUT,error.code());
        var detail=JSON.readTree(error.getMessage());assertEquals("fixture",detail.path("server").asText());
        assertEquals("probe",detail.path("tool").asText());assertEquals("call",detail.path("stage").asText());
        assertEquals(1,detail.path("timeoutSeconds").asInt());assertTrue(detail.path("elapsedMillis").asLong()>=900);
        assertTrue(detail.path("exceptionTypes").toString().contains("TimeoutException"));
        assertTrue(detail.path("executionOutcome").asText().contains("unknown"));
    }
    @Test void processExitIsObservedRatherThanGuessedFromTimeout() {
        assertEquals(ToolResult.ErrorCode.MCP_PROCESS_EXITED,failure("exit").code());
    }
    @Test void malformedStdoutIsClassifiedEvenWhenSdkSwallowsParseFailure() {
        var error=failure("malformed");assertEquals(ToolResult.ErrorCode.MCP_PROTOCOL_ERROR,error.code());
        assertFalse(error.getMessage().contains("secret-body-sentinel"));
    }
    @Test void remoteToolErrorKeepsOnlySignalAndNeverRawResponseOrStderr()throws Exception {
        var error=failure("remote");assertEquals(ToolResult.ErrorCode.MCP_REMOTE_ERROR,error.code());
        var detail=JSON.readTree(error.getMessage());assertEquals("CONNECTION_REFUSED",detail.path("remoteSignal").asText());
        assertFalse(error.getMessage().contains("secret-body-sentinel"));assertFalse(error.getMessage().contains("fake-access-token"));
        assertTrue(detail.path("stderrLines").asLong()>=1);
    }
    @Test void jsonRpcErrorPreservesCodeWithoutMessageOrData()throws Exception {
        var error=failure("rpc");assertEquals(ToolResult.ErrorCode.MCP_REMOTE_ERROR,error.code());
        assertEquals(-32602,JSON.readTree(error.getMessage()).path("jsonRpcCode").asInt());
        assertFalse(error.getMessage().contains("fake-access-token"));
    }
    @Test void missingExecutableIsStartupFailure()throws Exception {
        var c=config("ok"); var missing=new McpServerConfig(c.name(),c.description(),c.transport(),null,
                Path.of("target","definitely-not-a-program.exe").toAbsolutePath().toString(),List.of(),Map.of(),Map.of(),null,true,Set.of(),Set.of(),2,1,null);
        var error=assertThrows(ToolException.class,()->SdkMcpConnection.open(missing));
        assertEquals(ToolResult.ErrorCode.MCP_START_FAILED,error.code());assertEquals("initialize",JSON.readTree(error.getMessage()).path("stage").asText());
    }
    @Test void managerDoesNotEraseSpecificWorkerError() {
        var expected=new McpDiagnostics(config("ok")).explicit(ToolResult.ErrorCode.MCP_PROTOCOL_ERROR,"initialize",null,System.nanoTime());
        try(var manager=new McpConnectionManager(new ToolRegistry(),List.of(config("ok")),c->{throw expected;})) {
            assertSame(expected,assertThrows(ToolException.class,()->manager.summon("fixture")));
        }
    }
    @Test void activationDeadlineRemainsDistinctFromCallDeadline()throws Exception {
        var c=config("ok");var shortConfig=new McpServerConfig(c.name(),c.description(),c.transport(),null,c.command(),c.args(),Map.of(),Map.of(),null,true,Set.of(),Set.of(),1,2,null);
        try(var manager=new McpConnectionManager(new ToolRegistry(),List.of(shortConfig),ignored->{
            try {Thread.sleep(5000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}throw new RuntimeException();
        })) {
            var error=assertThrows(ToolException.class,()->manager.summon("fixture"));assertEquals(ToolResult.ErrorCode.MCP_STARTUP_TIMEOUT,error.code());
            assertEquals("activate",JSON.readTree(error.getMessage()).path("stage").asText());
        }
    }
    @Test void consolePrintsOnlyDiagnosticSummaryAndTiming() {
        var error=new McpDiagnostics(config("ok")).explicit(ToolResult.ErrorCode.MCP_CALL_TIMEOUT,"call","probe",System.nanoTime());
        var lines=new ArrayList<String>();new DebugPrinter((kind,text)->lines.add(text)).toolFailed(2,
                new ToolResult("id","mcp__fixture__probe",ToolResult.Status.ERROR,error.getMessage(),error.code(),1000,false));
        assertEquals(3,lines.size());assertTrue(lines.get(1).contains("阶段 call"));assertTrue(lines.get(1).contains("1 s"));
    }
    @Test void unknownFailureDoesNotTurnIntoTimeoutAndDoesNotLeakMessage() {
        var error=new McpDiagnostics(config("ok")).failure("call","probe",System.nanoTime(),new IllegalStateException("fake-access-token"));
        assertEquals(ToolResult.ErrorCode.MCP_TRANSPORT_ERROR,error.code());assertFalse(error.getMessage().contains("fake-access-token"));
    }
    /** 每个模式只触发一种确定故障，响应中故意放入秘密哨兵以验证输出隔离。 */
    public static class Fixture {
        public static void main(String[] args)throws Exception {
            var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            var output=new PrintWriter(System.out,true,StandardCharsets.UTF_8);
            for(String line;(line=input.readLine())!=null;) {
                var request=JSON.readTree(line);if(!request.has("id"))continue;
                var response=JSON.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));
                var result=response.putObject("result");
                switch(request.path("method").asText()) {
                    case "initialize" -> {result.put("protocolVersion",request.path("params").path("protocolVersion").asText());result.putObject("capabilities").putObject("tools");result.putObject("serverInfo").put("name","fixture").put("version","1");}
                    case "tools/list" -> {var tool=result.putArray("tools").addObject().put("name","probe").put("description","fixture");tool.putObject("inputSchema").put("type","object");}
                    case "tools/call" -> {
                        switch(args[0]) {
                            case "stall" -> Thread.sleep(4000);
                            case "exit" -> {System.exit(23);}
                            case "malformed" -> {output.println("not-json secret-body-sentinel");Thread.sleep(4000);}
                            case "remote" -> {System.err.println("credential fake-access-token");result.put("isError",true).putArray("content").addObject().put("type","text").put("text","ECONNREFUSED secret-body-sentinel fake-access-token");}
                            case "rpc" -> {response.remove("result");response.putObject("error").put("code",-32602).put("message","fake-access-token").put("data","secret-body-sentinel");}
                            default -> result.putArray("content").addObject().put("type","text").put("text","ok");
                        }
                    }
                    default -> throw new IllegalStateException();
                }
                output.println(response);
            }
        }
    }
}
