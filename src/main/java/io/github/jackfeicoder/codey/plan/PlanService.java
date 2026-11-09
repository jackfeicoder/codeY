package io.github.jackfeicoder.codey.plan;

import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.util.*;
import java.util.function.Consumer;

/** 主线程计划状态机；批准来自CLI，模型只能提交规格或提出步骤结果。 */
public final class PlanService {
    private final PlanStore store;
    private final String sessionId;
    private final WorkspaceBinding workspace;
    private ExecutionPlan current;
    public PlanService(PlanStore store,String sessionId,WorkspaceBinding workspace){this.store=store;this.sessionId=sessionId;this.workspace=workspace;current=store.latest();}
    public ExecutionPlan current(){return current==null?null:PlanStore.copy(current);}
    private void change(Consumer<ExecutionPlan> edit){var next=PlanStore.copy(current);edit.accept(next);current=store.save(next,current.sequence);}
    public void beginPlanning(String goal) {
        if(current==null||current.phase==ExecutionPlan.Phase.COMPLETED) {
            var p=new ExecutionPlan();p.id=UUID.randomUUID().toString();p.sessionId=sessionId;p.workspace=workspace;
            p.goal=goal;p.title="待提交计划";current=store.save(p,0);
        }else change(p->{p.phase=ExecutionPlan.Phase.DRAFT;p.approvedHash=null;});
    }
    public void submit(String title,List<PlanStep> steps,String planningTaskId,int baseVersion) {
        if(current==null||current.version!=baseVersion||current.phase!=ExecutionPlan.Phase.DRAFT)throw new IllegalArgumentException("规划版本已变化，请重新查看当前计划。");
        if(current.version>=20)throw new IllegalArgumentException("计划修订已达20版，请另开计划。");
        var candidate=PlanStore.copy(current);candidate.title=title;candidate.steps=new ArrayList<>(steps);
        if(steps.isEmpty())throw new IllegalArgumentException("计划至少包含一步。");candidate.orderedSteps();
        var old=current;
        if(old.version>0)candidate.history.add(new ExecutionPlan.Revision(old.version,old.title,PlanStore.copy(old).steps));
        // 只有规格及上游均未改变的已完成步骤可继承；其余步骤需重新核实既有副作用。
        for(var s:candidate.orderedSteps()) {
            var previous=old.steps.stream().filter(p->p.id.equals(s.id)).findFirst().orElse(null);
            if(previous!=null&&previous.status==PlanStep.Status.COMPLETED&&s.sameSpecification(previous)
                    &&s.dependsOn.stream().allMatch(id->candidate.step(id).status==PlanStep.Status.COMPLETED)) {
                s.status=PlanStep.Status.COMPLETED;s.taskId=previous.taskId;s.note=previous.note;s.evidence=previous.evidence;
            }else if(previous!=null)s.note="步骤或上游已修订；实施前核实既有文件/外部状态，不盲目重放。";
        }
        candidate.version++;candidate.phase=ExecutionPlan.Phase.AWAITING_REVIEW;candidate.approvedHash=null;candidate.planningTaskId=planningTaskId;
        current=store.save(candidate,current.sequence);
    }
    /** 仅宿主用户交互调用；计划批准不改变文件/命令/MCP权限。 */
    public void approve() {
        if(current==null||current.version<1||current.phase==ExecutionPlan.Phase.DRAFT||current.phase==ExecutionPlan.Phase.COMPLETED)
            throw new IllegalArgumentException("请先提交可审阅计划。");
        change(p->{p.approvedHash=specificationHash(p);p.phase=p.steps.stream().allMatch(s->s.status==PlanStep.Status.COMPLETED)?ExecutionPlan.Phase.COMPLETED:ExecutionPlan.Phase.APPROVED;});
    }
    public boolean approved(){return current!=null&&Objects.equals(current.approvedHash,specificationHash(current));}
    public PlanStep nextStep() {
        if(!approved())throw new IllegalArgumentException("当前版本尚未批准。");
        return current.orderedSteps().stream().filter(s->s.status!=PlanStep.Status.COMPLETED)
                .filter(s->s.dependsOn.stream().allMatch(id->current.step(id).status==PlanStep.Status.COMPLETED)).findFirst().orElse(null);
    }
    public void startStep(String stepId,String taskId) {
        var next=nextStep();if(next==null||!next.id.equals(stepId))throw new IllegalArgumentException("步骤依赖尚未完成。");
        change(p->{var s=p.step(stepId);boolean same=Objects.equals(s.taskId,taskId);s.status=PlanStep.Status.IN_PROGRESS;s.taskId=taskId;
            if(!same){s.proposed=null;s.evidence=List.of();}p.phase=ExecutionPlan.Phase.EXECUTING;});
    }
    /** 每次主模型请求前持久扣预算；跨步骤、失败和恢复不重置。 */
    public void reserveRequest(String stepId,String taskId,int version) {
        verifyExecution(stepId,taskId,version);
        if(current.modelCalls>=current.maxModelCalls)throw new IllegalArgumentException("计划总模型预算已用完；/plan 查看，可显式输入 增加预算 20。");
        change(p->p.modelCalls++);
    }
    public int remaining(){return current==null?0:current.maxModelCalls-current.modelCalls;}
    public void addBudget(int extra) {
        if(current==null||extra<1||extra>100||current.maxModelCalls+extra>1000)throw new IllegalArgumentException("单次补充1..100次，计划总上限1000次。");
        change(p->p.maxModelCalls+=extra);
    }
    public void propose(String stepId,String taskId,int version,PlanStep.Status status,String note,List<String> evidence,List<Message> messages) {
        verifyExecution(stepId,taskId,version);ExecutionPlan.text(note,1,1200);
        if(status==PlanStep.Status.PENDING||evidence==null||evidence.size()>16||new HashSet<>(evidence).size()!=evidence.size())throw new IllegalArgumentException("步骤结果或证据无效。");
        if(status==PlanStep.Status.COMPLETED)validateEvidence(current.step(stepId),evidence,messages);
        change(p->{var s=p.step(stepId);s.note=note;s.evidence=List.copyOf(evidence);s.proposed=status;});
    }
    private void verifyExecution(String stepId,String taskId,int version) {
        if(current==null||current.version!=version||!approved()||current.phase!=ExecutionPlan.Phase.EXECUTING
                ||!Objects.equals(current.step(stepId).taskId,taskId)||current.step(stepId).status!=PlanStep.Status.IN_PROGRESS)
            throw new IllegalArgumentException("执行步骤、任务或批准版本已变化。");
    }
    /** 状态只能在任务历史成功提交后落实；普通文本回答不自动当成步骤完成。 */
    public void committed(String stepId,String taskId,int version) {
        if(current.version!=version||!Objects.equals(current.step(stepId).taskId,taskId))throw new IllegalArgumentException("提交关联不匹配。");
        if(current.step(stepId).status==PlanStep.Status.COMPLETED)return;
        change(p->{var s=p.step(stepId);
            s.status=s.proposed==PlanStep.Status.COMPLETED?PlanStep.Status.COMPLETED:PlanStep.Status.BLOCKED;
            if(s.proposed==null)s.note="本任务已返回回答，但未提交带证据的步骤结果，请核实后继续。";
            p.phase=p.steps.stream().allMatch(x->x.status==PlanStep.Status.COMPLETED)?ExecutionPlan.Phase.COMPLETED
                    :s.status==PlanStep.Status.BLOCKED?ExecutionPlan.Phase.BLOCKED:ExecutionPlan.Phase.APPROVED;
        });
    }
    public void blocked(String stepId,String taskId,String reason) {
        if(current==null||current.step(stepId).status==PlanStep.Status.COMPLETED||!Objects.equals(current.step(stepId).taskId,taskId))return;
        change(p->{p.step(stepId).status=PlanStep.Status.BLOCKED;p.step(stepId).note=reason;p.phase=ExecutionPlan.Phase.BLOCKED;});
    }
    public void revalidateOutcome(String stepId,List<Message> messages) {
        var step=current.step(stepId);if(step.proposed==PlanStep.Status.COMPLETED)validateEvidence(step,step.evidence,messages);
    }
    /** 只接受本执行任务已返回的成功工具结果，排除计划控制工具；验证步骤还需成功且输出完整的命令证据。 */
    private static void validateEvidence(PlanStep step,List<String> evidence,List<Message> messages) {
        if(evidence.isEmpty()&&step.kind!=PlanStep.Kind.ANALYSIS)throw new IllegalArgumentException("实施/验证步骤需要真实工具证据。");
        var calls=new HashMap<String,String>();var results=new HashMap<String,Message>();
        for(var m:messages){m.toolCalls().forEach(c->calls.put(c.id(),c.name()));if(m.role()==MessageRole.TOOL)results.put(m.toolCallId(),m);}
        boolean command=false;
        try {
            for(var id:evidence) {
                var name=calls.get(id);var result=results.get(id);
                if(name==null||result==null||Set.of("submit_plan","update_plan").contains(name))throw new IllegalArgumentException();
                var outer=PlanStore.JSON.readTree(result.content());
                if(!outer.path("status").asText().equals("success")||outer.path("truncated").asBoolean())throw new IllegalArgumentException();
                if(name.equals("execute_command")) {
                    var body=PlanStore.JSON.readTree(outer.path("output").asText());
                    if(!body.path("exitCode").isIntegralNumber()||body.path("exitCode").asInt()!=0||!body.path("status").asText().equals("completed")
                            ||body.path("timedOut").asBoolean()||body.path("outputIncomplete").asBoolean()||body.path("cleanupIncomplete").asBoolean()
                            ||body.path("stdoutTruncated").asBoolean()||body.path("stderrTruncated").asBoolean())throw new IllegalArgumentException();
                    command=true;
                }
            }
            if(step.kind==PlanStep.Kind.VERIFICATION&&!command)throw new IllegalArgumentException();
        }catch(Exception invalid){throw new IllegalArgumentException("证据需来自本任务已返回的成功工具；VERIFICATION需要退出码0且输出完整的命令。证据只证明操作结果，业务验收仍需核对。");}
    }
    public String context() {
        if(current==null)return "";
        return "[宿主计划状态；计划不授予工具权限]\n"+render()+"\n批准与执行由用户控制；不得自行切换模式。步骤完成必须提交update_plan并引用本任务真实工具结果；工具成功不自动证明全部业务验收。";
    }
    public String render() {
        if(current==null)return "当前会话尚无计划。";
        var out=new StringBuilder("计划 ").append(current.title).append(" | v").append(current.version).append(" | ").append(current.phase)
                .append(" | 模型预算 ").append(current.modelCalls).append('/').append(current.maxModelCalls).append("\n目标：").append(current.goal);
        for(var s:current.orderedSteps())out.append("\n").append(s.id).append(" [").append(s.status).append("] ").append(s.description)
                .append("\n  类型：").append(s.kind).append("；依赖：").append(s.dependsOn).append("；文件：").append(s.files)
                .append("\n  验收：").append(s.verification).append(s.note.isEmpty()?"":"\n  记录："+s.note);
        return out.toString();
    }
    private static String specificationHash(ExecutionPlan p) {
        return io.github.jackfeicoder.codey.policy.ApprovalScope.key(p.id,p.sessionId,p.workspace.toString(),""+p.version,p.goal,p.title,
                p.steps.stream().map(s->io.github.jackfeicoder.codey.policy.ApprovalScope.key(s.id,s.description,s.verification,s.kind.name(),s.files.toString(),s.dependsOn.toString())).toList().toString());
    }
}
