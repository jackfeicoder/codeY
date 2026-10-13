package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 完整工具消息组才能提交；逐消息的 Message 校验不能替代历史顺序校验。 */
class MessageHistoryTest {
    /** 配对可按任意结果顺序，多个工具步骤后跟最终回答才是完整任务。 */
    @Test
    void acceptsCompleteMultiToolTurnAndOldTextTurns() {
        var turn = List.of(Message.user("q"), calls("a", "b"), Message.tool("b", "error"),
                Message.tool("a", "success"), calls("c"), Message.tool("c", "observed"), Message.assistant("done"));
        Session session = new Session("base");
        session.completeTurn("old", Message.assistant("old answer"));
        session.completeAgentTurn(turn);
        assertEquals(10, session.messages().size());
        MessageHistory.validate(List.of());
    }

    /** 缺失/重复/未知结果、中断消息组、夹入 user/system 都不允许提交。 */
    @ParameterizedTest
    @MethodSource("invalidHistories")
    void rejectsIncompleteOrMisorderedHistoryWithoutMutatingSession(List<Message> turn) {
        Session session = new Session("base");
        assertThrows(IllegalArgumentException.class, () -> session.completeAgentTurn(turn));
        assertEquals(List.of(Message.system("base")), session.messages());
    }

    /** 所有单条消息本身均合法，组合后才暴露关联与顺序错误。 */
    private static Stream<List<Message>> invalidHistories() {
        return Stream.of(
                List.of(),
                List.of(Message.user("q")),
                List.of(Message.user("q"), calls("a"), Message.assistant("done")),
                List.of(Message.user("q"), calls("a"), Message.tool("wrong", "result"), Message.assistant("done")),
                List.of(Message.user("q"), calls("a", "b"), Message.tool("a", "r"), Message.tool("a", "r"), Message.assistant("done")),
                List.of(Message.user("q"), calls("a"), Message.tool("a", "r")),
                List.of(Message.user("q"), calls("a"), Message.tool("a", "r"), calls("a"), Message.tool("a", "r"), Message.assistant("done")),
                List.of(Message.user("q"), Message.system("injected"), Message.assistant("done")),
                List.of(Message.user("q"), Message.assistant("done"), Message.tool("a", "extra")),
                List.of(Message.user("q"), Message.assistant("done"), Message.user("second"), Message.assistant("done")),
                List.of(Message.user(" "), Message.assistant("done")));
    }

    /** 构造工具组，结果中的关联 ID 必须对应这些调用。 */
    private static Message calls(String... ids) {
        return Message.assistantToolCalls(Stream.of(ids).map(id -> new ToolCall(id, "read_file", "{}")).toList());
    }
}
