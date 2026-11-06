package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.SessionSettings;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.task.*;
import io.github.jackfeicoder.codey.tool.*;
import org.jline.terminal.Terminal;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 验证用户取消与宿主退出分离；隔离子 JVM 验证实际 SIGINT 注册，不向测试进程发送信号。 */
@Timeout(20)
class OperationCancellationTest {
    @TempDir Path data;
    @AfterEach void resetInterrupt(){Thread.interrupted();}

    @Test void repeatedCtrlCDoesNotInterruptCleanupOrTheNextOperation() {
        var cancellation=new OperationCancellation();
        cancellation.cancel();assertFalse(Thread.currentThread().isInterrupted());
        try(var first=cancellation.begin()) {
            cancellation.cancel();assertTrue(first.cancelled());assertTrue(Thread.interrupted());
            cancellation.cancel();assertFalse(Thread.currentThread().isInterrupted());
        }
        try(var second=cancellation.begin()){assertFalse(second.cancelled());assertFalse(Thread.currentThread().isInterrupted());}
    }
    @Test void unrelatedThreadInterruptIsNotConsumed() {
        var cancellation=new OperationCancellation();
        try(var operation=cancellation.begin()){Thread.currentThread().interrupt();assertFalse(operation.cancelled());}
        assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void terminalSignalCancelsActiveOperationAndRestoresInput() throws Exception {
        var terminal=new DumbTerminal("fixture","xterm",new ByteArrayInputStream("next\r".getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),StandardCharsets.UTF_8);
        try(var input=new ConsoleInput(new BufferedReader(new StringReader("")),new PrintWriter(new StringWriter()),terminal)) {
            try(var operation=input.beginOperation()) {
                terminal.raise(Terminal.Signal.INT);assertTrue(operation.cancelled());assertTrue(Thread.currentThread().isInterrupted());
            }
            assertFalse(Thread.currentThread().isInterrupted());assertEquals("next",input.readLine("You> "));
        }
    }
    @Test void ctrlCWhileEditingClearsInputAndKeepsNextPrompt() throws Exception {
        var terminal=new DumbTerminal("fixture","xterm",new ByteArrayInputStream("partial\u0003next\r".getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),StandardCharsets.UTF_8);
        try(var input=new ConsoleInput(new BufferedReader(new StringReader("")),new PrintWriter(new StringWriter()),terminal)) {
            assertEquals("",input.readLine("You> "));assertEquals("next",input.readLine("You> "));
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }
    @Test void ctrlCAtApprovalCancelsTaskRatherThanRecordingARegularDenial() throws Exception {
        var terminal=new DumbTerminal("fixture","xterm",new ByteArrayInputStream("\u0003".getBytes(StandardCharsets.UTF_8)),new ByteArrayOutputStream(),StandardCharsets.UTF_8);
        try(var input=new ConsoleInput(new BufferedReader(new StringReader("")),new PrintWriter(new StringWriter()),terminal)) {
            try(var operation=input.beginOperation()) {
                assertNull(input.readApproval("Approve> "));
                assertTrue(operation.cancelled());assertTrue(Thread.currentThread().isInterrupted());
            }
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    private SessionLauncher launcher(ConsoleInput input,PrintWriter output,PrintWriter errors,LlmClient client,ToolRegistry tools) {
        return new SessionLauncher(data,new SessionSettings(data,null,false),new JsonSessionStore(data),
                name->new SessionLauncher.ModelChoice("fixture",client,client),()->"base",input,output,errors,
                new SessionLauncher.Options(null,true,"cancel fixture",null,null),tools,DebugPrinter.NONE,List::of)
                .withTasks(new TaskStore(data));
    }
    @Test void cancelledModelRequestCanContinueSameTaskWithoutExitingCli() throws Exception {
        var text=new StringWriter();var errors=new StringWriter();var output=new PrintWriter(text);
        try(var input=new ConsoleInput(new BufferedReader(new StringReader("original\n继续\nnext\n/exit\n")),output)) {
            var calls=new AtomicInteger();
            assertEquals(0,launcher(input,output,new PrintWriter(errors),request->{
                assertFalse(Thread.currentThread().isInterrupted());
                if(calls.incrementAndGet()==1){input.cancelOperation();throw new LlmException(LlmException.Kind.INTERRUPTED,"fixture");}
                return new LlmResponse(Message.assistant("answer"),"stop",null);
            },new ToolRegistry()).run());
            assertEquals(3,calls.get());
        }
        var session=new JsonSessionStore(data).list().sessions().get(0);
        var tasks=new TaskStore(data).list(session.id());assertEquals(2,tasks.size());
        assertTrue(tasks.stream().allMatch(TaskState::completed));assertTrue(tasks.stream().anyMatch(t->t.attempts==2));
        assertEquals(4,session.messages().size());assertTrue(text.toString().contains("CodeY 仍在运行"));
        assertFalse(errors.toString().contains("LLM error [INTERRUPTED]"));
    }
    @Test void embeddedChatCliAlsoKeepsRunningAfterUserCancellation() throws Exception {
        var output=new PrintWriter(new StringWriter());var session=new Session("base");var calls=new AtomicInteger();
        try(var input=new ConsoleInput(new BufferedReader(new StringReader("first\nsecond\n/exit\n")),output)) {
            LlmClient client=request->{
                if(calls.incrementAndGet()==1){input.cancelOperation();throw new LlmException(LlmException.Kind.INTERRUPTED,"fixture");}
                assertFalse(Thread.currentThread().isInterrupted());return new LlmResponse(Message.assistant("answer"),"stop",null);
            };
            new ChatCli(new io.github.jackfeicoder.codey.agent.AgentLoop(client,new ToolRegistry()),session,
                    new ToolContext(io.github.jackfeicoder.codey.workspace.WorkspaceBinding.none()),input,output,output).run();
        }
        assertEquals(2,calls.get());assertEquals(List.of(Message.system("base"),Message.user("second"),Message.assistant("answer")),session.messages());
    }
    @Test void cancelledBlockingToolPersistsResultAndNextQuestionStillWorks() throws Exception {
        var text=new StringWriter();var output=new PrintWriter(text);var called=new CountDownLatch(1);
        try(var input=new ConsoleInput(new BufferedReader(new StringReader("original\nnext\n/exit\n")),output)) {
            Tool tool=new Tool() {
                public ToolDefinition definition(){return new ToolDefinition("blocking_fixture","fixture",new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("type","object"));}
                public ToolAccess access(){return ToolAccess.READ_ONLY;}
                public void validateArguments(com.fasterxml.jackson.databind.JsonNode arguments,ToolContext context){}
                public String execute(com.fasterxml.jackson.databind.JsonNode arguments,ToolContext context)throws InterruptedException{called.countDown();Thread.sleep(10000);return "unexpected";}
            };
            var cancelThread=new Thread(()->{try{if(called.await(5,TimeUnit.SECONDS))input.cancelOperation();}catch(InterruptedException ignored){}});
            cancelThread.start();var count=new AtomicInteger();
            try {
                assertEquals(0,launcher(input,output,new PrintWriter(new StringWriter()),request->{
                    if(count.incrementAndGet()==1)return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("blocking","blocking_fixture","{}"))),"tool_calls",null);
                    assertFalse(Thread.currentThread().isInterrupted());return new LlmResponse(Message.assistant("answer"),"stop",null);
                },new ToolRegistry().register(tool)).run());
            }finally{cancelThread.interrupt();cancelThread.join(2000);}
        }
        var session=new JsonSessionStore(data).list().sessions().get(0);
        var stopped=new TaskStore(data).list(session.id()).stream().filter(t->t.status==TaskState.Status.INTERRUPTED).findFirst().orElseThrow();
        try(var task=new TaskStore(data).open(session.id(),stopped.id)) {
            assertTrue(task.state().messages.get(2).content().contains("INTERRUPTED"));
        }
        assertEquals(2,session.messages().size());
    }
    @Test void fallbackSystemSigintKeepsRealChildJvmAlive() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),SignalProbe.class.getName(),data.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);assertTrue(output.contains("CTRL_C_SURVIVED"),output);
        }finally{process.destroyForcibly();}
    }
    /** 独立 JVM 内触发真实 Signal API；父测试进程的 SIGINT 处理器完全不变。 */
    public static class SignalProbe {
        public static void main(String[] args)throws Exception {
            Path data=Path.of(args[0]);var count=new AtomicInteger();var output=new PrintWriter(System.out,true);
            try(var input=ConsoleInput.open(new BufferedReader(new StringReader("first\nsecond\n/exit\n")),output,List::of)) {
                LlmClient client=request->{
                    if(count.incrementAndGet()==1) {
                        new Thread(()->{try{
                            Thread.sleep(100);
                            Class<?> signal=Class.forName("sun.misc.Signal");
                            signal.getMethod("raise",signal).invoke(null,signal.getConstructor(String.class).newInstance("INT"));
                        }catch(Exception error){throw new AssertionError(error);}}).start();
                        try{return new CompletableFuture<LlmResponse>().get();}
                        catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new LlmException(LlmException.Kind.INTERRUPTED,"fixture");}
                        catch(ExecutionException failed){throw new AssertionError(failed);}
                    }
                    if(Thread.currentThread().isInterrupted())throw new AssertionError("Leaked interrupt");
                    return new LlmResponse(Message.assistant("second answer"),"stop",null);
                };
                int exit=new SessionLauncher(data,new SessionSettings(data,null,false),new JsonSessionStore(data),
                        name->new SessionLauncher.ModelChoice("fixture",client,client),()->"base",input,output,output,
                        new SessionLauncher.Options(null,true,"signal",null,null),new ToolRegistry(),DebugPrinter.NONE,List::of)
                        .withTasks(new TaskStore(data)).run();
                if(exit!=0 || count.get()!=2)throw new AssertionError("CLI exited early");
            }
            System.out.println("CTRL_C_SURVIVED");
        }
    }
}
