package io.github.jackfeicoder.codey.plan;

import io.github.jackfeicoder.codey.session.Message;
import java.util.*;
import java.util.function.Supplier;

/** 单次AgentLoop的计划能力；版本/任务/步骤由宿主固定，模型无法更换会话或批准计划。 */
public final class PlanBinding {
    public final PlanService service;
    public final String taskId,stepId;
    public final int version;
    public final boolean planning;
    private final Supplier<List<Message>> messages;
    private boolean terminal;
    public PlanBinding(PlanService service,String taskId,String stepId,boolean planning,Supplier<List<Message>> messages) {
        this.service=service;this.taskId=taskId;this.stepId=stepId;this.planning=planning;this.messages=messages;this.version=service.current().version;
    }
    public void submit(String title,List<PlanStep> steps){if(!planning||terminal)throw new IllegalArgumentException("当前调用不接受计划提交。");service.submit(title,steps,taskId,version);terminal=true;}
    public void update(PlanStep.Status status,String note,List<String> evidence){if(planning||terminal)throw new IllegalArgumentException("当前调用不接受步骤更新。");service.propose(stepId,taskId,version,status,note,evidence,messages.get());terminal=status==PlanStep.Status.COMPLETED||status==PlanStep.Status.BLOCKED;}
    public boolean terminal(){return terminal;}
    public void beforeRequest(){if(!planning)service.reserveRequest(stepId,taskId,version);}
    public String finalText(){return planning?service.render()+"\n计划已保存，等待用户审阅。":service.render()+"\n步骤结果已记录；最终完成状态需等待本任务历史提交。";}
}
