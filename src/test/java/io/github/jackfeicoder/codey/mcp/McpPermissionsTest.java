package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 用隔离服务验证显式信任仅覆盖注册的连接/具体查询，绝不按 get/list 名称自行放行。 */
class McpPermissionsTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private com.fasterxml.jackson.databind.node.ObjectNode entry() {
        return json.createObjectNode().put("description","fixture").put("transport","stdio").put("command","fixture");
    }
    private ToolContext context(ApprovalHandler handler) throws Exception {
        var workspace=new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,root.toRealPath().toString());
        return new ToolContext(workspace,16000,WorkMode.REACT,new PermissionService(PermissionMode.ASK,workspace,handler));
    }
    private McpConnectionManager manager(ToolRegistry registry,McpServerConfig config,AtomicInteger calls) {
        return new McpConnectionManager(registry,List.of(config),c->new McpConnection(){
            public List<RemoteTool> tools(){var schema=json.createObjectNode().put("type","object");return List.of(
                    new RemoteTool("list_accounts","query",schema),new RemoteTool("send_mail","write",schema),new RemoteTool("get_delete","misleading name",schema));}
            public String call(String name,JsonNode args){calls.incrementAndGet();return "{\"ok\":true}";}
            public void close(){}
        });
    }
    @Test void trustedConnectAndExactQueriesRunButOtherCallsStillAsk() throws Exception {
        var e=entry();e.putObject("permissions").put("autoApproveConnect",true).putArray("readOnlyTools").add("list_accounts");
        var config=new McpConfigStore(root,Map.of()).register("fixture",e);var registry=new ToolRegistry();var calls=new AtomicInteger();var approvals=new AtomicInteger();
        try(var manager=manager(registry,config,calls)) {
            var context=context(r->{approvals.incrementAndGet();return ApprovalHandler.Answer.DENY;});var executor=new ToolExecutor(registry);
            assertEquals(ToolResult.Status.SUCCESS,executor.execute(new ToolCall("1","mcp_summon","{\"server\":\"fixture\"}"),context).status());
            assertEquals(ToolResult.Status.SUCCESS,executor.execute(new ToolCall("2","mcp__fixture__list_accounts","{}"),context).status());
            assertEquals(0,approvals.get());assertEquals(1,calls.get());
            assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,executor.execute(new ToolCall("3","mcp__fixture__send_mail","{}"),context).errorCode());
            assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,executor.execute(new ToolCall("4","mcp__fixture__get_delete","{}"),context).errorCode());
            assertEquals(2,approvals.get());assertEquals(1,calls.get());
        }
    }
    @Test void legacyRegistrationRetainsApprovalAndUserCanPersistOrRevokeTrust() throws Exception {
        var store=new McpConfigStore(root,Map.of());var c=store.register("fixture",entry());
        assertFalse(c.permissions().autoApproveConnect());assertTrue(c.permissions().readOnlyTools().isEmpty());
        var registry=new ToolRegistry();
        try(var manager=manager(registry,c,new AtomicInteger())) {
            var executor=new ToolExecutor(registry);
            assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED,executor.execute(new ToolCall("1","mcp_summon","{\"server\":\"fixture\"}"),context(ApprovalHandler.unavailable())).errorCode());
            var trusted=store.setPermissions("fixture",new McpPermissions(true,Set.of("list_accounts")));manager.applyConfiguration(trusted);
            assertTrue(McpConfigLoader.load(root,Map.of()).get(0).permissions().autoApproveConnect());
            assertEquals(ToolResult.Status.SUCCESS,executor.execute(new ToolCall("2","mcp_summon","{\"server\":\"fixture\"}"),context(ApprovalHandler.unavailable())).status());
            manager.applyConfiguration(store.setPermissions("fixture",McpPermissions.untrusted()));assertFalse(manager.active("fixture"));
            assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED,executor.execute(new ToolCall("3","mcp_summon","{\"server\":\"fixture\"}"),context(ApprovalHandler.unavailable())).errorCode());
        }
    }
    @Test void configChangeDuringApprovalDoesNotExecuteNewConnection() throws Exception {
        var store=new McpConfigStore(root,Map.of());var c=store.register("fixture",entry());var registry=new ToolRegistry();
        try(var manager=manager(registry,c,new AtomicInteger())) {
            var context=context(r->{manager.applyConfiguration(store.setPermissions("fixture",new McpPermissions(true,Set.of())));return ApprovalHandler.Answer.APPROVE;});
            var result=new ToolExecutor(registry).execute(new ToolCall("1","mcp_summon","{\"server\":\"fixture\"}"),context);
            assertEquals(ToolResult.ErrorCode.FILE_CONFLICT,result.errorCode());assertFalse(manager.active("fixture"));
        }
    }
}
