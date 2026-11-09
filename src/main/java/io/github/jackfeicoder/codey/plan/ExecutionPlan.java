package io.github.jackfeicoder.codey.plan;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.util.*;

/** 计划持久状态：规格版本与进度分别管理，版本变更使原批准失效。 */
public final class ExecutionPlan {
    public enum Phase { DRAFT, AWAITING_REVIEW, APPROVED, EXECUTING, BLOCKED, COMPLETED }
    public String id,sessionId,goal,title,updatedAt,approvedHash,planningTaskId;
    public WorkspaceBinding workspace;
    public int version,modelCalls,maxModelCalls=60;
    public long sequence;
    public Phase phase=Phase.DRAFT;
    public List<PlanStep> steps=new ArrayList<>();
    public List<Revision> history=new ArrayList<>();
    public record Revision(int version,String title,List<PlanStep> steps) { }
    public ExecutionPlan() { }
    public PlanStep step(String id) {return steps.stream().filter(s->s.id.equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("未知计划步骤。"));}
    /** Kahn拓扑排序既做环检测，也按提交顺序稳定选择就绪步骤。 */
    public List<PlanStep> orderedSteps() {
        if(steps==null||steps.size()>20)throw new IllegalArgumentException("计划最多20步。");
        var byId=new LinkedHashMap<String,PlanStep>();
        for(var s:steps) {
            if(s==null||s.id==null||!s.id.matches("[A-Za-z][A-Za-z0-9_-]{0,47}")||byId.putIfAbsent(s.id,s)!=null)
                throw new IllegalArgumentException("步骤ID无效或重复。");
            text(s.description,1,1200);text(s.verification,1,800);
            if(s.kind==null||s.status==null||s.dependsOn==null||s.files==null||s.dependsOn.size()>20||s.files.size()>20)
                throw new IllegalArgumentException("步骤字段无效。");
            s.files.forEach(p->text(p,1,512));text(s.note,0,1200);
        }
        for(var s:steps) {
            if(new HashSet<>(s.dependsOn).size()!=s.dependsOn.size())throw new IllegalArgumentException("依赖重复。");
            for(var dep:s.dependsOn)if(s.id.equals(dep)||!byId.containsKey(dep))throw new IllegalArgumentException("依赖指向自身或不存在的步骤。");
        }
        var result=new ArrayList<PlanStep>();var done=new HashSet<String>();
        while(result.size()<steps.size()) {
            var next=steps.stream().filter(s->!done.contains(s.id)&&done.containsAll(s.dependsOn)).findFirst();
            if(next.isEmpty())throw new IllegalArgumentException("计划存在循环依赖。");
            result.add(next.get());done.add(next.get().id);
        }
        return List.copyOf(result);
    }
    public void validate() {
        if(!io.github.jackfeicoder.codey.session.SessionSnapshot.validId(id)||!io.github.jackfeicoder.codey.session.SessionSnapshot.validId(sessionId)
                ||workspace==null||phase==null||version<0||version>20||modelCalls<0||maxModelCalls<1||maxModelCalls>1000||modelCalls>maxModelCalls
                ||history==null||history.size()>20)throw new IllegalArgumentException("计划状态无效。");
        text(goal,1,4000);text(title,1,200);orderedSteps();
        if(steps.isEmpty()&&phase!=Phase.DRAFT)throw new IllegalArgumentException("待审计划至少包含一步。");
    }
    static void text(String value,int min,int max) {
        if(value==null||value.length()<min||value.length()>max||min>0&&value.isBlank()||value.indexOf('\0')>=0)
            throw new IllegalArgumentException("计划文本为空、过长或包含无效字符。");
    }
}
