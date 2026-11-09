package io.github.jackfeicoder.codey.plan;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 模型可见入口的作用域、参数和终态屏障，使用真实执行器而非直接修改状态。 */
class PlanControlToolTest {
    @TempDir Path data;
    final String sid=UUID.randomUUID().toString();
    PlanService service(){return new PlanService(new PlanStore(data,sid,WorkspaceBinding.none()),sid,WorkspaceBinding.none());}
    ToolContext context(PlanBinding binding,WorkMode mode){return new ToolContext(WorkspaceBinding.none(),16000,mode,new PermissionService(PermissionMode.FULL,WorkspaceBinding.none(),ApprovalHandler.unavailable()),null,binding);}
    ToolRegistry registry(){return new ToolRegistry().register(new PlanControlTool(true)).register(new PlanControlTool(false));}
    @Test void controlDefinitionsAreHiddenOutsideBoundHostScope(){
        var s=service();s.beginPlanning("goal");var b=new PlanBinding(s,"task",null,true,List::of);var r=registry();
        assertTrue(r.definitions(new ToolContext(WorkspaceBinding.none())).isEmpty());
        assertEquals(List.of("submit_plan"),r.definitions(context(b,WorkMode.PLAN)).stream().map(ToolDefinition::name).toList());
        assertTrue(r.definitions(context(b,WorkMode.REACT)).isEmpty());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,new ToolExecutor(r).execute(new ToolCall("x","update_plan","{}"),context(b,WorkMode.PLAN)).errorCode());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"title\":\"x\",\"steps\":[]}","{\"title\":\"x\",\"steps\":[],\"approved\":true}","{\"title\":2,\"steps\":[]}","{\"title\":\"x\",\"title\":\"y\",\"steps\":[]}"})
    void invalidSubmissionNeverChangesVersionOrTerminates(String args){
        var s=service();s.beginPlanning("goal");var b=new PlanBinding(s,"task",null,true,List::of);
        var result=new ToolExecutor(registry()).execute(new ToolCall("x","submit_plan",args),context(b,WorkMode.PLAN));
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,result.errorCode());assertEquals(0,s.current().version);assertFalse(b.terminal());
    }
    @Test void updateIsLimitedToBoundStepAndTerminalUpdateBlocksFollowingActions(){
        var s=service();s.beginPlanning("goal");s.submit("title",List.of(PlanServiceTest.step("A")),"planner",0);s.approve();s.startStep("A","executor");
        var b=new PlanBinding(s,"executor","A",false,List::of);var ctx=context(b,WorkMode.REACT);var ex=new ToolExecutor(registry());
        assertEquals(List.of("update_plan"),registry().definitions(ctx).stream().map(ToolDefinition::name).toList());
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,ex.execute(new ToolCall("bad","update_plan","{\"stepId\":\"B\",\"status\":\"COMPLETED\",\"note\":\"done\",\"evidence\":[]}"),ctx).errorCode());
        assertFalse(b.terminal());
        assertEquals(ToolResult.Status.SUCCESS,ex.execute(new ToolCall("done","update_plan","{\"stepId\":\"A\",\"status\":\"COMPLETED\",\"note\":\"analysis\",\"evidence\":[]}"),ctx).status());
        assertTrue(b.terminal());assertTrue(registry().definitions(ctx).isEmpty());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,ex.execute(new ToolCall("again","update_plan","{}"),ctx).errorCode());
    }
}
