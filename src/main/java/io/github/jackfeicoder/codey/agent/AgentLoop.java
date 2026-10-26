package io.github.jackfeicoder.codey.agent;

import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.context.ContextBuilder;
import io.github.jackfeicoder.codey.context.ContextSnapshot;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.context.ContextLimitException;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolExecutor;
import io.github.jackfeicoder.codey.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 单任务的 ReAct 风格循环：请求模型 → 顺序执行工具 → 回填结果 → 再请求，直到回答或预算耗尽。 */
public final class AgentLoop {
    /** 包含最终回答请求在内，默认最多调用模型 20 次；最后一次只用于回答。 */
    public static final int DEFAULT_MAX_STEPS = 20;
    /** 配置和构造入口共用硬上限，避免用户参数绕过预算范围。 */
    public static final int MAX_STEPS = 100;
    /** 单个模型响应最多调度 16 个工具，避免响应一次要求无界执行。 */
    public static final int MAX_TOOL_CALLS_PER_STEP = 16;
    /** 一次 generate 对应一次模型请求，HTTP 逻辑仍在 LLM 层。 */
    private final LlmClient client;
    /** 汇总当前可用定义，不将说明拼入提示词。 */
    private final ToolRegistry registry;
    /** 校验参数与执行工具，工具失败转换为关联结果。 */
    private final ToolExecutor executor;
    /** 单任务的模型请求预算，构造后固定。 */
    private final int maxSteps;
    /** 在执行位置打印简洁进度，由 Main 提供输出；默认静默便于复用。 */
    private final DebugPrinter debug;
    /** 集中组装请求；监听器只接收估算快照，业务循环不依赖 CLI。 */
    private final ContextBuilder contexts;
    private final Consumer<ContextSnapshot> onContext;
    /** 当前模型的输入预算，独立于 maxSteps 模型请求次数预算。 */
    private final ContextBudgetPolicy budget;

    /** 使用默认预算装配循环，注册工具由宿主负责。 */
    public AgentLoop(LlmClient client, ToolRegistry registry) { this(client, registry, DEFAULT_MAX_STEPS); }

    /** 显式预算便于运行策略和测试，不在此读取配置或创建厂商客户端。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps) {
        this(client, registry, maxSteps, DebugPrinter.NONE);
    }

    /** 指定调试输出，旧构造方式保持静默，不增加事件调度接口。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps, DebugPrinter debug) {
        this(client, registry, maxSteps, debug, new ContextBuilder(), snapshot -> { });
    }

    /** 宿主可观察每次请求的上下文，包括工具回填后的请求；不改变会话提交规则。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps, DebugPrinter debug,
                     ContextBuilder contexts, Consumer<ContextSnapshot> onContext) {
        this(client, registry, maxSteps, debug, contexts, onContext, ContextBudgetPolicy.unknown());
    }

    /** 生产入口显式注入选中模型的预算策略；旧库调用没有模型资料时保持 UNKNOWN。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps, DebugPrinter debug,
                     ContextBuilder contexts, Consumer<ContextSnapshot> onContext, ContextBudgetPolicy budget) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maxSteps < 1 || maxSteps > MAX_STEPS) throw new IllegalArgumentException("Agent steps must be within 1..100.");
        this.maxSteps = maxSteps;
        this.executor = new ToolExecutor(registry);
        this.debug = Objects.requireNonNull(debug, "debug");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.onContext = Objects.requireNonNull(onContext, "onContext");
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    /** 在独立消息副本上执行，不修改 Session；调用方只提交正常完成结果。 */
    public AgentResult run(Session session, String input, ToolContext context) {
        return run(session, input, context, null);
    }

    /** 可选增量展示入口；原入口继续走非流式，便于库调用与已有测试复用。 */
    public AgentResult run(Session session, String input, ToolContext context, AgentStreamListener stream) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");
        return execute(session, input, context, stream); // 最终回答、错误和预算提示继续由 CLI 展示。
    }

    /** 实际循环使用请求副本，打印的数据不加入消息历史。 */
    private AgentResult execute(Session session, String input, ToolContext context, AgentStreamListener stream) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Session requires a nonblank user input.");
        var history = session.messages(); // 原历史与本次任务分开，返回结果不依赖请求视图的下标。
        var messages = new ArrayList<Message>();
        messages.add(Message.user(input));
        Set<String> callIds = new HashSet<>(); // 同一任务内跨步骤检测重复调用 ID。
        int executions = 0;
        boolean warned = false; // 一个用户任务最多输出一次预警，不把每轮都刷成同一提醒。
        for (int step = 1; step <= maxSteps; step++) {
            checkInterrupted();
            boolean finalRound = step == maxSteps;
            var snapshot = contexts.build(history, messages, registry.definitions(context), context.workMode(), step, maxSteps);
            onContext.accept(snapshot); // 每次网络请求前更新估算，不向 HTTP 增加统计字段。
            var assessment = budget.evaluate(snapshot);
            if (!assessment.canSend()) throw new ContextLimitException(assessment, step - 1, executions);
            if (assessment.state() == ContextBudgetPolicy.State.WARNING && !warned) {
                debug.contextWarning(assessment.estimatedInput(), assessment.inputBudget(), assessment.remainingTokens());
                warned = true;
            }
            LlmRequest request = snapshot.request();
            debug.round(step);
            LlmResponse response;
            if (stream == null) response = client.generate(request);
            else {
                stream.onResponseStart(step);
                try { response = client.generateStream(request, stream::onText); }
                finally { stream.onResponseEnd(step); }
            }
            checkInterrupted();
            if (response == null) throw invalid("Model returned no response.");
            if (response.toolCalls().isEmpty()) {
                if (!"stop".equals(response.finishReason()) && !response.isTruncated()) {
                    throw invalid("Model did not return a supported final response.");
                }
                // stop/length 只说明服务端结束生成；没有正文也没有工具时，不能当作任务完成。
                if (response.text().isBlank()) {
                    throw invalid("Model returned an empty final answer (finish_reason=" + response.finishReason() + ").");
                }
                messages.add(response.message());
                return new AgentResult(AgentResult.Status.COMPLETED,
                        messages, response, step, executions, finalRound);
            }
            // 即使服务商忽略 tool_choice=none，也不执行最后一轮提出的工具，更不提交残缺回答。
            if (finalRound) return new AgentResult(AgentResult.Status.BUDGET_EXHAUSTED,
                    messages, null, step, executions, true);
            // 在执行任何工具前检查整组：截断或异常结束原因不触发工具副作用。
            if (!"tool_calls".equals(response.finishReason())
                    || response.toolCalls().size() > MAX_TOOL_CALLS_PER_STEP) {
                throw invalid("Model returned an incomplete or oversized tool-call group.");
            }
            for (var call : response.toolCalls()) {
                if (!callIds.add(call.id())) throw invalid("Model reused a tool-call ID within this task.");
            }
            messages.add(response.message());
            for (var call : response.toolCalls()) {
                checkInterrupted();
                debug.tool(step, call);
                var result = executor.execute(call, context);
                executions++;
                debug.toolFailed(step, result);
                checkInterrupted(); // 中断不进入下一工具或模型请求。
                messages.add(result.toMessage()); // 错误也回填，让模型下一轮修正参数。
            }
        }
        return new AgentResult(AgentResult.Status.BUDGET_EXHAUSTED,
                messages, null, maxSteps, executions);
    }

    /** 保留中断标志，统一沿现有 LLM 错误通道退出。 */
    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new LlmException(LlmException.Kind.INTERRUPTED, "Agent task was interrupted.");
        }
    }

    /** 程序控制的协议错误，不回显工具参数或模型原文。 */
    private static LlmException invalid(String message) {
        return new LlmException(LlmException.Kind.INVALID_RESPONSE, message);
    }
}
