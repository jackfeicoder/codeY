package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.mcp.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpCliTest {
    @TempDir Path root;
    private final StringWriter out=new StringWriter(),errors=new StringWriter();
    private final AtomicInteger opens=new AtomicInteger(),closed=new AtomicInteger(),calls=new AtomicInteger();
    private final ToolRegistry tools=new ToolRegistry();
    private McpConnectionManager manager() {
        var config=new McpServerConfig("fixture","fixture search","stdio",null,"fixture",List.of(),Map.of(),Map.of(),null,true,Set.of(),Set.of(),2,2,null);
        return new McpConnectionManager(tools,List.of(config),c->{opens.incrementAndGet();return new McpConnection(){
            public List<RemoteTool> tools(){try{return List.of(new RemoteTool("search","Search fixture",new ObjectMapper().readTree("{\"type\":\"object\"}")));}catch(Exception e){throw new RuntimeException(e);}}
            public String call(String raw,com.fasterxml.jackson.databind.JsonNode args){calls.incrementAndGet();return "found";}
            public void close(){closed.incrementAndGet();}
        };});
    }
    private void run(String script,McpConnectionManager manager,LlmClient client)throws Exception {
        var output=new PrintWriter(out);var input=new ConsoleInput(new BufferedReader(new StringReader(script)),output);
        assertEquals(0,new SessionLauncher(root,new SessionSettings(root.resolve("data"),null,false),new JsonSessionStore(root.resolve("data")),
                p->new SessionLauncher.ModelChoice("fixture model",client,client),()->"base",input,output,new PrintWriter(errors),
                new SessionLauncher.Options(null,true,"fixture",null,null),tools,DebugPrinter.NONE,List::of,20,new PermissionSettings(PermissionMode.ASK,true)).withMcp(manager).run());
    }
    private static LlmResponse done(){return new LlmResponse(Message.assistant("done"),"stop",null);}
    @Test void commandsManageServerAndTaskUsesSameApprovalAndReleases()throws Exception {
        var modelCalls=new AtomicInteger();
        try(var manager=manager()) {
            run("/help\n/mcp\n/mcp tools fixture\n/mcp summon fixture\ny\nfind issue\ny\n/mcp tools fixture\n/mcp disable fixture\n/mcp enable fixture\n/exit\n",manager,request->{
                if(modelCalls.incrementAndGet()==1){assertTrue(request.tools().stream().anyMatch(t->t.name().equals("mcp__fixture__search")));
                    return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("call","mcp__fixture__search","{}"))),"tool_calls",null);}
                return done();
            });
            assertEquals(1,opens.get());assertEquals(1,closed.get());assertEquals(1,calls.get());assertFalse(manager.active("fixture"));
            assertTrue(out.toString().contains("/mcp [list"));assertTrue(out.toString().contains("DORMANT"));assertTrue(out.toString().contains("DISABLED"));
            var saved=new JsonSessionStore(root.resolve("data")).list().sessions().get(0);assertEquals(4,saved.messages().size());
            assertFalse(saved.messages().stream().anyMatch(m->m.content()!=null&&m.content().contains("/mcp summon")));assertEquals("",errors.toString());
        }
    }
    @Test void planDeniesSummonAndExitClosesManuallyActivatedServer()throws Exception {
        try(var manager=manager()) {
            run("/mode plan\n/mcp summon fixture\n/mode react\n/mcp summon fixture\ny\n/exit\n",manager,r->done());
            assertEquals(1,opens.get());assertEquals(1,closed.get());assertTrue(out.toString().contains("PLAN"));assertEquals(0,calls.get());
        }
    }
}
