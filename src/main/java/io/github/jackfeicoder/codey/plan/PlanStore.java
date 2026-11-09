package io.github.jackfeicoder.codey.plan;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;

/** 会话内计划库：拒绝链接、限制大小、锁内序号校验和原子替换；不在工作区创建计划文档。 */
public final class PlanStore {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path directory;
    private final String sessionId;
    private final WorkspaceBinding workspace;
    public PlanStore(Path data,String sessionId,WorkspaceBinding workspace) {
        if(!io.github.jackfeicoder.codey.session.SessionSnapshot.validId(sessionId))throw new IllegalArgumentException("会话ID无效。");
        this.sessionId=sessionId;this.workspace=workspace;
        directory=data.toAbsolutePath().normalize().resolve("sessions").resolve(sessionId).resolve("plans");
    }
    public ExecutionPlan latest() {
        try {
            check(directory);if(!Files.exists(directory))return null;
            try(var entries=Files.list(directory)) {
                var files=entries.filter(p->p.getFileName().toString().endsWith(".json")).limit(101).toList();
                if(files.size()>100)throw new IllegalArgumentException("计划数量达到上限。");
                var plans=new ArrayList<ExecutionPlan>();for(var file:files)plans.add(read(file));
                return plans.stream().max(Comparator.comparing(p->p.updatedAt)).orElse(null);
            }
        }catch(Exception e){throw failure();}
    }
    public ExecutionPlan load(String id) {try{return read(file(id));}catch(Exception e){throw failure();}}
    public ExecutionPlan save(ExecutionPlan plan,long expected) {
        Path temp=null;
        try {
            plan.validate();verifyOwner(plan);check(directory);Files.createDirectories(directory);check(directory);
            Path lock=directory.resolve("plans.lock");check(lock);
            try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.tryLock()) {
                if(held==null)throw failure();Path file=file(plan.id);check(file);
                if(Files.exists(file)?read(file).sequence!=expected:expected!=0)throw failure();
                var saved=copy(plan);saved.sequence=expected+1;saved.updatedAt=Instant.now().toString();
                byte[] bytes=JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(saved);
                if(bytes.length>524288)throw failure();
                temp=Files.createTempFile(directory,".plan-",".tmp");
                try(var out=FileChannel.open(temp,StandardOpenOption.WRITE)){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())out.write(buffer);out.force(true);}
                Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);temp=null;return saved;
            }
        }catch(Exception e){throw failure();}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    private ExecutionPlan read(Path file)throws Exception {
        check(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>524288)throw failure();
        byte[] bytes;try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(524289);}
        if(bytes.length>524288)throw failure();var plan=JSON.readValue(bytes,ExecutionPlan.class);plan.validate();verifyOwner(plan);
        if(!file.getFileName().toString().equals(plan.id+".json"))throw failure();return plan;
    }
    private void verifyOwner(ExecutionPlan plan){if(!sessionId.equals(plan.sessionId)||!workspace.equals(plan.workspace))throw failure();}
    private Path file(String id){if(!io.github.jackfeicoder.codey.session.SessionSnapshot.validId(id))throw failure();return directory.resolve(id+".json");}
    private static void check(Path path)throws Exception {
        for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw failure();
        if(Files.exists(path)&&!path.toRealPath().equals(path.toAbsolutePath().normalize()))throw failure();
    }
    static ExecutionPlan copy(ExecutionPlan plan){try{return JSON.readValue(JSON.writeValueAsBytes(plan),ExecutionPlan.class);}catch(Exception e){throw failure();}}
    private static IllegalArgumentException failure(){return new IllegalArgumentException("计划未保存或读取失败：请检查会话归属、文件格式、并发修改、大小及目录权限。");}
}
