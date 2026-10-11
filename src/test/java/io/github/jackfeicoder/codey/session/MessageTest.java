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

class MessageTest {
    private static final ToolCall READ = new ToolCall("call_read", "read_file", "{\"path\":\"Main.java\"}");

    @Test
    void preservesOrdinaryConversationContentExactly() {
        List<Message> history = List.of(Message.system("你是编程助手。"),
                Message.user("解释代码\n保持换行"), Message.assistant("代码说明"));
        assertEquals(MessageRole.SYSTEM, history.get(0).role());
        assertEquals("解释代码\n保持换行", history.get(1).content());
        assertEquals(MessageRole.ASSISTANT, history.get(2).role());
        assertTrue(history.stream().allMatch(message -> message.toolCalls().isEmpty()));
        assertTrue(history.stream().allMatch(message -> message.toolCallId() == null));
    }

    @Test
    void representsACompleteMultipleToolCallMessageGroup() {
        ToolCall list = new ToolCall("call_list", "list_files", "{}");
        Message request = Message.assistantToolCalls(List.of(READ, list));
        Message readResult = Message.tool(READ.id(), "public class Main {}");
        Message listResult = Message.tool(list.id(), "Main.java");
        assertNull(request.content());
        assertEquals(MessageRole.ASSISTANT, request.role());
        assertEquals(List.of(readResult.toolCallId(), listResult.toolCallId()),
                request.toolCalls().stream().map(ToolCall::id).toList());
        assertEquals(MessageRole.TOOL, readResult.role());
        assertTrue(readResult.toolCalls().isEmpty());
    }

    @Test
    void allowsAssistantTextAlongsideToolCallsAndEmptyToolOutput() {
        Message request = Message.assistant("先读取文件。", List.of(READ));
        assertEquals("先读取文件。", request.content());
        assertEquals(READ, request.toolCalls().get(0));
        assertEquals("", Message.tool(READ.id(), "").content());
    }

    @Test
    void toolCallListIsIndependentOfCallerAndUnmodifiable() {
        List<ToolCall> calls = new ArrayList<>(List.of(READ));
        Message message = Message.assistantToolCalls(calls);
        calls.clear();
        assertEquals(List.of(READ), message.toolCalls());
        assertThrows(UnsupportedOperationException.class, () -> message.toolCalls().clear());
    }

    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "USER", "TOOL"})
    void rejectsToolRequestsFromOtherRoles(MessageRole role) {
        assertThrows(IllegalArgumentException.class,
                () -> new Message(role, "text", List.of(READ), role == MessageRole.TOOL ? READ.id() : null));
    }

    @ParameterizedTest
    @EnumSource(value = MessageRole.class, names = {"SYSTEM", "USER", "ASSISTANT"})
    void rejectsToolResultIdsFromOtherRoles(MessageRole role) {
        assertThrows(IllegalArgumentException.class, () -> new Message(role, "text", List.of(), READ.id()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsToolResultsWithoutCallId(String id) {
        assertThrows(IllegalArgumentException.class, () -> Message.tool(id, "output"));
    }

    @ParameterizedTest
    @EnumSource(MessageRole.class)
    void rejectsNullContentWhenThereIsNoToolRequest(MessageRole role) {
        assertThrows(IllegalArgumentException.class,
                () -> new Message(role, null, List.of(), role == MessageRole.TOOL ? READ.id() : null));
    }

    @Test
    void rejectsDuplicateToolIdsAndNullCalls() {
        ToolCall duplicate = new ToolCall(READ.id(), "list_files", "{}");
        assertThrows(IllegalArgumentException.class,
                () -> Message.assistantToolCalls(List.of(READ, duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> Message.assistantToolCalls(Arrays.asList(READ, null)));
    }

    @Test
    void rejectsMissingRoleAndEmptyToolRequest() {
        assertThrows(IllegalArgumentException.class, () -> new Message(null, "text", List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> Message.assistantToolCalls(List.of()));
    }

    @ParameterizedTest
    @EnumSource(MessageRole.class)
    void mapsRolesToLowercaseProtocolValues(MessageRole role) {
        assertEquals(role, MessageRole.fromWireValue(role.wireValue()));
        assertEquals(role.name().toLowerCase(java.util.Locale.ROOT), role.wireValue());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "USER"})
    void rejectsUnknownProtocolRoles(String role) {
        assertThrows(IllegalArgumentException.class, () -> MessageRole.fromWireValue(role));
    }
}
