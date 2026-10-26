package io.github.jackfeicoder.codey.context;

/** 容量拦截属于本地上下文错误，不伪装成模型 HTTP 错误；保留实际执行计数供 CLI 展示。 */
public final class ContextLimitException extends RuntimeException {
    private final ContextBudgetPolicy.Assessment assessment;
    private final int modelCalls;
    private final int toolCalls;

    public ContextLimitException(ContextBudgetPolicy.Assessment assessment, int modelCalls, int toolCalls) {
        super("Estimated input " + assessment.estimatedInput() + " exceeds input budget " + assessment.inputBudget()
                + " tokens. Blocked before request " + (modelCalls + 1) + "; completed " + modelCalls
                + " model requests and " + toolCalls + " tool calls. This turn was not saved.");
        this.assessment = assessment; this.modelCalls = modelCalls; this.toolCalls = toolCalls;
    }

    public ContextBudgetPolicy.Assessment assessment() { return assessment; }
    public int modelCalls() { return modelCalls; }
    public int toolCalls() { return toolCalls; }
}
