package io.github.jackfeicoder.codey.agent;

import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
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

    /** 使用默认预算装配循环，注册工具由宿主负责。 */
    public AgentLoop(LlmClient client, ToolRegistry registry) { this(client, registry, DEFAULT_MAX_STEPS); }

    /** 显式预算便于运行策略和测试，不在此读取配置或创建厂商客户端。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps) {
        this(client, registry, maxSteps, DebugPrinter.NONE);
    }

    /** 指定调试输出，旧构造方式保持静默，不增加事件调度接口。 */
    public AgentLoop(LlmClient client, ToolRegistry registry, int maxSteps, DebugPrinter debug) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maxSteps < 1 || maxSteps > MAX_STEPS) throw new IllegalArgumentException("Agent steps must be within 1..100.");
        this.maxSteps = maxSteps;
        this.executor = new ToolExecutor(registry);
        this.debug = Objects.requireNonNull(debug, "debug");
    }

    /** 在独立消息副本上执行，不修改 Session；调用方只提交正常完成结果。 */
    public AgentResult run(Session session, String input, ToolContext context) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(context, "context");
        return execute(session, input, context); // 最终回答、错误和预算提示继续由 CLI 展示。
    }

    /** 实际循环使用请求副本，打印的数据不加入消息历史。 */
    private AgentResult execute(Session session, String input, ToolContext context) {
        int historySize = session.messages().size(); // 返回结果只截取新增的一轮，旧历史不重复保存。
        var messages = new ArrayList<>(session.messagesFor(input));
        Set<String> callIds = new HashSet<>(); // 同一任务内跨步骤检测重复调用 ID。
        int executions = 0;
        for (int step = 1; step <= maxSteps; step++) {
            checkInterrupted();
            boolean finalRound = step == maxSteps;
            // 预算提示只改本次请求中的 system 副本，既不伪造用户消息，也不写入会话快照。
            var requestMessages = new ArrayList<>(messages);
            int remaining = maxSteps - step + 1;
            if (remaining <= 3) {
                String instruction = finalRound
                        ? "这是当前任务最后一次模型请求，工具调用已禁用。请依据已获得的信息直接回答，"
                        + "明确尚未完成或尚未确认的部分，不要声称未执行的操作已完成，不要再请求工具。"
                        : "当前任务最多允许 " + maxSteps + " 次模型请求，包括本次还剩 " + remaining
                        + " 次。请优先使用已有证据，避免重复探索；信息足够时立即回答，最后一次请求将禁用工具并用于总结。";
                requestMessages.set(0, Message.system(messages.get(0).content() + "\n\n[运行时任务预算]\n" + instruction));
            }
            LlmRequest request = new LlmRequest(requestMessages, registry.definitions(context), !finalRound);
            debug.round(step);
            LlmResponse response = client.generate(request);
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
                        messages.subList(historySize, messages.size()), response, step, executions, finalRound);
            }
            // 即使服务商忽略 tool_choice=none，也不执行最后一轮提出的工具，更不提交残缺回答。
            if (finalRound) return new AgentResult(AgentResult.Status.BUDGET_EXHAUSTED,
                    messages.subList(historySize, messages.size()), null, step, executions, true);
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
