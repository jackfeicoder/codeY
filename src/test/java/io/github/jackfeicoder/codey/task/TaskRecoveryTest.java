package io.github.jackfeicoder.codey.task;

import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实磁盘和模型替身验证崩溃边界；不调用网络或真实业务工具。 */
class TaskRecoveryTest {
    @TempDir Path data;
    private final WorkspaceBinding workspace=WorkspaceBinding.none();
    private final Session session=new Session("base");
    private String sessionId() throws Exception {
        String id=UUID.randomUUID().toString();Files.createDirectories(data.resolve("sessions").resolve(id));return id;
    }
    private TaskRun create(TaskStore store,String id){return store.create(id,workspace,"fixture","goal","",session.messages());}
    private LlmResponse answer(){return new LlmResponse(Message.assistant("done"),"stop",null);}
    private Message calls(String... ids){return Message.assistantToolCalls(Arrays.stream(ids).map(id->new ToolCall(id,"fixture","{}")).toList());}

    @Test void outOfOrderParallelResultsRebuildCanonicalHistoryAndDetectCommit() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)) {
            id=run.state().id;run.assistant(calls("first","second","third"));
            run.toolStarted("first");run.toolStarted("second");run.toolStarted("third");
            run.toolResult(Message.tool("third","result-3"));run.toolResult(Message.tool("second","result-2"));run.toolResult(Message.tool("first","result-1"));
            assertEquals(List.of("first","second","third"),run.state().messages.stream().filter(m->m.role()==MessageRole.TOOL).map(Message::toolCallId).toList());
            run.assistant(Message.assistant("done"));session.completeAgentTurn(run.state().messages);
        }
        try(var run=store.open(sid,id)) {
            assertTrue(run.alreadyCommitted(session.messages()));run.committed();
        }
    }
    @Test void partialParallelResultsKeepKnownLaterCallsWhenEarlierCallsAreMissing() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)) {
            id=run.state().id;run.assistant(calls("unknown-first","known-second","not-started"));
            run.toolStarted("unknown-first");run.toolStarted("known-second");run.toolResult(Message.tool("known-second","keep-known"));
        }
        try(var run=store.open(sid,id)) {
            run.resume("fixture",session.messages());
            var results=run.state().messages.stream().filter(m->m.role()==MessageRole.TOOL).toList();
            assertEquals(List.of("unknown-first","known-second","not-started"),results.stream().map(Message::toolCallId).toList());
            assertTrue(results.get(0).content().contains("recovery_unknown"));assertEquals("keep-known",results.get(1).content());assertTrue(results.get(2).content().contains("recovery_not_started"));
            assertTrue(new AgentLoop(r->answer(),new ToolRegistry(),2).withTask(run).run(session,"goal",new ToolContext(workspace)).completed());
        }
    }

    @Test void recoversKnownResultsAndMarksUncertainCallsWithoutReplayingThem() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)) {
            id=run.state().id;run.request();run.assistant(calls("known","unknown","not-started"));
            run.toolStarted("known");run.toolResult(Message.tool("known","original result"));run.toolStarted("unknown");
            Files.writeString(run.tempDirectory().resolve("keep.txt"),"intermediate");
        } // 模拟进程消失：没有 STOP，日志仍停在调用意图。
        try(var run=store.open(sid,id)) {
            assertEquals(TaskState.Status.RUNNING,run.state().status);
            run.resume("other-model",session.messages());
            assertEquals(2,run.state().attempts);
            assertTrue(run.state().messages.get(3).content().contains("recovery_unknown"));
            assertTrue(run.state().messages.get(4).content().contains("recovery_not_started"));
            AtomicInteger requests=new AtomicInteger();
            var result=new AgentLoop(request->{
                requests.incrementAndGet();
                assertTrue(request.messages().stream().anyMatch(m->"original result".equals(m.content())));
                assertTrue(request.messages().stream().anyMatch(m->m.content()!=null && m.content().contains("recovery_unknown")));
                return answer();
            },new ToolRegistry(),3).withTask(run).run(session,"goal",new ToolContext(workspace));
            assertEquals(1,requests.get());assertEquals(0,result.toolCalls());
            MessageHistory.validate(result.turnMessages());
            assertTrue(Files.exists(run.tempDirectory().resolve("keep.txt")));
            assertEquals(TaskState.Status.COMMIT_PENDING,run.state().status);
        }
    }
    @Test void journalRebuildsCheckpointAndFinalCommitIsDetectedExactlyOnce() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)) {
            id=run.state().id;run.assistant(Message.assistant("done"));
            assertFalse(run.alreadyCommitted(session.messages()));
            session.completeAgentTurn(run.state().messages);
        }
        Path directory=data.resolve("sessions").resolve(sid).resolve("tasks").resolve(id);
        Files.writeString(directory.resolve("checkpoint.json"),"broken derived cache");
        try(var run=store.open(sid,id)) {
            assertTrue(run.alreadyCommitted(session.messages()));run.committed();
            assertFalse(Files.exists(directory.resolve("tmp")));
            assertFalse(Files.exists(directory.resolve("checkpoint.json")));
            assertTrue(Files.exists(directory.resolve("events.jsonl")));
            assertTrue(Files.exists(directory.resolve("task.json")));
        }
        assertEquals(3,session.messages().size());
        assertTrue(store.list(sid).get(0).completed());
    }
    @Test void failureSavesCompletedToolResultsBeforeTheNextModelRequest() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;var count=new AtomicInteger();
        try(var run=create(store,sid)) {
            id=run.state().id;
            assertThrows(LlmException.class,()->new AgentLoop(request->{
                if(count.incrementAndGet()==1)return new LlmResponse(calls("call"),"tool_calls",null);
                throw new LlmException(LlmException.Kind.HTTP,"fixture");
            },new ToolRegistry(),3).withTask(run).run(session,"goal",new ToolContext(workspace)));
        }
        try(var run=store.open(sid,id)) {
            assertEquals(TaskState.Status.FAILED,run.state().status);assertEquals(2,run.state().modelCalls);
            assertEquals(1,run.state().toolCalls);assertEquals(MessageRole.TOOL,run.state().messages.get(2).role());
            assertTrue(run.state().messages.get(2).content().contains("UNKNOWN_TOOL"));
        }
        assertEquals(1,session.messages().size());
    }
    @Test void budgetStopRetainsProgressAndResumeGetsFreshRequestBudget() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)) {
            id=run.state().id;
            var result=new AgentLoop(request->new LlmResponse(calls("a"),"tool_calls",null),new ToolRegistry(),1)
                    .withTask(run).run(session,"goal",new ToolContext(workspace));
            assertFalse(result.completed());assertEquals(TaskState.Status.BUDGET_EXHAUSTED,run.state().status);
            assertEquals(1,run.state().messages.size()); // 最后一轮请求的工具并未调度。
        }
        try(var run=store.open(sid,id)) {
            run.resume("fixture",session.messages());
            assertTrue(new AgentLoop(r->answer(),new ToolRegistry(),1).withTask(run).run(session,"goal",new ToolContext(workspace)).completed());
            assertEquals(2,run.state().modelCalls);
        }
    }
    @Test void partialJournalTailIsBackedUpAndRemovedUnderLock() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)){id=run.state().id;run.request();}
        Path directory=data.resolve("sessions").resolve(sid).resolve("tasks").resolve(id);
        Files.writeString(directory.resolve("events.jsonl"),"{\"sequence\":",StandardOpenOption.APPEND);
        try(var run=store.open(sid,id)){run.resume("fixture",session.messages());run.request();}
        assertEquals(2,store.list(sid).get(0).modelCalls);
        try(var files=Files.list(directory)){assertTrue(files.anyMatch(p->p.toString().endsWith(".bak")));}
    }
    @Test void malformedCompleteJournalLineFailsClosed() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)){id=run.state().id;}
        Path log=data.resolve("sessions").resolve(sid).resolve("tasks").resolve(id).resolve("events.jsonl");
        Files.writeString(log,"{}\n",StandardOpenOption.APPEND);
        assertThrows(TaskException.class,()->store.open(sid,id));
    }
    @Test void rejectsChangedHistoryButAcceptsLaterCompletedTurns() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();
        try(var run=create(store,sid)) {
            assertThrows(TaskException.class,()->run.resume("fixture",List.of(Message.system("different"))));
            session.completeTurn("another goal",Message.assistant("ok"));
            run.resume("fixture",session.messages());assertEquals(3,run.state().anchorCount);
        }
    }
    @Test void holdsSingleWriterLockAndRejectsTraversalIds() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();
        try(var run=create(store,sid)){assertThrows(TaskException.class,()->store.open(sid,run.state().id));}
        assertThrows(TaskException.class,()->store.open(sid,"../elsewhere"));
    }
    @Test void journalWriteFailureStopsBeforeModelIsCalled() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();var count=new AtomicInteger();
        try(var run=create(store,sid)) {
            Path directory=run.tempDirectory().getParent();
            Files.delete(directory.resolve("events.jsonl"));Files.createDirectory(directory.resolve("events.jsonl"));
            assertThrows(TaskException.class,()->new AgentLoop(r->{count.incrementAndGet();return answer();},new ToolRegistry(),2)
                    .withTask(run).run(session,"goal",new ToolContext(workspace)));
            assertEquals(0,count.get());
        }
    }
    @Test void interruptionIsDurableAndPreservesInterruptFlag() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();String id;
        try(var run=create(store,sid)) {
            id=run.state().id;Thread.currentThread().interrupt();
            try {
                assertThrows(LlmException.class,()->new AgentLoop(r->answer(),new ToolRegistry(),2).withTask(run).run(session,"goal",new ToolContext(workspace)));
                assertTrue(Thread.currentThread().isInterrupted());
            }finally{Thread.interrupted();}
        }
        try(var run=store.open(sid,id)){assertEquals(TaskState.Status.INTERRUPTED,run.state().status);}
    }
    @Test void cleanupDoesNotRemoveWorkspaceDeliverablesOrOtherTaskFiles() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();Path output=data.resolve("deliverable.txt");Files.writeString(output,"keep");
        try(var run=create(store,sid)) {
            Path tmp=run.tempDirectory();
            Files.createDirectories(tmp.resolve("nested"));
            Files.writeString(tmp.resolve("nested/result.txt"),"temporary");
            Files.writeString(tmp.getParent().resolve("keep.txt"),"audit");
            run.assistant(Message.assistant("done"));run.committed();
            assertTrue(Files.exists(output));assertTrue(Files.exists(tmp.getParent().resolve("keep.txt")));
            assertFalse(Files.exists(tmp));
        }
    }
    @Test void completedWriteIsNotReplayedOnResumeAndDeliveryFileSurvivesCleanup() throws Exception {
        Path project=Files.createDirectory(data.resolve("project"));
        var binding=new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,project.toString());
        var context=new ToolContext(binding,16000,WorkMode.REACT,new io.github.jackfeicoder.codey.policy.PermissionService(
                io.github.jackfeicoder.codey.policy.PermissionMode.FULL,binding,io.github.jackfeicoder.codey.policy.ApprovalHandler.unavailable()));
        var registry=new ToolRegistry().register(new io.github.jackfeicoder.codey.tool.builtin.WriteFileTool());
        var store=new TaskStore(data);String sid=sessionId();String id;AtomicInteger count=new AtomicInteger();
        try(var run=store.create(sid,binding,"fixture","create output.txt","",session.messages())) {
            id=run.state().id;
            assertThrows(LlmException.class,()->new AgentLoop(request->{
                if(count.incrementAndGet()==1)return new LlmResponse(Message.assistantToolCalls(List.of(
                        new ToolCall("write","write_file","{\"path\":\"output.txt\",\"content\":\"original\"}"))),"tool_calls",null);
                throw new LlmException(LlmException.Kind.HTTP,"fixture");
            },registry,3).withTask(run).run(session,run.state().goal,context));
        }
        assertEquals("original",Files.readString(project.resolve("output.txt")));
        Files.writeString(project.resolve("output.txt"),"external edit");
        try(var run=store.open(sid,id)) {
            run.resume("fixture",session.messages());
            var result=new AgentLoop(request->answer(),registry,3).withTask(run).run(session,run.state().goal,context);
            session.completeAgentTurn(result.turnMessages());run.committed();
        }
        assertEquals("external edit",Files.readString(project.resolve("output.txt")));
    }
    @Test void taskTempEnvironmentReachesRealSubprocess() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();
        try(var run=create(store,sid)) {
            String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var result=new io.github.jackfeicoder.codey.tool.process.CommandRunner().run(List.of(executable,"-cp",
                    System.getProperty("java.class.path"),TempProbe.class.getName()),data,10,10000,java.nio.charset.StandardCharsets.UTF_8,
                    Map.of("CODEY_TASK_TMP_DIR",run.tempDirectory().toString()));
            assertEquals(0,result.exitCode());assertEquals("temporary",Files.readString(run.tempDirectory().resolve("probe.txt")));
        }
    }
    public static class TempProbe {
        public static void main(String[] args)throws Exception{Files.writeString(Path.of(System.getenv("CODEY_TASK_TMP_DIR")).resolve("probe.txt"),"temporary");}
    }
    @Test void cleanupDoesNotFollowLinkedDirectoriesOutsideTaskTmp() throws Exception {
        var store=new TaskStore(data);String sid=sessionId();
        Path outside=Files.createDirectory(data.resolve("user-files"));Files.writeString(outside.resolve("keep.txt"),"keep");
        try(var run=create(store,sid)) {
            Path link=run.tempDirectory().resolve("linked");
            if(System.getProperty("os.name").startsWith("Windows")) {
                String cmd=Path.of(System.getenv("SystemRoot"),"System32","cmd.exe").toString();
                var process=new ProcessBuilder(cmd,"/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
                assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
            } else Files.createSymbolicLink(link,outside);
            run.assistant(Message.assistant("done"));run.committed();
            assertEquals("keep",Files.readString(outside.resolve("keep.txt")));
            assertTrue(run.state().completed());
        }
    }
}
