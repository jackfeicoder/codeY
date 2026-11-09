package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.SessionSettings;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.task.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真实会话文件与任务日志联测：重启、继续、提交窗口与歧义选择。 */
class TaskCommandsTest {
    @TempDir Path data;
    private final StringWriter output=new StringWriter(),errors=new StringWriter();
    private int launch(String commands,SessionLauncher.Options options,LlmClient client) throws Exception {
        return new SessionLauncher(data,new SessionSettings(data,null,false),new JsonSessionStore(data),
                name->new SessionLauncher.ModelChoice("fixture",client,client),()->"base",
                new BufferedReader(new StringReader(commands)),new PrintWriter(output),new PrintWriter(errors),options,
                new ToolRegistry(),DebugPrinter.NONE).withTasks(new TaskStore(data)).run();
    }
    @Test void failedFirstTaskAppearsInSessionListAndContinuesAfterRestart() throws Exception {
        var count=new AtomicInteger();
        launch("goal\n/exit\n",new SessionLauncher.Options(null,true,"test",null,null),request->{
            if(count.incrementAndGet()==1)return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("old","unknown","{}"))),"tool_calls",null);
            throw new LlmException(LlmException.Kind.HTTP,"fixture unavailable");
        });
        var sessions=new JsonSessionStore(data).list().sessions();assertEquals(1,sessions.size());
        assertTrue(sessions.get(0).messages().isEmpty());
        String sid=sessions.get(0).id();String tid=new TaskStore(data).list(sid).get(0).id;
        assertEquals(TaskState.Status.FAILED,new TaskStore(data).list(sid).get(0).status);
        var resumed=new AtomicInteger();
        launch("1\n/tasks\n继续\n/exit\n",new SessionLauncher.Options(null,false,null,null,null),request->{
            resumed.incrementAndGet();
            assertTrue(request.messages().stream().anyMatch(m->"old".equals(m.toolCallId())));
            assertEquals(1,request.messages().stream().filter(m->m.role()==MessageRole.USER).count());
            assertTrue(request.messages().stream().anyMatch(m->m.content()!=null && m.content().contains("第 2 次执行")));
            return new LlmResponse(Message.assistant("recovered"),"stop",null);
        });
        assertEquals(1,resumed.get());
        var task=new TaskStore(data).list(sid).get(0);assertEquals(tid,task.id);assertTrue(task.completed());
        assertEquals(4,new JsonSessionStore(data).load(sid).messages().size());
        assertFalse(Files.exists(data.resolve("sessions").resolve(sid).resolve("tasks").resolve(tid).resolve("tmp")));
        assertTrue(output.toString().contains("未完成任务"));
    }
    @Test void finalReadyTaskIsCommittedWithoutRequestAndRepeatedContinueDoesNotDuplicateIt() throws Exception {
        var store=new JsonSessionStore(data);String sid;
        try(var service=SessionService.create(store,WorkspaceBinding.none(),"fixture","base","ready")) {
            sid=service.snapshot().id();
            try(var run=new TaskStore(data).create(sid,WorkspaceBinding.none(),"fixture","goal","",service.session().messages())) {
                run.assistant(Message.assistant("saved final"));
            }
        }
        launch("继续\n继续\n/exit\n",new SessionLauncher.Options(null,false,null,sid,null),request->{
            // 第二个普通“继续”在没有未完成任务时恢复正常对话语义。
            return new LlmResponse(Message.assistant("new answer"),"stop",null);
        });
        var messages=store.load(sid).messages();
        assertEquals(4,messages.size());assertEquals("saved final",messages.get(1).content());
        assertTrue(new TaskStore(data).list(sid).stream().allMatch(TaskState::completed));
        assertTrue(output.toString().contains("无需重新调用模型或工具"));
    }
    @Test void crashAfterSessionSaveDoesNotAppendFinalAnswerTwice() throws Exception {
        var store=new JsonSessionStore(data);String sid;
        try(var service=SessionService.create(store,WorkspaceBinding.none(),"fixture","base","ready")) {
            sid=service.snapshot().id();
            try(var run=new TaskStore(data).create(sid,WorkspaceBinding.none(),"fixture","goal","",service.session().messages())) {
                run.assistant(Message.assistant("final"));service.session().completeAgentTurn(run.state().messages);service.captureTurn();service.save();
            }
        }
        launch("/continue\n/exit\n",new SessionLauncher.Options(null,false,null,sid,null),request->{throw new AssertionError("Must not call model");});
        assertEquals(2,store.load(sid).messages().size());assertTrue(new TaskStore(data).list(sid).get(0).completed());
    }
    @Test void multipleUnfinishedTasksRequireSelectionAndOtherSessionsAreNotResumed() throws Exception {
        var store=new JsonSessionStore(data);String sid;
        try(var service=SessionService.create(store,WorkspaceBinding.none(),"fixture","base","pending")) {
            sid=service.snapshot().id();
            for(String goal:List.of("one","two"))try(var run=new TaskStore(data).create(sid,WorkspaceBinding.none(),"fixture",goal,"",service.session().messages())){run.stop(TaskState.Status.FAILED);}
        }
        launch("继续\n/continue missing\n/exit\n",new SessionLauncher.Options(null,false,null,sid,null),r->{throw new AssertionError("No implicit choice");});
        assertTrue(output.toString().contains("存在多个未完成任务"));assertEquals(2,new TaskStore(data).list(sid).size());
    }
    @Test void numericUuidPrefixIsNotMistakenForAnOutOfRangeSessionNumber() throws Exception {
        var store=new JsonSessionStore(data);String sid="12345678-1234-4234-8234-123456789012";
        var initial=SessionSnapshot.create(WorkspaceBinding.none(),"fixture","base","numeric");
        var snapshot=new SessionSnapshot(initial.schemaVersion(),sid,initial.title(),initial.titleSource(),false,
                initial.createdAt(),initial.updatedAt(),0,initial.workspace(),initial.profileAlias(),initial.systemPrompt(),initial.promptHash(),
                List.of(Message.user("first"),Message.assistant("answer")));
        try(var lease=store.lock(sid,true)){lease.save(snapshot);}
        var calls=new AtomicInteger();
        launch("12345678\nnext\n/exit\n",new SessionLauncher.Options(null,false,null,null,null),request->{
            calls.incrementAndGet();return new LlmResponse(Message.assistant("done"),"stop",null);
        });
        assertEquals(1,calls.get());assertEquals(4,store.load(sid).messages().size());
    }
    @Test void ordinaryTaskKeepsDurableRecordsWithoutPrintingLifecycleNoise() throws Exception {
        launch("hello\n/exit\n",new SessionLauncher.Options(null,true,"quiet",null,null),r->new LlmResponse(Message.assistant("done"),"stop",null));
        var session=new JsonSessionStore(data).list().sessions().get(0);var task=new TaskStore(data).list(session.id()).get(0);
        assertTrue(task.completed());assertEquals(2,session.messages().size());
        assertFalse(output.toString().contains(task.id));assertFalse(output.toString().contains("进度随执行保存"));assertFalse(output.toString().contains("托管临时目录已尝试清理"));
    }
}
