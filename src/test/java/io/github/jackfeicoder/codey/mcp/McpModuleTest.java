package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 隔离连接替身验证动态工具可见性、审批、故障和任务资源释放。 */
class McpModuleTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String SCHEMA="{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}";
    static JsonNode json(String text) { try{return JSON.readTree(text);}catch(Exception e){throw new RuntimeException(e);} }
    static McpServerConfig config(String name) { return new McpServerConfig(name,"搜索问题","streamable-http","http://localhost/mcp",null,List.of(),Map.of(),Map.of(),null,true,Set.of(),Set.of(),1,1,null); }
    static class Connection implements McpConnection {
        boolean closed;int calls;String raw;
        List<RemoteTool> definitions=List.of(new RemoteTool("search","Search issues",json(SCHEMA)));
        @Override public List<RemoteTool> tools(){return definitions;}
        @Override public String call(String name,JsonNode args){calls++;raw=name;return "fixture result";}
        @Override public void close(){closed=true;}
    }
    static ToolContext context(PermissionMode mode,WorkMode work,ApprovalHandler handler) {
        var workspace=WorkspaceBinding.none(); return new ToolContext(workspace,16000,work,new PermissionService(mode,workspace,handler));
    }
    static ToolContext full(){return context(PermissionMode.FULL,WorkMode.REACT,ApprovalHandler.unavailable());}
    static ToolCall call(String name,String args){return new ToolCall(UUID.randomUUID().toString(),name,args);}
    static LlmResponse calls(ToolCall... calls){return new LlmResponse(Message.assistant(null,List.of(calls)),"tool_calls",null);}
    static LlmResponse answer(){return new LlmResponse(Message.assistant("done"),"stop",null);}
    @Test void constructionIsDormantAndRepeatedSummonReusesConnection() {
        var registry=new ToolRegistry();var opens=new AtomicInteger();var connection=new Connection();
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->{opens.incrementAndGet();return connection;})) {
            assertEquals(0,opens.get());assertEquals(2,registry.definitions().size());assertTrue(manager.catalog().contains("未知"));
            manager.summon("github");manager.summon("github");assertEquals(1,opens.get());assertEquals(3,registry.definitions().size());
            assertTrue(manager.catalog().contains("ACTIVE"));manager.release("github");assertTrue(connection.closed);assertEquals(2,registry.definitions().size());
            assertFalse(manager.catalog().contains("未知"));assertEquals("DORMANT",manager.state("github"));
        }
    }
    @Test void modelSummonsThenUsesToolNextRoundAndTaskReleasesEverything() {
        var registry=new ToolRegistry();var connection=new Connection();var count=new AtomicInteger();
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
            var agent=new AgentLoop(request->{
                int step=count.incrementAndGet();
                boolean visible=request.tools().stream().anyMatch(t->t.name().equals("mcp__github__search"));
                if(step==1) { assertFalse(visible);return calls(call("mcp_summon","{\"server\":\"github\"}")); }
                assertTrue(visible);
                if(step==2)return calls(call("mcp__github__search","{\"query\":\"bug\"}"));
                assertTrue(request.messages().get(request.messages().size()-1).content().contains("fixture result"));return answer();
            },registry);
            var session=new Session("base");assertTrue(agent.run(session,"find issues",full()).completed());
            assertEquals("search",connection.raw);assertEquals(1,connection.calls);assertTrue(connection.closed);assertFalse(manager.active("github"));
            assertEquals(1,session.messages().size());assertEquals(2,registry.definitions().size());
        }
    }
    @Test void toolInventedInSameSummonResponseIsNotExecutedUntilNextRequest() {
        var registry=new ToolRegistry();var connection=new Connection();var count=new AtomicInteger();
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
            var result=new AgentLoop(r->{if(count.incrementAndGet()==1)return calls(call("mcp_summon","{\"server\":\"github\"}"),call("mcp__github__search","{\"query\":\"bug\"}"));
                assertTrue(r.messages().get(r.messages().size()-1).content().contains("UNKNOWN_TOOL"));return answer();},registry).run(new Session("base"),"find",full());
            assertTrue(result.completed());assertEquals(0,connection.calls);assertTrue(connection.closed);
        }
    }
    @Test void askRequiresSeparateActivationAndToolApprovalAndDenialPreventsCall() {
        var registry=new ToolRegistry();var connection=new Connection();var approvals=new ArrayList<ApprovalRequest>();
        var context=context(PermissionMode.ASK,WorkMode.REACT,r->{approvals.add(r);return approvals.size()==1?ApprovalHandler.Answer.APPROVE:ApprovalHandler.Answer.DENY;});
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
            var executor=new ToolExecutor(registry);
            assertEquals(ToolResult.Status.SUCCESS,executor.execute(call("mcp_summon","{\"server\":\"github\"}"),context).status());
            assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,executor.execute(call("mcp__github__search","{\"query\":\"bug\"}"),context).errorCode());
            assertEquals(2,approvals.size());assertTrue(approvals.get(1).preview().contains("bug"));assertEquals(0,connection.calls);
        }
    }
    @Test void fullStillRespectsPlanAndUnavailableAskDoesNotConnect() {
        var registry=new ToolRegistry();var opens=new AtomicInteger();
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->{opens.incrementAndGet();return new Connection();})) {
            var executor=new ToolExecutor(registry);var call=call("mcp_summon","{\"server\":\"github\"}");
            assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED,executor.execute(call,context(PermissionMode.ASK,WorkMode.REACT,ApprovalHandler.unavailable())).errorCode());
            assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,executor.execute(call,context(PermissionMode.FULL,WorkMode.PLAN,ApprovalHandler.unavailable())).errorCode());
            assertEquals(0,opens.get());
        }
    }
    @Test void inputSchemaIsCheckedBeforeRemoteCall() {
        var registry=new ToolRegistry();var connection=new Connection();
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
            manager.summon("github");var executor=new ToolExecutor(registry);
            assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,executor.execute(call("mcp__github__search","{\"query\":3}"),full()).errorCode());
            assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,executor.execute(call("mcp__github__search","{}"),full()).errorCode());assertEquals(0,connection.calls);
        }
    }
    @Test void malformedDiscoveryDoesNotRegisterPartialBatchAndClosesConnection() {
        var registry=new ToolRegistry();var connection=new Connection();connection.definitions=List.of(connection.definitions.get(0),connection.definitions.get(0));
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
            assertThrows(ToolException.class,()->manager.summon("github"));assertTrue(connection.closed);assertEquals(2,registry.definitions().size());assertEquals("FAILED",manager.state("github"));
        }
    }
    @Test void namespaceSupportsSameRawToolAndLossyNamesStayDistinct() {
        assertNotEquals(McpConnectionManager.publicName("github","a.b"),McpConnectionManager.publicName("github","a_b"));
        assertTrue(McpConnectionManager.publicName("a".repeat(32),"z".repeat(100)).length()<=64);
        var registry=new ToolRegistry();try(var manager=new McpConnectionManager(registry,List.of(config("github"),config("web")),c->new Connection())) {
            manager.summon("github");manager.summon("web");assertTrue(registry.find("mcp__github__search").isPresent());assertTrue(registry.find("mcp__web__search").isPresent());
            manager.release("github");assertTrue(registry.find("mcp__web__search").isPresent());
        }
    }
    @Test void disabledServerCannotBeSummonedOrExecuteStaleAdapter() {
        var registry=new ToolRegistry();var connection=new Connection();try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
            manager.summon("github");var stale=registry.find("mcp__github__search").orElseThrow().implementation();
            manager.enabled("github",false);assertTrue(connection.closed);assertThrows(ToolException.class,()->manager.summon("github"));
            assertThrows(ToolException.class,()->stale.validateArguments(json("{\"query\":\"x\"}"),full()));
            manager.enabled("github",true);assertEquals("DORMANT",manager.state("github"));
        }
    }
    @Test void exceptionAndBudgetExhaustionBothReleaseTaskResources() {
        for(boolean exception:List.of(true,false)) {
            var registry=new ToolRegistry();var connection=new Connection();var count=new AtomicInteger();
            try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->connection)) {
                var agent=new AgentLoop(r->{if(count.incrementAndGet()==1)return calls(call("mcp_summon","{\"server\":\"github\"}"));
                    if(exception)throw new LlmException(LlmException.Kind.HTTP,429,"fixture");return calls(call("mcp__github__search","{\"query\":\"x\"}"));},registry,2);
                if(exception)assertThrows(LlmException.class,()->agent.run(new Session("base"),"x",full()));
                else assertFalse(agent.run(new Session("base"),"x",full()).completed());
                assertTrue(connection.closed);assertFalse(manager.active("github"));
            }
        }
    }
    @Test void activationTimeoutClosesLateConnectionAndDoesNotPublishTools() throws Exception {
        var registry=new ToolRegistry();var connection=new Connection();
        try(var manager=new McpConnectionManager(registry,List.of(config("github")),c->{
            long end=System.nanoTime()+1_200_000_000L;while(System.nanoTime()<end) {try{Thread.sleep(10);}catch(InterruptedException ignored){}}
            return connection;
        })) {
            assertThrows(ToolException.class,()->manager.summon("github"));Thread.sleep(350);
            assertTrue(connection.closed);assertFalse(manager.active("github"));assertEquals(2,registry.definitions().size());
        }
    }
    @Test void filtersToolsAndRejectsRemoteSchemaReferences() {
        var registry=new ToolRegistry();var c=config("github");
        var filtered=new McpServerConfig(c.name(),c.description(),c.transport(),c.url(),null,List.of(),Map.of(),Map.of(),null,true,Set.of(),Set.of("search"),1,1,null);
        try(var manager=new McpConnectionManager(registry,List.of(filtered),x->new Connection())) {manager.summon("github");assertEquals(2,registry.definitions().size());}
        var remote=new Connection();remote.definitions=List.of(new McpConnection.RemoteTool("evil","x",json("{\"type\":\"object\",\"$ref\":\"https://example.com/schema\"}")));
        try(var manager=new McpConnectionManager(registry,List.of(c),x->remote)) {assertThrows(ToolException.class,()->manager.summon("github"));assertTrue(remote.closed);}
    }
}
