package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.plan.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.task.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 模型替身+真实会话/计划/任务文件，覆盖审阅、修订、步骤执行、失败恢复和预算。 */
class PlanCliTest {
    @TempDir Path root;
    StringWriter output,errors;
    Path data(){return root.resolve("data");}
    SessionStore sessions(){return new JsonSessionStore(data());}
    SessionSnapshot saved(){return sessions().list().sessions().get(0);}
    PlanStore plans(){return new PlanStore(data(),saved().id(),saved().workspace());}
    int launch(String commands,String resume,LlmClient client)throws Exception {
        Files.createDirectories(root.resolve("work"));output=new StringWriter();errors=new StringWriter();
        return new SessionLauncher(root,new SessionSettings(data(),null,false),sessions(),
                p->new SessionLauncher.ModelChoice("fixture",client,client),()->"base",
                new BufferedReader(new StringReader(commands)),new PrintWriter(output),new PrintWriter(errors),
                new SessionLauncher.Options(resume==null?root.resolve("work").toString():null,false,resume==null?"plan-test":null,resume,null),
                new ToolRegistry().register(new ReadFileTool()).register(new WriteFileTool()),DebugPrinter.NONE)
                .withTasks(new TaskStore(data())).withPlans().run();
    }
    static String step(String id,String kind,String... deps) {
        String dependencies=Arrays.stream(deps).map(s->"\""+s+"\"").collect(java.util.stream.Collectors.joining(","));
        return "{\"id\":\""+id+"\",\"description\":\"do "+id+"\",\"verification\":\"check result\",\"kind\":\""+kind+"\",\"files\":[],\"dependsOn\":["+dependencies+"]}";
    }
    static LlmResponse calls(ToolCall... calls){return new LlmResponse(Message.assistantToolCalls(List.of(calls)),"tool_calls",null);}
    static ToolCall submit(String title,String... steps){return new ToolCall("submit-"+title,"submit_plan","{\"title\":\""+title+"\",\"steps\":["+String.join(",",steps)+"]}");}
    static ToolCall update(String id,String evidence){return new ToolCall("update-"+id,"update_plan","{\"stepId\":\""+id+"\",\"status\":\"COMPLETED\",\"note\":\"checked\",\"evidence\":["+(evidence==null?"":"\""+evidence+"\"")+"]}");}
    static ToolCall write(String id,String path){return new ToolCall(id,"write_file","{\"path\":\""+path+"\",\"content\":\"created once\"}");}
    static LlmResponse done(){return new LlmResponse(Message.assistant("discussion"),"stop",null);}
    @Test void submittingPlanStopsPhaseAndDoesNotAllowSameResponseToWrite()throws Exception {
        var count=new AtomicInteger();launch("/plan implement something\n/exit\n",null,r->{count.incrementAndGet();assertTrue(r.tools().stream().anyMatch(t->t.name().equals("submit_plan")));assertTrue(r.tools().stream().noneMatch(t->t.name().equals("write_file")));return calls(submit("v1",step("A","IMPLEMENTATION")),write("evil","unexpected.txt"));});
        assertEquals(1,count.get());assertFalse(Files.exists(root.resolve("work/unexpected.txt")));assertEquals(ExecutionPlan.Phase.AWAITING_REVIEW,plans().latest().phase);
        assertTrue(output.toString().contains("等待用户审阅"));assertTrue(output.toString().contains("[1/执行]"));assertEquals("",errors.toString());
        assertTrue(saved().messages().stream().anyMatch(m->m.content()!=null&&m.content().contains("PERMISSION_DENIED")));
    }
    @Test void approvalRunsStepsInTopologicalOrderAndCommitsEvidenceWithoutExtraModelCalls()throws Exception {
        var count=new AtomicInteger();launch("/plan implement\n执行\n/exit\n",null,r->switch(count.incrementAndGet()) {
            case 1->calls(submit("v1",step("B","IMPLEMENTATION","A"),step("A","IMPLEMENTATION")));
            case 2->{assertTrue(r.messages().get(0).content().contains("[当前执行步骤] A"));yield calls(write("file-a","a.txt"),update("A","file-a"));}
            case 3->{assertTrue(r.messages().get(0).content().contains("[当前执行步骤] B"));yield calls(write("file-b","b.txt"),update("B","file-b"));}
            default->throw new AssertionError("No extra model calls");
        });
        assertEquals(3,count.get());assertEquals(ExecutionPlan.Phase.COMPLETED,plans().latest().phase);assertEquals(2,plans().latest().modelCalls);assertTrue(Files.exists(root.resolve("work/a.txt")));assertTrue(Files.exists(root.resolve("work/b.txt")));assertEquals("",errors.toString());
        assertTrue(new TaskStore(data()).list(saved().id()).stream().allMatch(TaskState::completed));
    }
    @Test void userCanReviseBeforeExecutionAndContinueDoesNotApprove()throws Exception {
        var count=new AtomicInteger();launch("/plan implement\n继续\n2\nchange A\n3\n/exit\n",null,r->{int n=count.incrementAndGet();return calls(submit("v"+n,step("A","ANALYSIS")));});
        assertEquals(2,count.get());assertEquals(2,plans().latest().version);assertEquals(1,plans().latest().history.size());assertNull(plans().latest().approvedHash);assertEquals(0,plans().latest().modelCalls);assertTrue(output.toString().contains("继续不表示批准"));
    }
    @Test void failedExecutionResumesOriginalTaskAfterRestartWithoutRewritingKnownFile()throws Exception {
        var count=new AtomicInteger();launch("/plan implement\n执行\n/exit\n",null,r->switch(count.incrementAndGet()){
            case 1->calls(submit("v1",step("A","IMPLEMENTATION")));
            case 2->calls(write("known-write","a.txt"));
            default->throw new LlmException(LlmException.Kind.HTTP,"fixture disconnect");
        });
        var plan=plans().latest();String taskId=plan.step("A").taskId;assertEquals(ExecutionPlan.Phase.BLOCKED,plan.phase);
        var resumed=new AtomicInteger();launch("继续\n/exit\n",saved().id(),r->{resumed.incrementAndGet();assertTrue(r.messages().stream().anyMatch(m->m.role()==MessageRole.TOOL&&m.toolCallId().equals("known-write")));return calls(update("A","known-write"));});
        assertEquals(1,resumed.get());assertEquals(taskId,plans().latest().step("A").taskId);assertEquals(3,plans().latest().modelCalls);assertEquals(ExecutionPlan.Phase.COMPLETED,plans().latest().phase);assertEquals("created once",Files.readString(root.resolve("work/a.txt")));assertEquals("",errors.toString());
    }
    @Test void plainModelAnswerDoesNotMarkStepCompleteOrExecuteDependents()throws Exception {
        var count=new AtomicInteger();launch("/plan implement\n执行\n/exit\n",null,r->count.incrementAndGet()==1?calls(submit("v1",step("A","ANALYSIS"),step("B","ANALYSIS","A"))):done());
        assertEquals(2,count.get());assertEquals(PlanStep.Status.BLOCKED,plans().latest().step("A").status);assertEquals(PlanStep.Status.PENDING,plans().latest().step("B").status);
    }
    @Test void budgetIsSharedAndOnlyUserCanAddItAfterRestart()throws Exception {
        launch("/plan implement\n/exit\n",null,r->calls(submit("v1",step("A","ANALYSIS"),step("B","ANALYSIS","A"))));
        var p=plans().latest();p.maxModelCalls=1;plans().save(p,p.sequence);var count=new AtomicInteger();String sid=saved().id();
        launch("执行\n继续\n/exit\n",sid,r->{count.incrementAndGet();return calls(update("A",null));});assertEquals(1,count.get());assertEquals(1,plans().latest().modelCalls);assertEquals(PlanStep.Status.PENDING,plans().latest().step("B").status);
        launch("增加预算 2\n继续\n/exit\n",sid,r->{count.incrementAndGet();return calls(update("B",null));});assertEquals(2,count.get(),output+"\n"+errors);assertEquals(3,plans().latest().maxModelCalls);assertEquals(2,plans().latest().modelCalls);assertEquals(ExecutionPlan.Phase.COMPLETED,plans().latest().phase);
    }
    @Test void persistedOutcomeIsReconciledWithoutRequestingModelAgain()throws Exception {
        launch("/plan implement\n/exit\n",null,r->calls(submit("v1",step("A","ANALYSIS"))));
        var p=plans().latest();var service=new PlanService(plans(),saved().id(),saved().workspace());service.approve();
        var history=new ArrayList<Message>();history.add(Message.system(saved().systemPrompt()));history.addAll(saved().messages());
        try(var task=new TaskStore(data()).create(saved().id(),saved().workspace(),"fixture","step A","",history)) {
            task.bindPlan(new TaskState.PlanLink(p.id,p.version,"A",false));service.startStep("A",task.state().id);service.propose("A",task.state().id,p.version,PlanStep.Status.COMPLETED,"analysis",List.of(),task.state().messages);
        }
        launch("继续\n/exit\n",saved().id(),r->{throw new AssertionError("No replay");});assertEquals(ExecutionPlan.Phase.COMPLETED,plans().latest().phase,output+"\n"+errors);assertEquals("",errors.toString());
    }
    @Test void committedTaskWithUncommittedPlanIsReconciledEvenWithZeroBudget()throws Exception {
        launch("/plan implement\n/exit\n",null,r->calls(submit("v1",step("A","ANALYSIS"))));
        var service=new PlanService(plans(),saved().id(),saved().workspace());service.approve();
        var history=new ArrayList<Message>();history.add(Message.system(saved().systemPrompt()));history.addAll(saved().messages());
        var p=service.current();p.maxModelCalls=1;plans().save(p,p.sequence);
        service=new PlanService(plans(),saved().id(),saved().workspace());
        List<Message> turn;
        try(var task=new TaskStore(data()).create(saved().id(),saved().workspace(),"fixture","step A","",history)) {
            task.bindPlan(new TaskState.PlanLink(p.id,p.version,"A",false));service.startStep("A",task.state().id);
            service.reserveRequest("A",task.state().id,p.version);
            service.propose("A",task.state().id,p.version,PlanStep.Status.COMPLETED,"analysis",List.of(),task.state().messages);
            task.assistant(Message.assistant("result"));turn=List.copyOf(task.state().messages);task.committed();
        }
        try(var session=SessionService.resume(sessions(),saved().id(),"fixture")){session.session().completeAgentTurn(turn);session.captureTurn();session.save();}
        launch("继续\n/exit\n",saved().id(),r->{throw new AssertionError("No replay or extra request");});
        assertEquals(ExecutionPlan.Phase.COMPLETED,plans().latest().phase,output+"\n"+errors);assertEquals(1,plans().latest().modelCalls);assertEquals("",errors.toString());
    }
    @Test void invalidPlanCanBeCorrectedInSamePlanningTask()throws Exception {
        var count=new AtomicInteger();launch("/plan implement\n/exit\n",null,r->{
            if(count.incrementAndGet()==1)return calls(submit("bad",step("A","ANALYSIS","missing")));
            assertTrue(r.messages().stream().anyMatch(m->m.role()==MessageRole.TOOL&&m.content().contains("INVALID_ARGUMENTS")));
            return calls(submit("good",step("A","ANALYSIS")));
        });
        assertEquals(2,count.get());assertEquals(1,plans().latest().version);assertEquals("good",plans().latest().title);assertEquals("",errors.toString());
    }
}
