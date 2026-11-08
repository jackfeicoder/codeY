package io.github.jackfeicoder.codey.policy;

/** 独立审查扩展点；规则给出可解释结论，不把主模型的自我声明当成批准。 */
@FunctionalInterface
public interface ApprovalReviewer {
    enum Verdict { ALLOW, HUMAN }
    Verdict review(ApprovalRequest request);
    static ApprovalReviewer rules() {
        return request -> request.impact().kind() != OperationImpact.Kind.UNKNOWN && !request.sensitive() && !request.destructive()
                ? Verdict.ALLOW : Verdict.HUMAN;
    }
}
