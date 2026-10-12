package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证会话的轮次顺序、请求与历史隔离、清空，以及非法提交时状态不变。 */
class SessionTest {
    /** 请求包含当前问题但不预先保存，提交后下一轮能携带完整历史。 */
    @Test
    void maintainsOrderedCompletedTurns() {
        Session session = new Session("基础提示词"); // 单独的测试会话，不读取真实配置。
        assertEquals(List.of(Message.system("基础提示词"), Message.user("第一问")),
                session.messagesFor("第一问"));
        assertEquals(1, session.messages().size());
        session.completeTurn("第一问", Message.assistant("第一答"));
        assertEquals(List.of(Message.system("基础提示词"), Message.user("第一问"),
                Message.assistant("第一答"), Message.user("第二问")), session.messagesFor("第二问"));
    }

    /** 返回列表不可修改，清空会话也不会改变之前的请求快照。 */
    @Test
    void snapshotsAreImmutableAndIndependentOfClear() {
        Session session = new Session("base");
        session.completeTurn("  question  ", Message.assistant("answer"));
        List<Message> snapshot = session.messages(); // 清空前的历史快照。
        List<Message> request = session.messagesFor("next"); // 独立的请求快照。
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> request.clear());
        session.clear();
        assertEquals(List.of(Message.system("base")), session.messages());
        assertEquals(3, snapshot.size());
        assertEquals("  question  ", snapshot.get(1).content());
        assertEquals(4, request.size());
        session.clear();
        assertEquals(1, session.messages().size());
    }

    /** 空白或缺失问题既不能发送，也不能提交，不改变已成功完成的轮次。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void invalidInputLeavesHistoryUnchanged(String input) {
        Session session = new Session("base");
        session.completeTurn("good", Message.assistant("answer"));
        List<Message> before = session.messages(); // 检查失败前后的状态相同。
        assertThrows(IllegalArgumentException.class, () -> session.messagesFor(input));
        assertThrows(IllegalArgumentException.class, () -> session.completeTurn(input, Message.assistant("answer")));
        assertEquals(before, session.messages());
    }

    /** 非 assistant、工具请求或空回答对象不进入文本会话。 */
    @Test
    void invalidAnswerDoesNotAppendUserOrIncompleteToolGroup() {
        Session session = new Session("base");
        assertThrows(IllegalArgumentException.class, () -> session.completeTurn("question", null));
        assertThrows(IllegalArgumentException.class, () -> session.completeTurn("question", Message.user("bad")));
        Message tools = Message.assistantToolCalls(List.of(new ToolCall("call-1", "read_file", "{}")));
        assertThrows(IllegalArgumentException.class, () -> session.completeTurn("question", tools));
        assertEquals(List.of(Message.system("base")), session.messages());
    }

    /** 会话需要有效基础指令，不将空占位内容当作系统提示词。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n"})
    void requiresNonblankSystemPrompt(String prompt) {
        assertThrows(IllegalArgumentException.class, () -> new Session(prompt));
    }
}
