package io.github.jackfeicoder.codey.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次运行中的文本会话：保存基础指令与成功完成的对话轮次。
 * 请求快照包含当前问题，但只有收到可保存的回答后才提交本轮，失败不污染历史。
 * 当前由 CLI 单线程使用；持久化交给 SessionService/SessionStore，不负责长期记忆或压缩。
 */
public final class Session {
    /** 清空会话时仍保留的基础系统消息。 */
    private final Message systemMessage;
    /** 按 system、user、assistant 顺序保存的已提交历史，不直接暴露给调用方。 */
    private final List<Message> history = new ArrayList<>();

    /** 使用非空白基础提示词创建会话，系统消息始终位于第一项。 */
    public Session(String basePrompt) {
        if (basePrompt == null || basePrompt.isBlank()) {
            throw new IllegalArgumentException("Session requires a nonblank system prompt.");
        }
        systemMessage = Message.system(basePrompt);
        history.add(systemMessage);
    }

    /** 从已校验的快照恢复，系统提示词只注入一次，不自动向模型发送请求。 */
    public static Session restore(SessionSnapshot snapshot) {
        Session session = new Session(snapshot.systemPrompt());
        session.history.addAll(snapshot.messages());
        return session;
    }

    /** 返回不可修改的历史快照，后续轮次或 clear 不影响已经返回的列表。 */
    public List<Message> messages() {
        return List.copyOf(history);
    }

    /** 在历史副本末尾追加当前问题，供 LlmRequest 使用；此时不修改会话。 */
    public List<Message> messagesFor(String input) {
        // 先校验输入，再构造本次请求独立的消息列表。
        Message user = userMessage(input);
        List<Message> request = new ArrayList<>(history);
        request.add(user);
        return List.copyOf(request);
    }

    /** 成功调用后一次性保存用户问题和普通 assistant 回答；工具轮次后续由 Agent 接管。 */
    public void completeTurn(String input, Message answer) {
        // 所有校验都在修改历史之前完成，失败时原会话保持不变。
        Message user = userMessage(input);
        if (answer == null || answer.role() != MessageRole.ASSISTANT || !answer.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("Text sessions require an assistant answer without tool calls.");
        }
        history.add(user);
        history.add(answer);
    }

    /** 丢弃所有对话轮次，保留原始基础提示词，不重新读取资源或发送请求。 */
    public void clear() {
        history.clear();
        history.add(systemMessage);
    }

    /** 校验问题并保留原始文本，不修改用户输入的空格或换行。 */
    private static Message userMessage(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Session requires a nonblank user input.");
        }
        return Message.user(input);
    }
}
