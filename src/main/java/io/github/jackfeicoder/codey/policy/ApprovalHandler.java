package io.github.jackfeicoder.codey.policy;

/** 终端以外的宿主可注入自己的审批界面；没有审批来源时默认不执行。 */
@FunctionalInterface
public interface ApprovalHandler {
    enum Answer { APPROVE, DENY, UNAVAILABLE }
    Answer approve(ApprovalRequest request) throws InterruptedException;
    static ApprovalHandler unavailable() { return request -> Answer.UNAVAILABLE; }
}
