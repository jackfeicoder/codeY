package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** 真正的/memory分发、帮助、范围、自动水位和归档/版本恢复，不请求真实厂商。 */
class MemoryMaintenanceCliTest {
    @TempDir Path root;
    private final List<LlmRequest> requests=new CopyOnWriteArrayList<>();
    private final StringWriter output=new StringWriter(),errors=new StringWriter();
    private JsonMemoryStore store(){return new JsonMemoryStore(root.resolve("data"));}
    private MemoryService service(){return new MemoryService(store(),new MemorySettings());}
    private boolean noChange;
    private void seed(){
        service().remember(WorkspaceBinding.none(),MemoryScope.USER,MemoryLoading.ON_DEMAND,null,"Java解释时附带面试回答和示例",List.of("Java"),null,null);
        service().remember(WorkspaceBinding.none(),MemoryScope.USER,MemoryLoading.ON_DEMAND,null,"解释Java时附带面试回答示例",List.of("Java"),null,null);
    }
    private void run(String commands,WorkspaceBinding workspace,MemoryConsolidationSettings settings) throws Exception {
        LlmClient client=request->{requests.add(request);
            if(request.messages().get(0).content().contains("# 长期记忆整理")){
                String text="{\"operations\":[]}";
                if(!noChange)try{
                    var entries=new ObjectMapper().readTree(request.messages().get(1).content()).path("entries");
                    var op=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.MERGE,List.of(entries.get(0).path("id").asText(),entries.get(1).path("id").asText()),
                            "Java面试","Java解释附面试示例",List.of("Java"),"合并重复偏好",null,null);
                    text=new ObjectMapper().writeValueAsString(new MemoryTidyPlan(List.of(op)));
                }catch(Exception invalid){throw new AssertionError(invalid);}
                return new LlmResponse(Message.assistant(text),"stop",null);
            }
            return new LlmResponse(Message.assistant("done"),"stop",null);
        };
        var writer=new PrintWriter(output);
        int status=new SessionLauncher(root,new SessionSettings(root.resolve("data"),null,false),new JsonSessionStore(root.resolve("data")),
                selected->new SessionLauncher.ModelChoice("fixture demo",client,client),()->"base",
                new ConsoleInput(new BufferedReader(new StringReader(commands)),writer),writer,new PrintWriter(errors),
                new SessionLauncher.Options(workspace.path(),workspace.mode()==WorkspaceBinding.Mode.NONE,"fixture",null,null),new ToolRegistry(),DebugPrinter.NONE,List::of,
                20,new PermissionSettings(),new ContextBudgetSettings(),new CompactionSettings(),new MemorySettings(),
                new MemoryExtractionSettings(false,10,4096,1024),settings).run();
        assertEquals(0,status);
    }
    @Test void manualTidyArchivesVersionsRestoreAndRollbackAreRealCommands() throws Exception {
        seed(); var original=service().list(WorkspaceBinding.none());
        run("/help\n/memory tidy --user\n/memory archives --user\n/memory versions --user\n/exit\n",WorkspaceBinding.none(),new MemoryConsolidationSettings());
        assertEquals(1,requests.size());assertEquals(1,service().list(WorkspaceBinding.none()).size());
        var document=store().readDocument(MemoryScope.USER,null);
        String archive=document.archived().get(0).id(),version=document.versions().get(0).id();
        assertTrue(output.toString().contains(archive));assertTrue(output.toString().contains(version));
        assertTrue(output.toString().contains("/memory [关键词|tidy"));
        run("/memory restore --user "+archive+"\n/exit\n",WorkspaceBinding.none(),new MemoryConsolidationSettings());
        assertEquals(new HashSet<>(original),new HashSet<>(service().list(WorkspaceBinding.none())));
        run("/memory rollback --user "+version+"\n/exit\n",WorkspaceBinding.none(),new MemoryConsolidationSettings());
        assertEquals(1,requests.size());assertEquals(original,service().list(WorkspaceBinding.none()));assertEquals("",errors.toString());
    }
    @Test void successfulTaskTriggersOnceAndMemoryRequestIsNotInChatHistory() throws Exception {
        seed();noChange=true;
        run("hello\nagain\n/exit\n",WorkspaceBinding.none(),new MemoryConsolidationSettings(true,2,8000,3600,10,5,16000,4096));
        assertEquals(3,requests.size());assertTrue(requests.get(1).messages().get(0).content().contains("# 长期记忆整理"));
        var saved=new JsonSessionStore(root.resolve("data")).list().sessions().get(0);
        assertEquals(4,saved.messages().size());assertTrue(saved.messages().stream().noneMatch(m->m.content()!=null&&m.content().contains("# 长期记忆整理")));
        assertEquals("",errors.toString());
    }
    @Test void explicitRememberCanCrossWaterlineWithoutStartingExtraction() throws Exception {
        run("/remember --user Java解释附带面试回答和示例\n/remember --user 解释Java时附面试回答示例\n/exit\n",WorkspaceBinding.none(),
                new MemoryConsolidationSettings(true,2,8000,0,1,5,16000,4096));
        assertEquals(1,requests.size());assertTrue(requests.get(0).messages().get(0).content().contains("# 长期记忆整理"));
        assertEquals(1,service().list(WorkspaceBinding.none()).size());
    }
    @Test void projectCommandsCannotRestoreUserArchivesAndDataIsIsolated() throws Exception {
        seed();var dir=Files.createDirectory(root.resolve("project"));var workspace=new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,dir.toString());
        run("/memory tidy --user\n/exit\n",workspace,new MemoryConsolidationSettings());
        var archive=store().readDocument(MemoryScope.USER,null).archived().get(0);
        run("/memory restore --project "+archive.id()+"\n/exit\n",workspace,new MemoryConsolidationSettings());
        assertTrue(errors.toString().contains("当前范围没有此归档ID"));assertEquals(1,store().load(MemoryScope.USER,null).size());
        assertTrue(store().load(MemoryScope.PROJECT,MemoryService.projectId(workspace)).isEmpty());
    }
    @Test void disabledAndBadSyntaxDoNotCallModel() throws Exception {
        seed();run("/memory tidy --user\n/memory restore --user nope\n/memory versions --unknown\n/exit\n",WorkspaceBinding.none(),
                new MemoryConsolidationSettings(false,2,8000,0,1,5,16000,4096));
        assertTrue(requests.isEmpty());assertEquals(2,store().load(MemoryScope.USER,null).size());
        assertTrue(output.toString().contains("已关闭"));assertTrue(errors.toString().contains("恢复需要完整归档ID"));
    }
}
