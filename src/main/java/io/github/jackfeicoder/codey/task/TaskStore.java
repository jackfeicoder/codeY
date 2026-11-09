package io.github.jackfeicoder.codey.task;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 会话内任务存储：先强制落盘事件，再更新派生检查点；恢复以日志为准。 */
public final class TaskStore {
    static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final long MAX_LOG = 64L * 1024 * 1024;
    private final Path root;
    public TaskStore(Path dataDirectory) { root = dataDirectory.toAbsolutePath().normalize().resolve("sessions"); }

    /** 创建任务要求会话已经存在；不会独立创建另一个会话目录。 */
    public TaskRun create(String sessionId, WorkspaceBinding workspace, String profile, String goal,
                          String instructions, List<Message> history) {
        String id = UUID.randomUUID().toString();
        Path directory = directory(sessionId, id, true);
        var run = lock(directory);
        try {
            var state = new TaskState();
            state.id=id; state.sessionId=sessionId; state.workspace=workspace; state.profile=profile;
            state.goal=goal; state.instructions=instructions; state.createdAt=Instant.now().toString();
            state.anchorCount=history.size(); state.anchorHash=hash(history);
            run.initialize(state);
            return run;
        } catch (RuntimeException failed) { run.close(); throw failed; }
    }

    public TaskRun open(String sessionId, String taskId) {
        var run = lock(directory(sessionId, taskId, false));
        try { run.load(); return run; }
        catch (RuntimeException failed) { run.close(); throw failed; }
    }

    /** 列出当前会话全部任务；损坏日志明确报错而非把它当作已完成。 */
    public List<TaskState> list(String sessionId) {
        validId(sessionId);
        Path tasks=root.resolve(sessionId).resolve("tasks");
        if (!Files.exists(tasks, LinkOption.NOFOLLOW_LINKS)) return List.of();
        check(tasks);
        var states=new ArrayList<TaskState>();
        try(var entries=Files.list(tasks)) {
            for(Path path:entries.toList()) if(SessionSnapshot.validId(path.getFileName().toString())) {
                check(path); var state=read(path.resolve("events.jsonl")).state();
                if (!sessionId.equals(state.sessionId) || !path.getFileName().toString().equals(state.id)) throw new TaskException("任务目录与日志 ID 不一致。");
                states.add(state);
            }
        } catch(java.io.IOException failed) { throw new TaskException("读取任务列表失败。"); }
        states.sort(Comparator.comparing((TaskState s)->s.updatedAt).reversed());
        return List.copyOf(states);
    }

    private Path directory(String sessionId,String taskId,boolean create) {
        validId(sessionId);validId(taskId);
        Path session=root.resolve(sessionId);check(session);
        Path tasks=session.resolve("tasks");
        try {
            if(create && !Files.exists(tasks,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(tasks);
            check(tasks);
            Path directory=tasks.resolve(taskId);
            if(create)Files.createDirectory(directory);
            check(directory);return directory;
        } catch(java.io.IOException failed){throw new TaskException("创建任务目录失败。");}
    }

    /** 拒绝链接和 Windows junction，逐级验证，清理路径同样使用此边界。 */
    void check(Path path) {
        Path absolute=path.toAbsolutePath().normalize();
        if(!absolute.startsWith(root))throw new TaskException("任务路径越界。");
        Path current=root;
        try {
            Path trusted=root.getParent().toRealPath();
            for(Path part:root.relativize(absolute)) {
                verifyDirectory(current,trusted);current=current.resolve(part);
            }
            verifyDirectory(current,trusted);
        } catch(java.io.IOException failed){throw new TaskException("任务目录不存在或包含链接。");}
    }
    private void verifyDirectory(Path path,Path trusted) throws java.io.IOException {
        Path expected=trusted.resolve(root.getParent().relativize(path)).normalize();
        if(Files.isSymbolicLink(path) || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)
                || !path.toRealPath().equals(expected)) throw new java.io.IOException();
    }
    private TaskRun lock(Path directory) {
        FileChannel channel=null;
        try {
            check(directory);Path file=directory.resolve("task.lock");rejectLink(file);
            channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock();
            if(lock==null)throw new TaskException("任务正在另一处运行。");
            return new TaskRun(this,directory,channel,lock);
        } catch(Exception failed) {
            if(channel!=null)try{channel.close();}catch(Exception ignored){}
            throw new TaskException("任务正在运行，或任务锁不可用。");
        }
    }
    static void validId(String id){if(!SessionSnapshot.validId(id))throw new TaskException("任务与会话 ID 必须为完整 UUID。");}
    static void rejectLink(Path file) throws java.io.IOException {
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().getParent().equals(file.getParent().toRealPath())))throw new java.io.IOException();
    }
    static String hash(List<Message> messages) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(messages)));}
        catch(Exception failed){throw new TaskException("计算任务历史指纹失败。");}
    }

    /** seq 检测丢失/重复事件；仅允许末尾未写完的一行，恢复获得锁后截除该残片。 */
    record Loaded(TaskState state,long validBytes) { }
    static Loaded read(Path file) {
        try {
            rejectLink(file);
            byte[] bytes;
            try(var input=Files.newInputStream(file)){bytes=input.readNBytes((int)MAX_LOG+1);}
            if(bytes.length>MAX_LOG)throw new java.io.IOException();
            TaskState state=null;int start=0;
            for(int i=0;i<bytes.length;i++) if(bytes[i]=='\n') {
                Event event=JSON.readValue(Arrays.copyOfRange(bytes,start,i),Event.class);
                state=apply(state,event);start=i+1;
            }
            if(state==null)throw new java.io.IOException();
            return new Loaded(state,start);
        }catch(Exception failed){throw new TaskException("任务日志损坏、超限或不可读；原文件保留。");}
    }
    /** 单条增量事件；消息正文只写一次，不在每个事件复制整个历史。 */
    public record Event(long sequence,String time,String type,Message message,String detail,
                        TaskState seed,List<TaskState.SkillRef> skills,Integer anchorCount,String anchorHash) { }
    static TaskState apply(TaskState state,Event e) {
        if(e==null || e.type()==null || e.time()==null || e.sequence()!=(state==null?1:state.sequence+1))throw new TaskException("任务事件序号错误。");
        if(state==null) {
            if(!e.type().equals("CREATE") || e.seed()==null)throw new TaskException("任务缺少创建事件。");
            state=e.seed();validId(state.id);validId(state.sessionId);
            if(state.schemaVersion!=1 || state.workspace==null || state.profile==null || state.profile.isBlank() || state.instructions==null
                    || state.anchorCount<1 || state.anchorHash==null || state.createdAt==null)throw new TaskException("任务创建记录不完整。");
            state.messages=new ArrayList<>(List.of(Message.user(state.goal)));
            state.status=TaskState.Status.RUNNING;state.attempts=1;
        } else switch(e.type()) {
            case "REQUEST" -> state.modelCalls++;
            case "ASSISTANT" -> {state.messages.add(Objects.requireNonNull(e.message()));
                if(e.message().toolCalls().isEmpty())state.status=TaskState.Status.COMMIT_PENDING;}
            case "TOOL_INTENT" -> {state.started.add(e.detail());state.toolCalls++;}
            case "PLAN_LINK" -> {try{state.plan=JSON.readValue(e.detail(),TaskState.PlanLink.class);}catch(Exception invalid){throw new TaskException("任务计划关联损坏。");}}
            case "TOOL_RESULT", "RECOVERY_RESULT" -> {
                state.messages.add(Objects.requireNonNull(e.message()));
                orderToolResults(state); // 事件保留实际完成顺序，派生消息按原调用顺序，恢复与提交比较一致。
            }
            case "SKILLS" -> state.skills=List.copyOf(e.skills());
            case "RESUME" -> {state.status=TaskState.Status.RUNNING;state.reason="";state.attempts++;
                state.anchorCount=e.anchorCount();state.anchorHash=e.anchorHash();state.profile=e.detail();}
            case "STOP" -> {var parts=e.detail().split("\\|",2);state.status=TaskState.Status.valueOf(parts[0]);state.reason=parts.length==2?parts[1]:parts[0];}
            case "COMMITTED" -> {state.status=TaskState.Status.COMPLETED;state.reason="";}
            case "CLEANUP_WARNING" -> state.reason="任务已完成；临时目录或检查点清理失败，原文件保留。";
            default -> throw new TaskException("未知任务事件类型。");
        }
        state.sequence=e.sequence();state.updatedAt=e.time();return state;
    }

    /** 最后一个工具组可部分完成；缺失项由恢复补齐，再按同一顺序归位。 */
    private static void orderToolResults(TaskState state) {
        for(int i=state.messages.size()-1;i>=0;i--) {
            var message=state.messages.get(i);
            if(message.role()!=io.github.jackfeicoder.codey.session.MessageRole.ASSISTANT)continue;
            if(message.toolCalls().isEmpty())return;
            var order=new java.util.HashMap<String,Integer>();
            for(int n=0;n<message.toolCalls().size();n++)order.put(message.toolCalls().get(n).id(),n);
            state.messages.subList(i+1,state.messages.size()).sort(java.util.Comparator.comparingInt(m->order.getOrDefault(m.toolCallId(),Integer.MAX_VALUE)));
            return;
        }
    }

    /** 文件强制落盘后，同目录原子替换；不退化为可能损坏旧记录的覆盖写。 */
    static void atomic(Path file,Object value) throws java.io.IOException {
        rejectLink(file);Path temp=Files.createTempFile(file.getParent(),".task-",".tmp");
        try {
            byte[] data=JSON.writeValueAsBytes(value);
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temp);}
    }

    /** 删除范围严格限制为本任务 tmp，不跟随符号链接或 junction。 */
    void cleanup(Path directory) throws java.io.IOException {
        check(directory);Path tmp=directory.resolve("tmp").toAbsolutePath().normalize();
        if(!tmp.getParent().equals(directory))throw new java.io.IOException();
        if(Files.exists(tmp,LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(tmp,new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs) throws java.io.IOException {
                    if(!dir.toRealPath().equals(directory.toRealPath().resolve(directory.relativize(dir))))throw new java.io.IOException("Linked temporary directory.");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws java.io.IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
                @Override public FileVisitResult postVisitDirectory(Path dir,java.io.IOException error) throws java.io.IOException {if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
            });
        }
        Files.deleteIfExists(directory.resolve("checkpoint.json"));
    }
}
