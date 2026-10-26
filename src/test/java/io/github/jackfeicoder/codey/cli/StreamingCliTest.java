package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 展示分片即时可见，只有完整任务入历史；会话装饰客户端不可把流退化为 generate。 */
class StreamingCliTest {
    @TempDir Path directory;
    private final Session session = new Session("base");
    private final StringWriter output = new StringWriter(), errors = new StringWriter();
    private ChatCli chat(LlmClient client, ToolRegistry registry) {
        return new ChatCli(new AgentLoop(client, registry), session, new ToolContext(WorkspaceBinding.none()),
                new BufferedReader(new StringReader("")), new PrintWriter(output), new PrintWriter(errors));
    }
    private abstract static class StreamingClient implements LlmClient {
        @Override public LlmResponse generate(LlmRequest request) { throw new AssertionError("Must preserve streaming dispatch"); }
    }
    private static LlmResponse answer(String text) { return new LlmResponse(Message.assistant(text), "stop", null); }

    @Test void rendersBeforeResponseReturnsAndCommitsExactlyOneFullAnswer() {
        var client = new StreamingClient() {
            @Override public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) {
                onText.accept("第一段"); assertTrue(output.toString().contains("CodeY> 第一段"));
                assertEquals(1, session.messages().size());
                onText.accept("第二段"); return answer("第一段第二段");
            }
        };
        assertTrue(chat(client, new ToolRegistry()).answer("问题"));
        assertEquals(3, session.messages().size()); assertEquals("第一段第二段", session.messages().get(2).content());
        assertEquals(1, output.toString().split("第一段第二段", -1).length - 1);
        assertEquals(1, output.toString().split("CodeY>", -1).length - 1);
    }

    @Test void partialFailureIsMarkedAndDoesNotEnterHistory() {
        var client = new StreamingClient() {
            @Override public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) {
                onText.accept("尚未完成的回答"); throw new LlmException(LlmException.Kind.NETWORK, "fixture stream interruption");
            }
        };
        assertThrows(LlmException.class, () -> chat(client, new ToolRegistry()).answer("问题"));
        assertTrue(output.toString().contains("尚未完成的回答")); assertTrue(errors.toString().contains("本轮未保存"));
        assertTrue(output.toString().endsWith(System.lineSeparator())); assertEquals(1, session.messages().size());
    }

    @Test void toolPreambleAndFinalAnswerUseSeparateSectionsAndKeepToolHistory() {
        var round = new AtomicInteger(); var executions = new AtomicInteger();
        Tool probe = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("probe", "probe", new ObjectMapper().createObjectNode().put("type", "object")); }
            public ToolAccess access() { return ToolAccess.READ_ONLY; }
            public void validateArguments(JsonNode arguments, ToolContext context) { }
            public String execute(JsonNode arguments, ToolContext context) { executions.incrementAndGet(); return "private-result"; }
        };
        var client = new StreamingClient() {
            @Override public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) {
                if (round.incrementAndGet() == 1) {
                    onText.accept("先读取。");
                    return new LlmResponse(Message.assistant("先读取。", List.of(new ToolCall("call-1", "probe", "{}"))), "tool_calls", null);
                }
                assertEquals("call-1", request.messages().get(request.messages().size() - 1).toolCallId());
                onText.accept("最终"); onText.accept("结论。"); return answer("最终结论。");
            }
        };
        assertTrue(chat(client, new ToolRegistry().register(probe)).answer("问题"));
        assertEquals(1, executions.get()); assertEquals(5, session.messages().size());
        assertEquals(2, output.toString().split("CodeY>", -1).length - 1);
        assertTrue(output.toString().contains("最终结论。")); assertFalse(output.toString().contains("private-result"));
    }

    @Test void clientWithoutTextCallbacksStillDisplaysFinalResponseOnce() {
        var client = new StreamingClient() {
            @Override public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) { return answer("fallback-visible"); }
        };
        assertTrue(chat(client, new ToolRegistry()).answer("问题")); assertEquals(1, output.toString().split("fallback-visible", -1).length - 1);
    }

    @Test void sessionLauncherForwardsStreamWithoutStatusInsideBodyAndSavesFullText() throws Exception {
        var streamCalls = new AtomicInteger();
        var client = new StreamingClient() {
            @Override public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) {
                streamCalls.incrementAndGet(); onText.accept("流式"); assertTrue(output.toString().contains("CodeY> 流式"));
                onText.accept("回答"); return new LlmResponse(Message.assistant("流式回答"), "stop", new LlmResponse.TokenUsage(321L, 4L, 325L));
            }
        };
        var out = new PrintWriter(output); var store = new JsonSessionStore(directory.resolve("sessions"));
        var input = new ConsoleInput(new BufferedReader(new StringReader("问题\n/exit\n")), out);
        int exit = new SessionLauncher(directory, new SessionSettings(directory.resolve("data"), null, false), store,
                profile -> new SessionLauncher.ModelChoice("fixture model-a", client, client), () -> "base", input,
                out, new PrintWriter(errors), new SessionLauncher.Options(null, true, "stream", null, null), new ToolRegistry(),
                DebugPrinter.NONE, List::of, 20, new PermissionSettings()).run();
        assertEquals(0, exit); assertEquals(1, streamCalls.get()); assertEquals("", errors.toString());
        assertTrue(output.toString().contains("CodeY> 流式回答")); assertTrue(output.toString().contains("上次输入 321"));
        try (var files = Files.walk(directory)) {
            Path snapshot = files.filter(path -> path.getFileName().toString().equals("session.json")).findFirst().orElseThrow();
            JsonNode messages = new ObjectMapper().readTree(Files.readString(snapshot)).get("messages");
            assertEquals("流式回答", messages.get(messages.size() - 1).path("content").asText());
        }
    }

    @Test void presentationPreservesSplitUnicodeAndCrLfAndEscapesTerminalCommands() {
        var stream = new ConsoleStreamOutput(new ConsoleTheme(false), new PrintWriter(output), () -> { }, true);
        stream.onResponseStart(1); stream.onText("\ud83d"); stream.onText("\ude00\r"); stream.onText("\n\u001b");
        stream.onText("[2J\t正文"); stream.onResponseEnd(1);
        assertTrue(output.toString().contains("CodeY> 😀\n\\u001b[2J\t正文"));
        assertFalse(output.toString().contains("\u001b")); assertFalse(output.toString().contains("\\ud83d"));
    }
}
