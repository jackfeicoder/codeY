package io.github.jackfeicoder.codey.policy;

/** 独立自动审查扩展点；首版规则只能确认有限普通文件操作，其他情况转人工。 */
@FunctionalInterface
public interface ApprovalReviewer {
    enum Verdict { ALLOW, HUMAN }
    Verdict review(ApprovalRequest request);
    static ApprovalReviewer rules() {
        return request -> request.target() != null && !request.sensitive() && !request.destructive()
                ? Verdict.ALLOW : Verdict.HUMAN;
    }
}
