package io.github.jackfeicoder.codey.tool;

import io.github.jackfeicoder.codey.config.ToolSchedulingSettings;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 连续独立读取并行，其他操作构成串行边界。观察回调、注册表访问和结果落盘都在调用线程执行。 */
public final class ToolScheduler {
    public static final int MAX_CALLS = 16;
    /** Java I/O 不保证立即响应中断；超过清理宽限就终止本次任务，不再越过写入边界。 */
    private static final long STOP_GRACE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** 全进程硬上限 8，避免反复取消不响应中断的 I/O 后不断创建新线程。队列同样有界。 */
    private static final ThreadPoolExecutor READERS = readers();
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final ToolSchedulingSettings settings;

    public interface Observer {
        void started(ToolCall call);
        void completed(ToolResult result);
        default void parallelGroup(int size,int parallelism) { }
    }
    public ToolScheduler(ToolRegistry registry,ToolSchedulingSettings settings) {
        this.registry=Objects.requireNonNull(registry);this.settings=Objects.requireNonNull(settings);
        this.executor=new ToolExecutor(registry);
    }
    /** offered 为本轮模型请求的名称快照，召唤后出现的新工具仍须等下一轮再调用。 */
    public List<ToolResult> execute(List<ToolCall> calls,Set<String> offered,ToolContext context,Observer observer) throws InterruptedException {
        calls=List.copyOf(calls);offered=Set.copyOf(offered);
        if(calls.size()>MAX_CALLS)throw new IllegalArgumentException("Tool-call group exceeds scheduler limit.");
        var results=new ArrayList<ToolResult>();
        for(int index=0;index<calls.size();) {
            interrupted();
            var group=new ArrayList<ReadCall>();
            int end=index;
            while(end<calls.size()) {
                var call=calls.get(end);
                var entry=offered.contains(call.name())?registry.find(call.name()).orElse(null):null;
                if(entry==null || entry.access()!=ToolAccess.READ_ONLY || entry.executionMode()!=ToolExecutionMode.PARALLEL_READ)break;
                group.add(new ReadCall(call,entry));end++;
            }
            if(!group.isEmpty()) {
                if(group.size()>1 && settings.maxParallel()>1)observer.parallelGroup(group.size(),settings.maxParallel());
                results.addAll(readGroup(group,context,observer));index=end;
            } else {
                var call=calls.get(index++);observer.started(call);interrupted();
                var result=offered.contains(call.name())?executor.execute(call,context):error(call,ToolResult.ErrorCode.UNKNOWN_TOOL,
                        "Tool was not offered in this request. Summon it and use it in the next request.",System.nanoTime());
                observer.completed(result);interrupted();results.add(result);
            }
        }
        interrupted();return List.copyOf(results);
    }
    /** 只提交最多 N 个，完成一个补一个；任务事件可按完成顺序记录，返回消息始终按原调用顺序。 */
    private List<ToolResult> readGroup(List<ReadCall> calls,ToolContext context,Observer observer) throws InterruptedException {
        var done=new ArrayBlockingQueue<ReadJob>(MAX_CALLS);
        var all=new ArrayList<ReadJob>();var pending=new LinkedHashSet<ReadJob>();var recorded=new HashSet<ReadJob>();
        var results=new ToolResult[calls.size()];int next=0;Throwable failure=null;
        try {
            while(next<calls.size() || !pending.isEmpty()) {
                interrupted();
                while(next<calls.size() && pending.size()<settings.maxParallel()) {
                    interrupted();var read=calls.get(next);observer.started(read.call());interrupted();
                    var job=new ReadJob(next++,read,context,done);all.add(job);pending.add(job);
                    try {READERS.execute(job);}
                    catch(RejectedExecutionException busy) {
                        job.cancel(false);job.stopped.countDown();pending.remove(job);
                        record(job,error(read.call(),ToolResult.ErrorCode.TOOL_SCHEDULER_BUSY,"Read workers are busy; no read was started. Retry only if needed.",job.submitted),results,recorded,observer);
                    }
                }
                if(pending.isEmpty())continue;
                long now=System.nanoTime();
                for(var job:List.copyOf(pending)) {
                    if(now-job.submitted>=TimeUnit.SECONDS.toNanos(settings.readTimeoutSeconds()) && !job.isDone()) {
                        job.cancel(true);if(READERS.remove(job))job.stopped.countDown();pending.remove(job);
                        record(job,error(job.read.call(),ToolResult.ErrorCode.TOOL_TIMEOUT,"Local read exceeded its scheduling deadline; cancellation requested.",job.submitted),results,recorded,observer);
                        awaitStopped(job); // 没确认停止前不补新调用，也不进入后续写操作。
                    }
                }
                if(pending.isEmpty())continue;
                long wait=pending.stream().mapToLong(j->Math.max(1,TimeUnit.SECONDS.toNanos(settings.readTimeoutSeconds())-(System.nanoTime()-j.submitted))).min().orElse(1);
                var job=done.poll(wait,TimeUnit.NANOSECONDS);
                if(job==null || !pending.remove(job))continue;
                ToolResult result=outcome(job);awaitStopped(job);
                record(job,result,results,recorded,observer);
            }
            return List.of(results);
        } catch(InterruptedException | RuntimeException | Error stopped) {failure=stopped;throw stopped;}
        finally {
            for(var job:all)if(!job.isDone()){job.cancel(true);if(READERS.remove(job))job.stopped.countDown();}
            boolean interrupted=Thread.interrupted();long deadline=System.nanoTime()+STOP_GRACE_NANOS;
            try {
                for(var job:all) {
                    try {job.stopped.await(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
                    catch(InterruptedException again){interrupted=true;}
                }
                // Ctrl+C 或日志异常时尽量保留已经返回的结果；未取得结果的调用仍由恢复模块标为未知。
                if(failure!=null)for(var job:all)if(job.isDone() && !job.isCancelled() && !recorded.contains(job)) {
                    try {record(job,outcome(job),results,recorded,observer);}
                    catch(RuntimeException | Error | InterruptedException journalFailure){if(journalFailure!=failure)failure.addSuppressed(journalFailure);}
                }
            } finally {if(interrupted || failure instanceof InterruptedException)Thread.currentThread().interrupt();READERS.purge();}
        }
    }
    private static void record(ReadJob job,ToolResult result,ToolResult[] results,Set<ReadJob> recorded,Observer observer) {
        results[job.index]=result;recorded.add(job);observer.completed(result);
    }
    private static ToolResult outcome(ReadJob job) throws InterruptedException {
        try {return job.get();}
        catch(ExecutionException failed) {
            if(failed.getCause() instanceof Error fatal)throw fatal;
            return error(job.read.call(),ToolResult.ErrorCode.EXECUTION_FAILED,"Read execution failed.",job.submitted);
        } catch(CancellationException cancelled){return error(job.read.call(),ToolResult.ErrorCode.INTERRUPTED,"Read execution was cancelled.",job.submitted);}
    }
    private static void awaitStopped(ReadJob job) throws InterruptedException {
        // 被从队列中移除的 Future 不会再进入 run/finally。
        if(job.isCancelled() && READERS.remove(job))job.stopped.countDown();
        if(!job.stopped.await(STOP_GRACE_NANOS,TimeUnit.NANOSECONDS))
            throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED,"Read worker did not stop after cancellation; task halted before further tools or model requests.");
    }
    private static void interrupted() throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException();}
    private static ToolResult error(ToolCall call,ToolResult.ErrorCode code,String text,long start) {
        return new ToolResult(call.id(),call.name(),ToolResult.Status.ERROR,text,code,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),false);
    }
    private static ThreadPoolExecutor readers() {
        var ids=new AtomicInteger();
        var pool=new ThreadPoolExecutor(ToolSchedulingSettings.MAX_PARALLEL,ToolSchedulingSettings.MAX_PARALLEL,30,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CALLS),r->{var thread=new Thread(r,"codey-read-"+ids.incrementAndGet());thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);return pool;
    }
    private record ReadCall(ToolCall call,ToolRegistry.RegisteredTool entry) { }
    private final class ReadJob extends FutureTask<ToolResult> {
        private final int index;private final ReadCall read;
        private final long submitted=System.nanoTime();
        private final CountDownLatch stopped=new CountDownLatch(1);
        private final BlockingQueue<ReadJob> done;
        private ReadJob(int index,ReadCall read,ToolContext context,BlockingQueue<ReadJob> done) {
            super(()->executor.execute(read.call(),context,read.entry()));this.index=index;this.read=read;this.done=done;
        }
        @Override public void run(){try{super.run();}finally{stopped.countDown();}}
        @Override protected void done(){done.add(this);}
    }
}
