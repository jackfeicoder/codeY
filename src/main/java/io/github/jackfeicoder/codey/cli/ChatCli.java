package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.Session;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

/** 文本对话入口：读取一行 → 请求全部历史 → 展示回答 → 保存成功轮次；不执行工具。 */
public final class ChatCli {
    /** 启动时装配的模型客户端，本阶段会话中固定使用同一 Profile。 */
    private final LlmClient client;
    /** 当前运行的会话记录。 */
    private final Session session;
    /** 可替换的输入流，既支持终端，也便于使用内存文本验证交互流程。 */
    private final BufferedReader input;
    /** 正常回答与交互提示的输出。 */
    private final PrintWriter output;
    /** 调用错误、截断和暂未支持的工具请求提示。 */
    private final PrintWriter errors;

    /** 接收已装配的组件与输入输出，不读取配置，也不关闭调用方提供的流。 */
    public ChatCli(LlmClient client, Session session, BufferedReader input,
                   PrintWriter output, PrintWriter errors) {
        this.client = Objects.requireNonNull(client, "client");
        this.session = Objects.requireNonNull(session, "session");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** 逐行处理直到 /exit、输入结束或线程中断；普通模型错误后允许继续输入。 */
    public void run() throws IOException {
        output.println("CodeY chat. Commands: /clear, /exit. History is kept only for this run.");
        while (!Thread.currentThread().isInterrupted()) {
            output.print("You> ");
            output.flush();
            // null 表示 EOF，例如管道结束或终端关闭，不再次请求模型。
            String line = input.readLine();
            if (line == null || "/exit".equals(line.strip())) {
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

    /** 发送一轮并展示结果；true 表示已提交普通回答，由持久化入口随后保存。 */
    public boolean answer(String line) {
        // 当前问题只放入请求副本，调用失败时无需回滚历史。
        LlmResponse response = client.generate(new LlmRequest(session.messagesFor(line)));
        boolean completed = response.toolCalls().isEmpty();
        if (completed) {
            session.completeTurn(line, response.message());
        } else {
            errors.println("Tool execution is not implemented yet. This turn was not saved.");
        }
        output.println("CodeY> " + response.text());
        if (response.isTruncated()) {
            errors.println("Model output was truncated (finish_reason=length).");
        }
        output.flush();
        errors.flush();
        return completed;
    }
}
