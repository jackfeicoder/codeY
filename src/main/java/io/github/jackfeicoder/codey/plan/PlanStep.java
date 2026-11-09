package io.github.jackfeicoder.codey.plan;

import java.util.List;

/** 一步的规格与执行投影；原始模型/工具消息仍由 TaskStore 保存。 */
public final class PlanStep {
    public enum Kind { ANALYSIS, IMPLEMENTATION, VERIFICATION }
    public enum Status { PENDING, IN_PROGRESS, COMPLETED, BLOCKED }
    public String id, description, verification;
    public Kind kind;
    public List<String> files=List.of(), dependsOn=List.of();
    public Status status=Status.PENDING;
    public String taskId, note="";
    /** 模型提出的结果先保存，只有对应任务成功提交后才落实为步骤完成。 */
    public Status proposed;
    public List<String> evidence=List.of();
    public PlanStep() { }
    public PlanStep(String id,String description,String verification,Kind kind,List<String> files,List<String> dependencies) {
        this.id=id;this.description=description;this.verification=verification;this.kind=kind;
        this.files=List.copyOf(files);this.dependsOn=List.copyOf(dependencies);
    }
    public boolean sameSpecification(PlanStep other) {
        return id.equals(other.id)&&description.equals(other.description)&&verification.equals(other.verification)
                &&kind==other.kind&&files.equals(other.files)&&dependsOn.equals(other.dependsOn);
    }
}
