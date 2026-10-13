package io.github.jackfeicoder.codey.agent;

import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolExecutor;
import io.github.jackfeicoder.codey.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 单任务的 ReAct 风格循环：请求模型 → 顺序执行工具 → 回填结果 → 再请求，直到回答或预算耗尽。 */
public final class AgentLoop {
    /** 包含最终回答请求在内，默认最多调用模型 8 次。 */
    public static final int DEFAULT_MAX_STEPS = 8;
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

    /** 使用默认预算装配循环，注册工具由宿主负责。 */
    public AgentLoop(LlmClient client, ToolRegistry registry) { this(client, registry, DEFAULT_MAX_STEPS); }

    /** 显式预算便于运行策略和测试，不在此读取配置或创建厂商客户端。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maxSteps < 1 || maxSteps > 100) throw new IllegalArgumentException("Agent steps must be within 1..100.");
        this.maxSteps = maxSteps;
        this.executor = new ToolExecutor(registry);
    }

    /** 在独立消息副本上执行，不修改 Session；调用方只提交正常完成结果。 */
    public AgentResult run(Session session, String input, ToolContext context) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");
        int historySize = session.messages().size(); // 返回结果只截取新增的一轮，旧历史不重复保存。
        var messages = new ArrayList<>(session.messagesFor(input));
        Set<String> callIds = new HashSet<>(); // 同一任务内跨步骤检测重复调用 ID。
        int executions = 0;
        for (int step = 1; step <= maxSteps; step++) {
            checkInterrupted();
            LlmResponse response = client.generate(new LlmRequest(messages, registry.definitions(context)));
            checkInterrupted();
            if (response == null) throw invalid("Model returned no response.");
            if (response.toolCalls().isEmpty()) {
                if (!"stop".equals(response.finishReason()) && !response.isTruncated()) {
                    throw invalid("Model did not return a supported final response.");
                }
                messages.add(response.message());
                return new AgentResult(AgentResult.Status.COMPLETED,
                        messages.subList(historySize, messages.size()), response, step, executions);
            }
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
                var result = executor.execute(call, context);
                executions++;
                checkInterrupted(); // 中断不进入下一工具或模型请求。
                messages.add(result.toMessage()); // 错误也回填，让模型下一轮修正参数。
            }
        }
        return new AgentResult(AgentResult.Status.BUDGET_EXHAUSTED,
                messages.subList(historySize, messages.size()), null, maxSteps, executions);
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
