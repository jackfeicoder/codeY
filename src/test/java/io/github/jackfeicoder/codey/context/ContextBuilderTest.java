package io.github.jackfeicoder.codey.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.ReadFileTool;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** 请求与持久历史隔离、完整工具组、分项覆盖及每次 ReAct 请求更新；使用本地模型替身。 */
class ContextBuilderTest {
    @TempDir Path directory;
    private final ContextBuilder builder = new ContextBuilder();
    private final TokenEstimator estimator = new TokenEstimator();
    private static final ToolCall CALL = new ToolCall("c1", "read_file", "{\"path\":\"A.java\"}");

    @Test void preservesEveryMessageAndCountsSchemaArgumentsAndResultsWithoutMutatingHistory() {
        var history = new ArrayList<>(List.of(Message.system("base 提示词"), Message.user("old"),
                Message.assistantToolCalls(List.of(CALL)), Message.tool("c1", "old body"), Message.assistant("old answer")));
        var original = List.copyOf(history);
        var current = new ArrayList<>(List.of(Message.user("new question"),
                Message.assistant("checking", List.of(CALL)), Message.tool("c1", "new body")));
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string").put("description", "路径说明".repeat(100));
        var definition = new ToolDefinition("read_file", "read a file", schema);
        var snapshot = builder.build(history, current, List.of(definition), WorkMode.REACT, 1, 20);
        var expected = new ArrayList<>(history); expected.addAll(current);
        assertEquals(expected, snapshot.request().messages());
        assertEquals(original, history);
        assertEquals(estimator.textTokens("base 提示词"), snapshot.tokens().basePrompt());
        assertEquals(history.stream().skip(1).mapToLong(estimator::messageBodyTokens).sum(), snapshot.tokens().history());
        assertEquals(estimator.messageBodyTokens(current.get(1)), snapshot.tokens().assistant());
        assertEquals(estimator.messageBodyTokens(current.get(2)), snapshot.tokens().toolResults());
        assertTrue(snapshot.tokens().toolDefinitions() > estimator.textTokens(definition.description()) + 100);
        assertEquals(0, snapshot.tokens().runtimeInstructions());
        assertEquals(4L * expected.size() + 3, snapshot.tokens().protocolOverhead());
        assertTrue(snapshot.tokens().total() > snapshot.tokens().history());
        history.clear(); current.clear(); schema.put("changed", true);
        assertEquals(expected, snapshot.request().messages());
        assertFalse(snapshot.request().tools().get(0).parameters().has("changed"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.request().messages().clear());
    }

    @Test void previewIncludesBaseHistoryToolsButNoInventedUserAndFinalRoundKeepsToolDefinitions() {
        var history = List.of(Message.system("base"));
        var tool = new ReadFileTool().definition();
        var preview = builder.build(history, List.of(), List.of(tool), WorkMode.REACT, 1, 20);
        assertEquals(history, preview.request().messages());
        assertEquals(0, preview.tokens().userInput());
        assertTrue(preview.tokens().toolDefinitions() > 0);
        var last = builder.build(history, List.of(Message.user("task")), List.of(tool), WorkMode.PLAN, 20, 20);
        assertFalse(last.request().toolCallsAllowed());
        assertEquals(List.of(tool), last.request().tools());
        assertTrue(last.request().messages().get(0).content().contains("[当前工作模式：PLAN]"));
        assertTrue(last.request().messages().get(0).content().contains("工具调用已禁用"));
        assertTrue(last.tokens().runtimeInstructions() > 0);
        assertEquals("base", history.get(0).content());
    }

    @Test void nearLimitPromptAndMalformedHistoricalGroupsAreHandledBeforeSending() {
        var request = builder.build(List.of(Message.system("base")), List.of(Message.user("task")), List.of(), WorkMode.REACT, 18, 20);
        assertTrue(request.request().messages().get(0).content().contains("包括本次还剩 3 次"));
        assertTrue(request.request().toolCallsAllowed());
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(Message.system("base"), Message.user("open")), List.of(), List.of(), WorkMode.REACT, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(Message.user("base")), List.of(), List.of(), WorkMode.REACT, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(Message.system("base")), List.of(), List.of(), WorkMode.REACT, 21, 20));
    }

    static Stream<List<Message>> invalidTurns() {
        var user = Message.user("task"); var assistant = Message.assistantToolCalls(List.of(CALL));
        return Stream.of(List.of(Message.tool("c1", "orphan")), List.of(user, assistant),
                List.of(user, assistant, Message.tool("wrong", "result")),
                List.of(user, assistant, Message.tool("c1", "result"), Message.tool("c1", "duplicate")),
                List.of(user, assistant, Message.tool("c1", "result"), assistant, Message.tool("c1", "reused")),
                List.of(user, Message.system("injected")), List.of(user, Message.assistant("already final")),
                List.of(user, Message.user("another turn")));
    }

    @ParameterizedTest @MethodSource("invalidTurns")
    void rejectsBrokenCurrentTurnInsteadOfSilentlyDroppingParts(List<Message> current) {
        assertThrows(IllegalArgumentException.class, () -> builder.build(List.of(Message.system("base")), current, List.of(), WorkMode.REACT, 1, 20));
    }

    @Test void heuristicCountsMixedCodeChineseEmojiAndEmptyContentWithoutCallingVendor() {
        assertEquals(0, estimator.textTokens(null)); assertEquals(0, estimator.textTokens(""));
        assertEquals(1, estimator.textTokens("abc")); assertEquals(2, estimator.textTokens("abcd"));
        assertEquals(4, estimator.textTokens("中文")); assertEquals(3, estimator.textTokens("😀"));
        assertTrue(estimator.textTokens("public void 登录() { return; } 😀") > 10);
        assertTrue(estimator.textTokens("data".repeat(100000)) > Integer.MAX_VALUE / 100000);
    }

    @Test void agentRebuildsAfterToolResultAndCommitsOnlyOriginalCompleteTurn() throws Exception {
        Files.writeString(directory.resolve("A.java"), "class A {}".repeat(100));
        var session = new Session("base"); session.completeTurn("old", Message.assistant("old answer"));
        var original = session.messages();
        var snapshots = new ArrayList<ContextSnapshot>();
        var requests = new ArrayList<LlmRequest>();
        LlmClient client = request -> {
            requests.add(request);
            return requests.size() == 1 ? new LlmResponse(Message.assistantToolCalls(List.of(CALL)), "tool_calls", null)
                    : new LlmResponse(Message.assistant("done"), "stop", null);
        };
        var result = new AgentLoop(client, new ToolRegistry().register(new ReadFileTool()), 20, DebugPrinter.NONE,
                builder, snapshots::add).run(session, "read", new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toString()), ToolContext.DEFAULT_MAX_OUTPUT_CHARS));
        assertEquals(2, snapshots.size());
        assertSame(requests.get(0), snapshots.get(0).request());
        assertTrue(snapshots.get(1).tokens().total() > snapshots.get(0).tokens().total());
        assertTrue(snapshots.get(1).tokens().toolResults() > 0);
        assertEquals(original, session.messages());
        assertEquals(4, result.turnMessages().size());
        session.completeAgentTurn(result.turnMessages());
        assertEquals(7, session.messages().size());
        assertEquals("done", session.messages().get(6).content());
    }

    @Test void failedModelLeavesCanonicalHistoryIntactDespiteObservedContext() {
        var session = new Session("base"); var snapshots = new ArrayList<ContextSnapshot>();
        var loop = new AgentLoop(request -> { throw new LlmException(LlmException.Kind.HTTP, "fixture failure"); },
                new ToolRegistry(), 20, DebugPrinter.NONE, builder, snapshots::add);
        assertThrows(LlmException.class, () -> loop.run(session, "task", new ToolContext(WorkspaceBinding.none(), ToolContext.DEFAULT_MAX_OUTPUT_CHARS)));
        assertEquals(1, snapshots.size()); assertEquals(List.of(Message.system("base")), session.messages());
    }
}
