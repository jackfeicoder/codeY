package io.github.jackfeicoder.codey.agent;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.config.ToolSchedulingSettings;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.task.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实文件、模型替身与任务日志验证并行调度接入协议/PLAN/断点恢复，不调用外部服务。 */
@Timeout(15)
class ParallelAgentLoopTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static LlmResponse response(Message message){return new LlmResponse(message,message.toolCalls().isEmpty()?"stop":"tool_calls",null);}
    @Test void fourBuiltinReadsRemainPairedAndOrderedInPlanMode()throws Exception {
        Files.writeString(root.resolve("a.txt"),"needle fixture body");
        var workspace=new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,root.toRealPath().toString());
        var context=new ToolContext(workspace,16000,WorkMode.PLAN,new PermissionService(PermissionMode.ASK,workspace,r->{fail("No read approvals");return ApprovalHandler.Answer.DENY;}));
        var registry=new ToolRegistry().register(new ListFilesTool()).register(new SearchFileTool()).register(new GrepCodeTool()).register(new ReadFileTool());
        var calls=List.of(new ToolCall("list","list_files","{}"),new ToolCall("find","search_file","{\"pattern\":\"*.txt\"}"),
                new ToolCall("grep","grep_code","{\"query\":\"needle\"}"),new ToolCall("read","read_file","{\"path\":\"a.txt\"}"));
        assertTrue(registry.definitions().stream().allMatch(d->registry.find(d.name()).orElseThrow().executionMode()==ToolExecutionMode.PARALLEL_READ));
        var rounds=new AtomicInteger();var out=new StringWriter();
        var result=new AgentLoop(request->{
            if(rounds.incrementAndGet()==1)return response(Message.assistantToolCalls(calls));
            var tools=request.messages().stream().filter(m->m.role()==MessageRole.TOOL).toList();
            assertEquals(List.of("list","find","grep","read"),tools.stream().map(Message::toolCallId).toList());
            tools.forEach(m->{try{assertEquals("success",JSON.readTree(m.content()).path("status").asText());}catch(IOException e){throw new AssertionError(e);}});
            return response(Message.assistant("plan complete"));
        },registry,3,new DebugPrinter(new PrintWriter(out))).run(new Session("base"),"inspect files",context);
        assertTrue(result.completed());assertEquals(4,result.toolCalls());assertEquals(2,result.modelCalls());
        assertTrue(out.toString().contains("并行读取 4 项"));assertFalse(out.toString().contains("needle fixture body"));
        MessageHistory.validate(result.turnMessages());assertEquals("needle fixture body",Files.readString(root.resolve("a.txt")));
    }
    @Test void interruptedParallelTaskPersistsCompletedLaterResultAndResumesWithoutReplay()throws Exception {
        var store=new TaskStore(root);String sid=UUID.randomUUID().toString();Files.createDirectories(root.resolve("sessions").resolve(sid));
        var session=new Session("base");var workspace=WorkspaceBinding.none();
        var slowStarted=new CountDownLatch(1);var fastSaved=new CountDownLatch(1);var slowStopped=new CountDownLatch(1);
        var requests=new AtomicInteger();var failure=new AtomicReference<Throwable>();var flag=new AtomicBoolean();String id;
        var registry=new ToolRegistry().register(read("slow",()->{slowStarted.countDown();try{Thread.sleep(10000);return "late";}finally{slowStopped.countDown();}}))
                .register(read("fast",()->"known-fast-result"));
        try(var task=store.create(sid,workspace,"fixture","goal","",session.messages())) {
            id=task.state().id;
            task.withSkills(()->{if(task.state().messages.stream().anyMatch(m->"fast-id".equals(m.toolCallId())))fastSaved.countDown();return List.of();});
            var driver=new Thread(()->{
                try{new AgentLoop(request->{requests.incrementAndGet();return response(Message.assistantToolCalls(List.of(new ToolCall("slow-id","slow","{}"),new ToolCall("fast-id","fast","{}"))));},registry,3)
                        .withTask(task).run(session,"goal",new ToolContext(workspace));}
                catch(Throwable e){failure.set(e);flag.set(Thread.currentThread().isInterrupted());}
            });
            driver.start();try{
                assertTrue(slowStarted.await(3,TimeUnit.SECONDS));assertTrue(fastSaved.await(3,TimeUnit.SECONDS));driver.interrupt();driver.join(4000);
                assertFalse(driver.isAlive());assertInstanceOf(LlmException.class,failure.get());assertEquals(LlmException.Kind.INTERRUPTED,((LlmException)failure.get()).kind());assertTrue(flag.get());
                assertTrue(slowStopped.await(1,TimeUnit.SECONDS));assertEquals(1,requests.get());assertEquals(TaskState.Status.INTERRUPTED,task.state().status);
            }finally{driver.interrupt();driver.join(4000);}
        }
        try(var task=store.open(sid,id)) {
            task.resume("fixture",session.messages());
            var results=task.state().messages.stream().filter(m->m.role()==MessageRole.TOOL).toList();
            assertEquals(List.of("slow-id","fast-id"),results.stream().map(Message::toolCallId).toList());
            assertTrue(results.get(0).content().contains("recovery_unknown"));assertTrue(results.get(1).content().contains("known-fast-result"));
            var result=new AgentLoop(request->response(Message.assistant("resumed")),registry,2).withTask(task).run(session,"goal",new ToolContext(workspace));
            assertEquals(0,result.toolCalls());session.completeAgentTurn(result.turnMessages());assertTrue(task.alreadyCommitted(session.messages()));task.committed();
        }
    }
    @FunctionalInterface interface Body {String read()throws InterruptedException;}
    private static Tool read(String name,Body body){return new Tool(){
        public ToolDefinition definition(){return new ToolDefinition(name,"fixture",JSON.createObjectNode().put("type","object"));}
        public ToolAccess access(){return ToolAccess.READ_ONLY;}
        public ToolExecutionMode executionMode(){return ToolExecutionMode.PARALLEL_READ;}
        public void validateArguments(JsonNode args,ToolContext context){}
        public String execute(JsonNode args,ToolContext context)throws InterruptedException{return body.read();}
    };}
}
