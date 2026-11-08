package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.config.ToolSchedulingSettings;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用屏障与可控读取验证真实并发、顺序边界、中断与超时，不依赖磁盘速度或网络。 */
@Timeout(12)
class ToolSchedulerTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @FunctionalInterface interface Body {String run(JsonNode args) throws InterruptedException;}
    static Tool tool(String name,boolean parallel,Body body) {
        return new Tool() {
            public ToolDefinition definition(){return new ToolDefinition(name,"fixture",JSON.createObjectNode().put("type","object"));}
            public ToolAccess access(){return ToolAccess.READ_ONLY;}
            public ToolExecutionMode executionMode(){return parallel?ToolExecutionMode.PARALLEL_READ:ToolExecutionMode.SERIAL;}
            public void validateArguments(JsonNode args,ToolContext context){}
            public String execute(JsonNode args,ToolContext context)throws InterruptedException{return body.run(args);}
        };
    }
    static ToolCall call(int id,String name){return new ToolCall("id-"+id,name,"{\"n\":"+id+"}");}
    static List<ToolCall> calls(int count,String name){return java.util.stream.IntStream.range(0,count).mapToObj(i->call(i,name)).toList();}
    static ToolContext context(){return new ToolContext(WorkspaceBinding.none());}
    static Set<String> offered(ToolRegistry registry){return registry.definitions().stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet());}
    static class Events implements ToolScheduler.Observer {
        final List<String> starts=new ArrayList<>(),finishes=new ArrayList<>();
        final Set<Long> threads=new HashSet<>();int groups;
        public void started(ToolCall call){threads.add(Thread.currentThread().getId());starts.add(call.id());}
        public void completed(ToolResult result){threads.add(Thread.currentThread().getId());finishes.add(result.callId());}
        public void parallelGroup(int count,int parallelism){threads.add(Thread.currentThread().getId());groups++;}
    }
    @Test void respectsLimitRefillsOnCompletionAndReturnsOriginalOrder()throws Exception {
        var twoStarted=new CountDownLatch(2);var releaseSlow=new CountDownLatch(1);var releaseFast=new CountDownLatch(1);var third=new CountDownLatch(1);
        var active=new AtomicInteger();var peak=new AtomicInteger();var events=new Events();
        var registry=new ToolRegistry().register(tool("read",true,args->{
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);twoStarted.countDown();
            try {int n=args.path("n").asInt();if(n==0)releaseSlow.await();if(n==1)releaseFast.await();if(n==2)third.countDown();return "value-"+n;}
            finally{active.decrementAndGet();}
        }));
        var result=new AtomicReference<List<ToolResult>>();var failure=new AtomicReference<Throwable>();
        var driver=new Thread(()->{try{result.set(new ToolScheduler(registry,new ToolSchedulingSettings(2,5)).execute(calls(6,"read"),offered(registry),context(),events));}catch(Throwable e){failure.set(e);}});
        driver.start();try {
            assertTrue(twoStarted.await(3,TimeUnit.SECONDS));assertEquals(2,active.get());releaseFast.countDown();
            assertTrue(third.await(3,TimeUnit.SECONDS));assertTrue(driver.isAlive());releaseSlow.countDown();driver.join(3000);
            assertFalse(driver.isAlive());assertNull(failure.get());assertEquals(2,peak.get());
            assertEquals(calls(6,"read").stream().map(ToolCall::id).toList(),result.get().stream().map(ToolResult::callId).toList());
            assertTrue(events.finishes.indexOf("id-1")<events.finishes.indexOf("id-0"));assertEquals(Set.of(driver.getId()),events.threads);assertEquals(1,events.groups);
        } finally{releaseSlow.countDown();releaseFast.countDown();driver.interrupt();driver.join(3000);}
    }
    @Test void serialOperationIsBarrierAndLaterReadsSeeItsResult()throws Exception {
        var arrived=new CountDownLatch(2);var value=new AtomicInteger();var active=new AtomicInteger();
        var registry=new ToolRegistry().register(tool("before",true,args->{active.incrementAndGet();try{arrived.countDown();assertTrue(arrived.await(3,TimeUnit.SECONDS));return "old";}finally{active.decrementAndGet();}}))
                .register(tool("edit",false,args->{assertEquals(0,active.get());value.set(9);return "edited";}))
                .register(tool("after",true,args->{assertEquals(9,value.get());return "new";}));
        var result=new ToolScheduler(registry,new ToolSchedulingSettings()).execute(List.of(call(0,"before"),call(1,"before"),call(2,"edit"),call(3,"after")),offered(registry),context(),new Events());
        assertEquals(List.of("old","old","edited","new"),result.stream().map(ToolResult::output).toList());
    }
    @Test void readOnlyWithoutParallelDeclarationRemainsOnCallerThread()throws Exception {
        long caller=Thread.currentThread().getId();var registry=new ToolRegistry().register(tool("stateful",false,args->{assertEquals(caller,Thread.currentThread().getId());return "ok";}));
        var events=new Events();new ToolScheduler(registry,new ToolSchedulingSettings()).execute(calls(3,"stateful"),offered(registry),context(),events);
        assertEquals(0,events.groups);assertEquals(3,events.finishes.size());
    }
    @Test void parallelismOneSerializesReadsAndDoesNotChangeResults()throws Exception {
        var count=new AtomicInteger();var registry=new ToolRegistry().register(tool("read",true,args->""+count.incrementAndGet()));
        var events=new Events();var result=new ToolScheduler(registry,new ToolSchedulingSettings(1,5)).execute(calls(4,"read"),offered(registry),context(),events);
        assertEquals(List.of("1","2","3","4"),result.stream().map(ToolResult::output).toList());assertEquals(0,events.groups);
    }
    @Test void errorFromOneReadDoesNotCancelIndependentReadsOrExposeException()throws Exception {
        var registry=new ToolRegistry().register(tool("read",true,args->{if(args.path("n").asInt()==1)throw new IllegalStateException("fixture-secret");return "ok";}));
        var result=new ToolScheduler(registry,new ToolSchedulingSettings()).execute(calls(3,"read"),offered(registry),context(),new Events());
        assertEquals(ToolResult.Status.SUCCESS,result.get(0).status());assertEquals(ToolResult.ErrorCode.EXECUTION_FAILED,result.get(1).errorCode());assertFalse(result.get(1).output().contains("fixture-secret"));assertEquals(ToolResult.Status.SUCCESS,result.get(2).status());
    }
    @Test void unofferedToolRemainsUnavailableAfterSerialRegistration()throws Exception {
        var reads=new AtomicInteger();var registry=new ToolRegistry();
        registry.register(tool("summon",false,args->{registry.register(tool("new_read",true,a->{reads.incrementAndGet();return "ok";}));return "ready";}));
        var result=new ToolScheduler(registry,new ToolSchedulingSettings()).execute(List.of(call(0,"summon"),call(1,"new_read")),Set.of("summon"),context(),new Events());
        assertEquals(ToolResult.ErrorCode.UNKNOWN_TOOL,result.get(1).errorCode());assertEquals(0,reads.get());
    }
    @Test void timeoutCancelsCooperativeReadAndKeepsOtherResultsBeforeBarrier()throws Exception {
        var cancelled=new CountDownLatch(1);var active=new AtomicInteger();
        var registry=new ToolRegistry().register(tool("read",true,args->{active.incrementAndGet();try{if(args.path("n").asInt()==0)Thread.sleep(10000);return "ok";}catch(InterruptedException e){cancelled.countDown();throw e;}finally{active.decrementAndGet();}}))
                .register(tool("barrier",false,args->{assertEquals(0,active.get());return "after";}));
        var result=new ToolScheduler(registry,new ToolSchedulingSettings(2,1)).execute(List.of(call(0,"read"),call(1,"read"),call(2,"barrier")),offered(registry),context(),new Events());
        assertEquals(ToolResult.ErrorCode.TOOL_TIMEOUT,result.get(0).errorCode());assertEquals(ToolResult.Status.SUCCESS,result.get(1).status());assertEquals("after",result.get(2).output());assertEquals(0,cancelled.getCount());assertFalse(Thread.currentThread().isInterrupted());
    }
    @Test void cancellationPreservesFlagKnownResultsAndDoesNotStartNextOperation()throws Exception {
        var started=new CountDownLatch(1);var finished=new CountDownLatch(1);var stopped=new CountDownLatch(1);var serial=new AtomicInteger();
        var registry=new ToolRegistry().register(tool("slow",true,args->{started.countDown();try{Thread.sleep(10000);return "late";}finally{stopped.countDown();}}))
                .register(tool("fast",true,args->"known")).register(tool("serial",false,args->{serial.incrementAndGet();return "bad";}));
        var events=new Events(){public void completed(ToolResult result){super.completed(result);if(result.name().equals("fast"))finished.countDown();}};
        var flag=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        var driver=new Thread(()->{try{new ToolScheduler(registry,new ToolSchedulingSettings()).execute(List.of(call(0,"slow"),call(1,"fast"),call(2,"serial")),offered(registry),context(),events);}catch(Throwable e){failure.set(e);flag.set(Thread.currentThread().isInterrupted());}});
        driver.start();try {
            assertTrue(started.await(3,TimeUnit.SECONDS));assertTrue(finished.await(3,TimeUnit.SECONDS));driver.interrupt();driver.join(3000);
            assertFalse(driver.isAlive());assertInstanceOf(InterruptedException.class,failure.get());assertTrue(flag.get());assertTrue(stopped.await(1,TimeUnit.SECONDS));assertEquals(0,serial.get());assertEquals(List.of("id-1"),events.finishes);
        }finally{driver.interrupt();driver.join(3000);}
    }
    @Test void uncooperativeReadHaltsTaskBeforeAnyLaterSerialOperation()throws Exception {
        var release=new AtomicBoolean();var exited=new CountDownLatch(1);var serial=new AtomicInteger();
        var registry=new ToolRegistry().register(tool("read",true,args->{try{while(!release.get()){Thread.interrupted();java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));}return "late";}finally{exited.countDown();}}))
                .register(tool("serial",false,args->{serial.incrementAndGet();return "bad";}));
        try {
            var error=assertThrows(ToolException.class,()->new ToolScheduler(registry,new ToolSchedulingSettings(1,1)).execute(List.of(call(0,"read"),call(1,"serial")),offered(registry),context(),new Events()));
            assertEquals(ToolResult.ErrorCode.EXECUTION_FAILED,error.code());assertEquals(0,serial.get());
        }finally{release.set(true);assertTrue(exited.await(2,TimeUnit.SECONDS));}
    }
    @Test void observerFailureCancelsOutstandingReadAndNoLaterToolRuns()throws Exception {
        var slowStarted=new CountDownLatch(1);var stopped=new CountDownLatch(1);var serial=new AtomicInteger();
        var registry=new ToolRegistry().register(tool("slow",true,args->{slowStarted.countDown();try{Thread.sleep(10000);return "late";}finally{stopped.countDown();}}))
                .register(tool("fast",true,args->{assertTrue(slowStarted.await(3,TimeUnit.SECONDS));return "ok";}))
                .register(tool("serial",false,args->{serial.incrementAndGet();return "bad";}));
        var events=new Events(){public void completed(ToolResult result){throw new IllegalStateException("journal failed");}};
        assertThrows(IllegalStateException.class,()->new ToolScheduler(registry,new ToolSchedulingSettings()).execute(List.of(call(0,"slow"),call(1,"fast"),call(2,"serial")),offered(registry),context(),events));
        assertTrue(stopped.await(1,TimeUnit.SECONDS));assertEquals(0,serial.get());
    }
    @Test void workerJvmErrorIsNotConvertedToOrdinaryToolFailure()throws Exception {
        var registry=new ToolRegistry().register(tool("fatal",true,args->{throw new AssertionError("fatal");}));
        assertThrows(AssertionError.class,()->new ToolScheduler(registry,new ToolSchedulingSettings()).execute(List.of(call(0,"fatal")),offered(registry),context(),new Events()));
    }
    @Test void maximumGroupAndUnknownToolsAreBoundedAndPaired()throws Exception {
        var registry=new ToolRegistry().register(tool("read",true,args->"ok"));
        var scheduler=new ToolScheduler(registry,new ToolSchedulingSettings());var result=scheduler.execute(calls(16,"read"),offered(registry),context(),new Events());
        assertEquals(16,result.size());assertThrows(IllegalArgumentException.class,()->scheduler.execute(calls(17,"read"),offered(registry),context(),new Events()));
        assertEquals(ToolResult.ErrorCode.UNKNOWN_TOOL,scheduler.execute(List.of(call(0,"missing")),Set.of("missing"),context(),new Events()).get(0).errorCode());
    }
    @Test void globalWorkersStayBoundedAndQueuedTimeoutsDoNotRunReads()throws Exception {
        var eightStarted=new CountDownLatch(8);var release=new CountDownLatch(1);var secondRuns=new AtomicInteger();
        var first=new ToolRegistry().register(tool("hold",true,args->{eightStarted.countDown();release.await();return "ok";}));
        var second=new ToolRegistry().register(tool("queued",true,args->{secondRuns.incrementAndGet();return "unexpected";}));
        var failure=new AtomicReference<Throwable>();var driver=new Thread(()->{try{new ToolScheduler(first,new ToolSchedulingSettings(8,5)).execute(calls(8,"hold"),offered(first),context(),new Events());}catch(Throwable e){failure.set(e);}});
        driver.start();try {
            assertTrue(eightStarted.await(3,TimeUnit.SECONDS));
            var results=new ToolScheduler(second,new ToolSchedulingSettings(8,1)).execute(calls(8,"queued"),offered(second),context(),new Events());
            assertTrue(results.stream().allMatch(r->r.errorCode()==ToolResult.ErrorCode.TOOL_TIMEOUT));assertEquals(0,secondRuns.get());
            release.countDown();driver.join(3000);assertFalse(driver.isAlive());assertNull(failure.get());
        }finally{release.countDown();driver.interrupt();driver.join(3000);}
    }
    @Test void mutatedAccessCannotOptIntoParallelExecution() {
        Tool invalid=new Tool(){public ToolDefinition definition(){return tool("write",false,a->"").definition();}public ToolAccess access(){return ToolAccess.FILE_MUTATION;}public ToolExecutionMode executionMode(){return ToolExecutionMode.PARALLEL_READ;}public void validateArguments(JsonNode args,ToolContext context){}public String execute(JsonNode args,ToolContext context){return "";}};
        assertThrows(IllegalArgumentException.class,()->new ToolRegistry().register(invalid));
    }
}
