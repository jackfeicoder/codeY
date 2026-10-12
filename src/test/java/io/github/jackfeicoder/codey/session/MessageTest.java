package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.tool.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 消息模型测试：验证普通对话、工具请求/结果关联、角色约束及列表不可变性。 */
class MessageTest {
    /** 复用的 read_file 调用样例，只是数据，不会执行。 */
    private static final ToolCall READ = new ToolCall("call_read", "read_file", "{\"path\":\"Main.java\"}");

    /** 验证系统、用户和模型文本按原内容保留。 */
    @Test
    void preservesOrdinaryConversationContentExactly() {
        // 测试对话列表，用于验证顺序与防御性复制。
        List<Message> history = List.of(Message.system("你是编程助手。"),
                Message.user("解释代码\n保持换行"), Message.assistant("代码说明"));
        assertEquals(MessageRole.SYSTEM, history.get(0).role());
        assertEquals("解释代码\n保持换行", history.get(1).content());
        assertEquals(MessageRole.ASSISTANT, history.get(2).role());
        assertTrue(history.stream().allMatch(message -> message.toolCalls().isEmpty()));
        assertTrue(history.stream().allMatch(message -> message.toolCallId() == null));
    }

    /** 验证一条模型消息的多个调用可分别关联工具结果。 */
    @Test
    void representsACompleteMultipleToolCallMessageGroup() {
        // 第二个工具调用样例，用于验证多个调用独立关联。
        ToolCall list = new ToolCall("call_list", "list_files", "{}");
        // 模型发起工具调用的 assistant 消息。
        Message request = Message.assistantToolCalls(List.of(READ, list));
        // 与读取调用对应的工具结果消息。
        Message readResult = Message.tool(READ.id(), "public class Main {}");
        // 与列表调用对应的工具结果消息。
        Message listResult = Message.tool(list.id(), "Main.java");
        assertNull(request.content());
        assertEquals(MessageRole.ASSISTANT, request.role());
        assertEquals(List.of(readResult.toolCallId(), listResult.toolCallId()),
                request.toolCalls().stream().map(ToolCall::id).toList());
        assertEquals(MessageRole.TOOL, readResult.role());
        assertTrue(readResult.toolCalls().isEmpty());
    }

    /** 验证模型文本可与工具请求共存，工具结果可为空文本。 */
    @Test
    void allowsAssistantTextAlongsideToolCallsAndEmptyToolOutput() {
        // 模型发起工具调用的 assistant 消息。
        Message request = Message.assistant("先读取文件。", List.of(READ));
        assertEquals("先读取文件。", request.content());
        assertEquals(READ, request.toolCalls().get(0));
        assertEquals("", Message.tool(READ.id(), "").content());
    }

    /** 验证工具列表经过复制，且消息内部列表不可修改。 */
    @Test
    void toolCallListIsIndependentOfCallerAndUnmodifiable() {
        // 可变输入列表，用于确认对象创建后不会受外部改动影响。
        List<ToolCall> calls = new ArrayList<>(List.of(READ));
        // 本次构造的不可变消息，用于验证列表复制。
        Message message = Message.assistantToolCalls(calls);
        calls.clear();
        assertEquals(List.of(READ), message.toolCalls());
        assertThrows(UnsupportedOperationException.class, () -> message.toolCalls().clear());
    }

    /** 验证只有 assistant 可携带工具调用请求。 */
    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "USER", "TOOL"})
    void rejectsToolRequestsFromOtherRoles(MessageRole role) {
        assertThrows(IllegalArgumentException.class,
                () -> new Message(role, "text", List.of(READ), role == MessageRole.TOOL ? READ.id() : null));
    }

    /** 验证只有 tool 消息可携带结果关联 ID。 */
    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "USER", "ASSISTANT"})
    void rejectsToolResultIdsFromOtherRoles(MessageRole role) {
        assertThrows(IllegalArgumentException.class, () -> new Message(role, "text", List.of(), READ.id()));
    }

    /** 验证工具结果必须能够关联原调用。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsToolResultsWithoutCallId(String id) {
        assertThrows(IllegalArgumentException.class, () -> Message.tool(id, "output"));
    }

    /** 验证没有工具请求时，各角色消息均需要内容。 */
    @ParameterizedTest
    @EnumSource(MessageRole.class)
    void rejectsNullContentWhenThereIsNoToolRequest(MessageRole role) {
        assertThrows(IllegalArgumentException.class,
                () -> new Message(role, null, List.of(), role == MessageRole.TOOL ? READ.id() : null));
    }

    /** 验证调用 ID 在单条消息中唯一且列表不含空元素。 */
    @Test
    void rejectsDuplicateToolIdsAndNullCalls() {
        // 故意使用重复 ID 的工具调用，用于验证消息约束。
        ToolCall duplicate = new ToolCall(READ.id(), "list_files", "{}");
        assertThrows(IllegalArgumentException.class,
                () -> Message.assistantToolCalls(List.of(READ, duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> Message.assistantToolCalls(Arrays.asList(READ, null)));
    }

    /** 验证消息角色必填，纯工具请求需要实际调用。 */
    @Test
    void rejectsMissingRoleAndEmptyToolRequest() {
        assertThrows(IllegalArgumentException.class, () -> new Message(null, "text", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> Message.assistantToolCalls(List.of()));
    }

    /** 验证 Java 枚举与小写协议角色可以互相转换。 */
    @ParameterizedTest
    @EnumSource(MessageRole.class)
    void mapsRolesToLowercaseProtocolValues(MessageRole role) {
        assertEquals(role, MessageRole.fromWireValue(role.wireValue()));
        assertEquals(role.name().toLowerCase(java.util.Locale.ROOT), role.wireValue());
    }

    /** 验证角色解析严格匹配受支持的协议值。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "USER"})
    void rejectsUnknownProtocolRoles(String role) {
        assertThrows(IllegalArgumentException.class, () -> MessageRole.fromWireValue(role));
    }
}
