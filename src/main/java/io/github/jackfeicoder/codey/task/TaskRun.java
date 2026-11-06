package io.github.jackfeicoder.codey.task;

import io.github.jackfeicoder.codey.session.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** 持锁的单任务执行记录；写失败立即阻止下一次模型/工具调用。 */
public final class TaskRun implements AutoCloseable {
    private final TaskStore store;
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private TaskState state;
    private Supplier<List<TaskState.SkillRef>> skills=List::of;
    TaskRun(TaskStore store,Path directory,FileChannel channel,FileLock lock) {
        this.store=store;this.directory=directory;this.lockChannel=channel;this.lock=lock;
    }
    public TaskState state(){return state;}
    public TaskRun withSkills(Supplier<List<TaskState.SkillRef>> value){skills=Objects.requireNonNull(value);return this;}
    void initialize(TaskState seed){event("CREATE",null,null,seed,null,null,null);tempDirectory();}
    void load() {
        var loaded=TaskStore.read(directory.resolve("events.jsonl"));state=loaded.state();
        if(!directory.getFileName().toString().equals(state.id)
                || !directory.getParent().getParent().getFileName().toString().equals(state.sessionId))throw new TaskException("任务 ID 不匹配。");
        try {
            // 尾部残片属于尚未持久化成功的事件，不应在后面直接追加合法 JSON。
            Path file=directory.resolve("events.jsonl");
            if(Files.size(file)>loaded.validBytes()) {
                Files.copy(file,directory.resolve("events-interrupted-"+UUID.randomUUID()+".bak"));
                try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.truncate(loaded.validBytes());channel.force(true);}
            }
        }catch(java.io.IOException failed){throw new TaskException("任务日志尾部修复失败；原文件保留。");}
    }
    /** 仅将宿主管理的临时目录交给命令工具；第三方服务仍可能使用自己的目录。 */
    public Path tempDirectory() {
        Path tmp=directory.resolve("tmp");
        try {
            store.check(directory);
            if(!Files.exists(tmp,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(tmp);
            store.check(tmp);return tmp;
        }catch(java.io.IOException failed){throw new TaskException("创建任务临时目录失败。");}
    }
    public String runtimeInstructions() {
        return state.instructions+"\n[任务执行与恢复]\n任务ID："+state.id+"\n临时文件目录："+tempDirectory()
                +"\n命令环境 CODEY_TASK_TMP_DIR 指向此目录，命令创建的临时脚本和中间数据优先放在这里；交付文件保留在用户指定位置。文件工具仍遵循工作区边界，临时目录不授予额外访问权限。"
                +"\n这是原任务的第 "+state.attempts+" 次执行。沿用下方当前任务消息及已记录工具结果继续，不从头重复。"
                +"\nrecovery_unknown 表示调用曾开始但未留下完整结果，可能已产生副作用；先检查实际状态，涉及发送/写入/删除等不要自动重放。"
                +"\nrecovery_not_started 表示尚未调度，可按当前目标决定是否调用。恢复不继承权限批准和 MCP 连接；旧工具名不代表当前已加载。\n";
    }
    /** 模型请求前保存技能快照，工具结束后也保存，覆盖 load_skill 随后中断的情况。 */
    public void captureSkills(){var refs=List.copyOf(skills.get());if(!refs.equals(state.skills))event("SKILLS",null,null,null,refs,null,null);}
    public void request(){captureSkills();event("REQUEST",null,null,null,null,null,null);}
    public void assistant(Message message){event("ASSISTANT",message,null,null,null,null,null);}
    public void toolStarted(String id){event("TOOL_INTENT",null,id,null,null,null,null);}
    public void toolResult(Message message){event("TOOL_RESULT",message,null,null,null,null,null);captureSkills();}
    public void stop(TaskState.Status status){stop(status,status.name());}
    /** reason 由宿主传入错误类别，不保存可能含凭证的异常全文。 */
    public void stop(TaskState.Status status,String reason){
        if(state.status!=TaskState.Status.COMMIT_PENDING && !state.completed())event("STOP",null,status.name()+"|"+reason,null,null,null,null);
    }

    /** 检查会话前缀，允许同会话后续成功轮次追加，不允许跨工作区/历史改写。 */
    public boolean alreadyCommitted(List<Message> history) {
        verifyAnchor(history);
        int end=state.anchorCount+state.messages.size();
        return history.size()>=end && history.subList(state.anchorCount,end).equals(state.messages);
    }
    private void verifyAnchor(List<Message> history) {
        if(history.size()<state.anchorCount || !TaskStore.hash(history.subList(0,state.anchorCount)).equals(state.anchorHash))
            throw new TaskException("会话历史与任务锚点不匹配，请选择原会话恢复。");
    }
    /** 恢复只补齐协议结果占位，不执行任何存下来的工具请求。 */
    public void resume(String profile,List<Message> history) {
        if(state.completed() || state.status==TaskState.Status.COMMIT_PENDING)throw new TaskException("该任务已完成或等待提交，无需再次请求模型。");
        verifyAnchor(history);
        // 只有最后一个工具组可能缺少结果；按原调用顺序补齐，保留已有结果。
        Set<String> returned=new HashSet<>();
        state.messages.stream().filter(m->m.role()==MessageRole.TOOL).forEach(m->returned.add(m.toolCallId()));
        var missing=state.messages.stream().flatMap(m->m.toolCalls().stream()).filter(c->!returned.contains(c.id())).toList();
        for(var call:missing) {
            boolean started=state.started.contains(call.id());
            String content="{\"source\":\"host_recovery\",\"status\":\""+(started?"recovery_unknown":"recovery_not_started")
                    +"\",\"output\":\""+(started?"No durable result recorded. The operation may have executed; verify state before any replay.":"This call was not dispatched before execution stopped.")+"\"}";
            event("RECOVERY_RESULT",Message.tool(call.id(),content),null,null,null,null,null);
        }
        event("RESUME",null,profile,null,null,history.size(),TaskStore.hash(history));
        tempDirectory();
    }
    /** 必须在完整消息组保存到 session.json 后调用；清理失败不撤销成功状态。 */
    public void committed() {
        if(state.status!=TaskState.Status.COMMIT_PENDING && !state.completed())throw new TaskException("任务尚无完整最终回答。");
        if(!state.completed())event("COMMITTED",null,null,null,null,null,null);
        try {store.cleanup(directory);}catch(Exception failed){event("CLEANUP_WARNING",null,null,null,null,null,null);}
    }

    private void event(String type,Message message,String detail,TaskState seed,List<TaskState.SkillRef> refs,Integer count,String hash) {
        // 中断后仍需记录停止状态，完成后恢复线程中断标志。
        boolean interrupted=Thread.interrupted();
        try {
            store.check(directory);
            var event=new TaskStore.Event(state==null?1:state.sequence+1,Instant.now().toString(),type,message,detail,seed,refs,count,hash);
            Path log=directory.resolve("events.jsonl");TaskStore.rejectLink(log);
            byte[] bytes=(TaskStore.JSON.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8);
            if(bytes.length>16*1024*1024 || (Files.exists(log)?Files.size(log):0)+bytes.length>64L*1024*1024)
                throw new TaskException("任务日志达到存储上限，已停止后续执行。");
            try(var channel=FileChannel.open(log,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            state=TaskStore.apply(state,event);
            TaskStore.atomic(directory.resolve("checkpoint.json"),state);
            // 列表投影不复制消息正文，原始过程在 events.jsonl 中永久保留。
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("id",state.id);metadata.put("sessionId",state.sessionId);metadata.put("goal",state.goal);
            metadata.put("status",state.status);metadata.put("reason",state.reason);metadata.put("updatedAt",state.updatedAt);
            metadata.put("attempts",state.attempts);metadata.put("modelCalls",state.modelCalls);metadata.put("toolCalls",state.toolCalls);
            TaskStore.atomic(directory.resolve("task.json"),metadata);
        }catch(TaskException failed){throw failed;}
        catch(Exception failed){throw new TaskException("任务进度写入失败；停止执行，已落盘事件仍可恢复。");}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    @Override public void close(){try{lock.release();}catch(Exception ignored){}try{lockChannel.close();}catch(Exception ignored){}}
}
