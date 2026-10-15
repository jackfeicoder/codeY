package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

/** 对话展示入口：读取用户任务 → AgentLoop 执行 → 展示最终回答 → 提交完整成功轮次。 */
public final class ChatCli {
    /** 宿主装配的单任务循环，不把模型与工具逻辑放进 CLI。 */
    private final AgentLoop agent;
    /** 当前会话的工作区与输出预算，不能由模型参数替换。 */
    private final ToolContext context;
    /** 当前运行的会话记录。 */
    private final Session session;
    /** 可替换的输入流，既支持终端，也便于使用内存文本验证交互流程。 */
    private final ConsoleInput input;
    /** 正常回答与交互提示的输出。 */
    private final PrintWriter output;
    /** 调用错误、截断和预算退出提示。 */
    private final PrintWriter errors;

    /** 接收已装配的组件与输入输出，不读取配置，也不关闭调用方提供的流。 */
    public ChatCli(LlmClient client, Session session, BufferedReader input,
                   PrintWriter output, PrintWriter errors) {
        this(new AgentLoop(client, new ToolRegistry()), session, new ToolContext(WorkspaceBinding.none()),
                input, output, errors);
    }

    /** 完整装配入口；持久化启动器传入注册工具与真实会话工作区。 */
    public ChatCli(AgentLoop agent, Session session, ToolContext context, BufferedReader input,
                   PrintWriter output, PrintWriter errors) {
        this(agent, session, context, new ConsoleInput(input, output), output, errors);
    }

    /** 持久化启动器复用正在使用的终端输入，模型执行过程中不会另开 Reader。 */
    public ChatCli(AgentLoop agent, Session session, ToolContext context, ConsoleInput input,
                   PrintWriter output, PrintWriter errors) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.context = Objects.requireNonNull(context, "context");
        this.session = Objects.requireNonNull(session, "session");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** 逐行处理直到 /exit、输入结束或线程中断；普通模型错误后允许继续输入。 */
    public void run() throws IOException {
        output.println("CodeY chat. Commands: /clear, /exit. History is kept only for this run.");
        while (!Thread.currentThread().isInterrupted()) {
            output.flush();
            // null 表示 EOF，例如管道结束或终端关闭，不再次请求模型。
            String line = input.readLine("You> ");
            if (line == null || "/exit".equals(line.strip()) || "exit".equalsIgnoreCase(line.strip())) {
                output.println("Bye.");
                output.flush();
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            // strip 仅用于命令识别，普通问题仍保留原始文本。
            String command = line.strip();
            if ("/clear".equals(command)) {
                session.clear();
                output.println("Conversation cleared. System prompt retained.");
                continue;
            }
            if (command.startsWith("/")) {
                output.println("Unknown command. Available commands: /clear, /exit.");
                continue;
            }
            try {
                answer(line);
            } catch (LlmException failed) {
                errors.println("LLM error [" + failed.kind() + "]: " + failed.getMessage()
                        + " This turn was not saved.");
                if (failed.kind() == LlmException.Kind.INTERRUPTED) {
                    // 中断请求后结束交互，避免继续阻塞在终端输入。
                    output.flush();
                    errors.flush();
                    return;
                }
            }
            output.flush();
            errors.flush();
        }
    }

    /** 执行一条任务；true 表示完整消息组已提交，持久化入口随后保存。 */
    public boolean answer(String line) {
        var result = agent.run(session, line, context); // 失败或预算退出不会污染已提交历史。
        if (!result.completed()) {
            errors.println("Agent stopped [BUDGET_EXHAUSTED] after " + result.modelCalls()
                    + " model requests and " + result.toolCalls() + " tool calls. This turn was not saved.");
            errors.flush();
            return false;
        }
        session.completeAgentTurn(result.turnMessages());
        var response = result.response();
        output.println("CodeY> " + response.text());
        if (result.budgetLimited()) {
            errors.println("本轮已到模型请求上限，回答基于现有信息；需要进一步调查可继续追问。");
        }
        if (response.isTruncated()) {
            errors.println("Model output was truncated (finish_reason=length).");
        }
        output.flush();
        errors.flush();
        return true;
    }
}
