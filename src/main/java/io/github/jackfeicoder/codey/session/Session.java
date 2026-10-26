package io.github.jackfeicoder.codey.session;

import java.util.ArrayList;
import java.util.List;
import io.github.jackfeicoder.codey.context.CompactionState;

/**
 * 单次运行中的会话：保存基础指令与成功完成的文本/工具对话轮次。
 * 请求快照包含当前问题，但只有收到可保存的回答后才提交本轮，失败不污染历史。
 * 当前由 CLI 单线程使用；保管已验收的摘要状态，生成交给 HistoryCompactor，持久化交给 SessionService。
 */
public final class Session {
    /** 清空会话时仍保留的基础系统消息。 */
    private final Message systemMessage;
    /** 保存 system 和完整轮次，可包含 assistant/tool 消息组，不直接暴露。 */
    private final List<Message> history = new ArrayList<>();
    /** 派生的摘要覆盖信息，和原始 history 分开；成功压缩后才替换。 */
    private CompactionState compaction;

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
        session.compaction = snapshot.compaction();
        return session;
    }

    /** 返回不可修改的历史快照，后续轮次或 clear 不影响已经返回的列表。 */
    public List<Message> messages() {
        return List.copyOf(history);
    }

    public CompactionState compaction() { return compaction; }
    /** 只接受与当前原始前缀匹配的摘要，不删除或重写消息。 */
    public void applyCompaction(CompactionState state) {
        java.util.Objects.requireNonNull(state).validate(history.subList(1, history.size()));
        compaction = state;
    }

    /** 原始历史便捷视图，不应用摘要；生产请求统一走 ContextBuilder，此时不修改会话。 */
    public List<Message> messagesFor(String input) {
        // 先校验输入，再构造本次请求独立的消息列表。
        Message user = userMessage(input);
        List<Message> request = new ArrayList<>(history);
        request.add(user);
        return List.copyOf(request);
    }

    /** 普通文本轮次的便捷入口；工具对话使用完整消息组提交。 */
    public void completeTurn(String input, Message answer) {
        // 所有校验都在修改历史之前完成，失败时原会话保持不变。
        Message user = userMessage(input);
        if (answer == null || answer.role() != MessageRole.ASSISTANT || !answer.toolCalls().isEmpty()) {
            throw new IllegalArgumentException("Text sessions require an assistant answer without tool calls.");
        }
        history.add(user);
        history.add(answer);
    }

    /** 原子提交 Agent 返回的完整一轮；缺少工具结果或最终回答时拒绝修改历史。 */
    public void completeAgentTurn(List<Message> turn) {
        MessageHistory.validate(turn);
        if (turn.isEmpty() || turn.stream().filter(message -> message.role() == MessageRole.USER).count() != 1) {
            throw new IllegalArgumentException("Agent commit requires exactly one complete user turn.");
        }
        history.addAll(List.copyOf(turn));
    }

    /** 丢弃所有对话轮次，保留原始基础提示词，不重新读取资源或发送请求。 */
    public void clear() {
        history.clear();
        history.add(systemMessage);
        compaction = null;
    }

    /** 校验问题并保留原始文本，不修改用户输入的空格或换行。 */
    private static Message userMessage(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Session requires a nonblank user input.");
        }
        return Message.user(input);
    }
}
