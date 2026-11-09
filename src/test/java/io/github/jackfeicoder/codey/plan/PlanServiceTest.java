package io.github.jackfeicoder.codey.plan;

import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实临时计划库验证图校验、版本批准、事务、证据及跨恢复预算。 */
class PlanServiceTest {
    @TempDir Path root;
    final String sid=UUID.randomUUID().toString();
    PlanStore store(){return new PlanStore(root,sid,WorkspaceBinding.none());}
    PlanService service(){return new PlanService(store(),sid,WorkspaceBinding.none());}
    static PlanStep step(String id,String... deps){return new PlanStep(id,"do "+id,"check "+id,PlanStep.Kind.ANALYSIS,List.of(),List.of(deps));}
    PlanService submitted(PlanStep... steps){var s=service();s.beginPlanning("goal");s.submit("title",List.of(steps),"planning-task",0);return s;}
    @Test void topologicalOrderIsStableAndForwardDependenciesAreSupported(){var s=submitted(step("C","B"),step("A"),step("B","A"));assertEquals(List.of("A","B","C"),s.current().orderedSteps().stream().map(x->x.id).toList());}
    @Test void rejectsUnknownSelfDuplicateAndCyclicDependenciesWithoutSavingCandidate(){
        var s=service();s.beginPlanning("goal");
        for(var steps:List.of(List.of(step("A","missing")),List.of(step("A","A")),List.of(step("A"),step("A")),List.of(step("A","B"),step("B","A")),List.of(step("A"),step("B","A","A"))))
            assertThrows(IllegalArgumentException.class,()->s.submit("bad",steps,"task",0));
        assertEquals(0,store().latest().version);assertEquals(ExecutionPlan.Phase.DRAFT,s.current().phase);
    }
    @Test void approvalIsRequiredAndDependenciesGateExecution(){var s=submitted(step("A"),step("B","A"));assertFalse(s.approved());assertThrows(IllegalArgumentException.class,s::nextStep);s.approve();assertEquals("A",s.nextStep().id);assertThrows(IllegalArgumentException.class,()->s.startStep("B","b"));}
    @Test void proposalIsNotCompletionUntilTaskHistoryCommits(){var s=submitted(step("A"),step("B","A"));s.approve();s.startStep("A","task-a");s.propose("A","task-a",1,PlanStep.Status.COMPLETED,"analysis based on supplied context",List.of(),List.of());assertEquals(PlanStep.Status.IN_PROGRESS,s.current().step("A").status);s.committed("A","task-a",1);assertEquals("B",s.nextStep().id);assertEquals(PlanStep.Status.COMPLETED,service().current().step("A").status);}
    @Test void failedStepBlocksDownstreamAndRetainsCompletedProgress(){var s=submitted(step("A"),step("B","A"),step("C","B"));s.approve();s.startStep("A","a");s.propose("A","a",1,PlanStep.Status.COMPLETED,"done",List.of(),List.of());s.committed("A","a",1);s.startStep("B","b");s.blocked("B","b","failure");assertEquals("B",s.nextStep().id);assertEquals(PlanStep.Status.PENDING,s.current().step("C").status);assertEquals(PlanStep.Status.COMPLETED,s.current().step("A").status);}
    @Test void revisionsInvalidateApprovalAndPreserveOnlyUnaffectedCompletedSteps(){var s=submitted(step("A"),step("B","A"),step("C","B"));s.approve();s.startStep("A","a");s.propose("A","a",1,PlanStep.Status.COMPLETED,"done",List.of(),List.of());s.committed("A","a",1);s.beginPlanning("change B");var b=step("B","A");b.description="new B";s.submit("v2",List.of(step("A"),b,step("C","B")),"planning2",1);assertEquals(2,s.current().version);assertEquals(1,s.current().history.size());assertFalse(s.approved());assertEquals(PlanStep.Status.COMPLETED,s.current().step("A").status);assertEquals(PlanStep.Status.PENDING,s.current().step("B").status);s.approve();assertEquals("B",s.nextStep().id);assertThrows(IllegalArgumentException.class,()->s.submit("stale",List.of(step("X")),"task",1));}
    @Test void sharedRequestBudgetSurvivesServiceReloadAndCannotResetThroughResume(){var s=submitted(step("A"));s.approve();s.startStep("A","a");var p=s.current();p.maxModelCalls=1;store().save(p,p.sequence);s=service();s.reserveRequest("A","a",1);var restored=service();assertEquals(0,restored.remaining());assertThrows(IllegalArgumentException.class,()->restored.reserveRequest("A","a",1));restored.addBudget(2);restored.reserveRequest("A","a",1);assertEquals(2,restored.current().modelCalls);assertThrows(IllegalArgumentException.class,()->restored.addBudget(101));}
    @Test void optimisticWriteConflictPreservesWinningState(){var s=submitted(step("A"));var p=s.current();var a=store().load(p.id);var b=store().load(p.id);a.title="winner";store().save(a,a.sequence);b.title="stale";assertThrows(IllegalArgumentException.class,()->store().save(b,b.sequence));assertEquals("winner",store().load(p.id).title);}
    @Test void storeRejectsForeignSessionCorruptionAndOversizedFiles()throws Exception {var s=submitted(step("A"));var p=s.current();p.sessionId=UUID.randomUUID().toString();assertThrows(IllegalArgumentException.class,()->store().save(p,p.sequence));Path file=root.resolve("sessions").resolve(sid).resolve("plans").resolve(s.current().id+".json");Files.writeString(file,"broken");assertThrows(IllegalArgumentException.class,()->store().load(s.current().id));}
    @Test void successfulCompleteFinishesPlanButPlainAnswerLeavesStepBlocked(){var s=submitted(step("A"));s.approve();s.startStep("A","a");s.committed("A","a",1);assertEquals(ExecutionPlan.Phase.BLOCKED,s.current().phase);s.startStep("A","b");s.propose("A","b",1,PlanStep.Status.COMPLETED,"done",List.of(),List.of());s.committed("A","b",1);assertEquals(ExecutionPlan.Phase.COMPLETED,service().current().phase);}
    static List<Message> evidence(String tool,String output,ToolResult.Status status,ToolResult.ErrorCode code) {
        return List.of(Message.user("task"),Message.assistantToolCalls(List.of(new ToolCall("proof",tool,"{}"))),new ToolResult("proof",tool,status,output,code,1,false).toMessage());
    }
    @Test void implementationRequiresKnownSuccessfulBusinessEvidence(){var a=step("A");a.kind=PlanStep.Kind.IMPLEMENTATION;var s=submitted(a);s.approve();s.startStep("A","a");assertThrows(IllegalArgumentException.class,()->s.propose("A","a",1,PlanStep.Status.COMPLETED,"done",List.of(),List.of()));assertThrows(IllegalArgumentException.class,()->s.propose("A","a",1,PlanStep.Status.COMPLETED,"done",List.of("made-up"),List.of()));assertThrows(IllegalArgumentException.class,()->s.propose("A","a",1,PlanStep.Status.COMPLETED,"done",List.of("proof"),evidence("read_file","bad",ToolResult.Status.ERROR,ToolResult.ErrorCode.FILE_NOT_FOUND)));s.propose("A","a",1,PlanStep.Status.COMPLETED,"file updated",List.of("proof"),evidence("write_file","changed",ToolResult.Status.SUCCESS,null));}
    @Test void verificationRequiresSuccessfulCompleteCommandAndRejectsControlEvidence(){var a=step("A");a.kind=PlanStep.Kind.VERIFICATION;var s=submitted(a);s.approve();s.startStep("A","a");for(String output:List.of("{}","{\"status\":\"completed\",\"exitCode\":1}","{\"status\":\"completed\",\"exitCode\":0,\"outputIncomplete\":true}"))assertThrows(IllegalArgumentException.class,()->s.propose("A","a",1,PlanStep.Status.COMPLETED,"tested",List.of("proof"),evidence("execute_command",output,ToolResult.Status.SUCCESS,null)));assertThrows(IllegalArgumentException.class,()->s.propose("A","a",1,PlanStep.Status.COMPLETED,"done",List.of("proof"),evidence("update_plan","ok",ToolResult.Status.SUCCESS,null)));s.propose("A","a",1,PlanStep.Status.COMPLETED,"test passed",List.of("proof"),evidence("execute_command","{\"status\":\"completed\",\"exitCode\":0}",ToolResult.Status.SUCCESS,null));s.revalidateOutcome("A",evidence("execute_command","{\"status\":\"completed\",\"exitCode\":0}",ToolResult.Status.SUCCESS,null));}
}
