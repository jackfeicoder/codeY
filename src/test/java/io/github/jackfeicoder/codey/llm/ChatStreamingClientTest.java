package io.github.jackfeicoder.codey.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.ReadFileTool;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实本地 HTTP SSE：验证首片提前到达、工具聚合、错误、慢流及取消，无真实模型请求。 */
@Timeout(10)
class ChatStreamingClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private ExecutorService threads;
    private volatile HttpHandler handler;
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        threads = Executors.newCachedThreadPool(); server.setExecutor(threads);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); request.set(json.readTree(exchange.getRequestBody())); handler.handle(exchange);
        }); server.start();
    }
    @AfterEach void close() { release.countDown(); server.stop(0); threads.shutdownNow(); }
    private ChatCompletionsClient client(Duration timeout, StreamingSettings settings) {
        return new ChatCompletionsClient(new LlmConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "", "fixture-model", timeout, AuthMode.NONE), settings);
    }
    private ChatCompletionsClient client() { return client(Duration.ofSeconds(5), new StreamingSettings()); }
    private LlmRequest request() { return new LlmRequest(List.of(Message.system("base"), Message.user("你好"))); }
    private static void start(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8"); exchange.sendResponseHeaders(200, 0);
    }
    private static void send(HttpExchange exchange, String text) throws IOException {
        exchange.getResponseBody().write(text.getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
    }
    private String event(ObjectNode delta, String finish) {
        var root = json.createObjectNode();
        var choice = root.putArray("choices").addObject().put("index", 0).set("delta", delta);
        if (finish == null) ((ObjectNode) choice).putNull("finish_reason"); else ((ObjectNode) choice).put("finish_reason", finish);
        return "data: " + root + "\n\n";
    }
    private String text(String text) { return event(json.createObjectNode().put("content", text), null); }
    private String done() { return event(json.createObjectNode(), "stop") + "data: [DONE]\n\n"; }
    private ObjectNode call(int index, String id, String name, String args) {
        var delta = json.createObjectNode(); var item = delta.putArray("tool_calls").addObject().put("index", index);
        if (id != null) item.put("id", id).put("type", "function");
        var function = item.putObject("function");
        if (name != null) function.put("name", name);
        if (args != null) function.put("arguments", args);
        return delta;
    }

    @Test void firstDeltaArrivesBeforeCompletionWithUnicodeAndFinalUsage() throws Exception {
        var first = new CountDownLatch(1);
        handler = exchange -> {
            start(exchange);
            send(exchange, "\ufeff: heartbeat\r\nevent: message\r\n\r\n" + text("你好").replace("\n", "\r\n"));
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            // UTF-8 字节被任意拆包也不应拆坏中文/emoji。
            for (byte value : text("，世界😀").getBytes(StandardCharsets.UTF_8)) { exchange.getResponseBody().write(value); exchange.getResponseBody().flush(); }
            send(exchange, event(json.createObjectNode(), "stop"));
            send(exchange, "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":4,\"total_tokens\":127}}\n\n");
            send(exchange, "data: [DONE]\n\n"); exchange.close();
        };
        var parts = new CopyOnWriteArrayList<String>();
        var future = threads.submit(() -> client().generateStream(request(), part -> { parts.add(part); first.countDown(); }));
        assertTrue(first.await(2, TimeUnit.SECONDS)); assertFalse(future.isDone()); assertEquals("你好", String.join("", parts));
        release.countDown(); var result = future.get(3, TimeUnit.SECONDS);
        assertEquals("你好，世界😀", result.text()); assertEquals(result.text(), String.join("", parts));
        assertEquals(123L, result.usage().promptTokens()); assertEquals(127L, result.usage().totalTokens());
        assertTrue(request.get().path("stream").asBoolean()); assertTrue(request.get().at("/stream_options/include_usage").asBoolean());
        assertEquals(1, requests.get());
    }

    @Test void crOnlySseEventsAreDeliveredWithoutWaitingForNextPacket() throws Exception {
        var first = new CountDownLatch(1);
        handler = exchange -> {
            start(exchange); send(exchange, text("first").replace("\n", "\r"));
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            send(exchange, done().replace("\n", "\r")); exchange.close();
        };
        var future = threads.submit(() -> client().generateStream(request(), part -> first.countDown()));
        assertTrue(first.await(2, TimeUnit.SECONDS)); assertFalse(future.isDone()); release.countDown();
        assertEquals("first", future.get(3, TimeUnit.SECONDS).text());
    }

    @Test void interleavedToolFragmentsAreSortedAndOnlyTextIsEmitted() {
        handler = exchange -> {
            start(exchange); send(exchange, text("先读取文件。"));
            send(exchange, event(call(1, "call-b", "read_", "{\"path\":\"B"), null));
            send(exchange, event(call(0, "call-a", "read_file", "{\"path\":\"A"), null));
            send(exchange, event(call(1, null, "file", ".java\"}"), null));
            send(exchange, event(call(0, null, null, ".java\"}"), null));
            send(exchange, event(json.createObjectNode(), "tool_calls") + "data: [DONE]\n\n"); exchange.close();
        };
        StringBuilder displayed = new StringBuilder(); var result = client().generateStream(request(), displayed::append);
        assertEquals("先读取文件。", displayed.toString()); assertEquals("tool_calls", result.finishReason());
        assertEquals(List.of(new ToolCall("call-a", "read_file", "{\"path\":\"A.java\"}"),
                new ToolCall("call-b", "read_file", "{\"path\":\"B.java\"}")), result.toolCalls());
        assertNull(result.usage());
    }

    @ParameterizedTest @ValueSource(strings={"empty_placeholder", "repeated_id", "late_id"})
    void compatibleIdPlaceholdersAndRepeatedIdsKeepOneStableCall(String mode) {
        handler = exchange -> {
            start(exchange);
            send(exchange, event(call(0, mode.equals("late_id") ? "" : "stable-call", "read_file", "{\"path\":"), null));
            send(exchange, event(call(0, mode.equals("empty_placeholder") ? "" : "stable-call", null, "\"a.txt\"}"), null));
            send(exchange, event(json.createObjectNode(), "tool_calls") + "data: [DONE]\n\n"); exchange.close();
        };
        var result = client().generateStream(request(), part -> { });
        assertEquals(List.of(new ToolCall("stable-call", "read_file", "{\"path\":\"a.txt\"}")), result.toolCalls());
    }

    @ParameterizedTest @ValueSource(strings={"conflict", "oversized", "whitespace", "only_empty"})
    void ambiguousOrInvalidIdsAreDiagnosedAndEntireGroupIsNeverExecuted(String mode) {
        handler = exchange -> {
            start(exchange);
            send(exchange, event(call(0, mode.equals("only_empty") ? "" : "fixture-secret-a", "probe", "{}"), null));
            String next = switch (mode) {
                case "conflict" -> "fixture-secret-b";
                case "oversized" -> "x".repeat(1025);
                case "whitespace" -> "   ";
                default -> "";
            };
            send(exchange, event(call(0, next, null, null), null));
            send(exchange, event(json.createObjectNode(), "tool_calls") + "data: [DONE]\n\n"); exchange.close();
        };
        var executions = new AtomicInteger();
        Tool probe = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("probe", "probe", json.createObjectNode().put("type", "object")); }
            public ToolAccess access() { return ToolAccess.READ_ONLY; }
            public void validateArguments(JsonNode args, ToolContext context) { }
            public String execute(JsonNode args, ToolContext context) { executions.incrementAndGet(); return "ok"; }
        };
        var session = new Session("base");
        var error = assertThrows(LlmException.class, () -> new AgentLoop(client(), new ToolRegistry().register(probe))
                .run(session, "run", new ToolContext(WorkspaceBinding.none()), part -> { }));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind()); assertEquals(0, executions.get());
        assertEquals(List.of(Message.system("base")), session.messages()); assertFalse(error.getMessage().contains("fixture-secret"));
        if (!mode.equals("only_empty")) {
            assertTrue(error.getMessage().contains("index=0")); assertTrue(error.getMessage().contains("frame=2"));
            assertTrue(error.getMessage().contains(switch (mode) {
                case "conflict" -> "ID conflict";
                case "oversized" -> "exceeds 1024";
                default -> "only whitespace";
            }));
        }
    }

    @Test void toolIndexZeroCanHaveDifferentIdsAcrossSeparateResponses() {
        handler = exchange -> {
            start(exchange); send(exchange, event(call(0, "call-" + requests.get(), "read_file", "{}"), null));
            send(exchange, event(json.createObjectNode(), "tool_calls") + "data: [DONE]\n\n"); exchange.close();
        };
        var client = client();
        assertEquals("call-1", client.generateStream(request(), part -> { }).toolCalls().get(0).id());
        assertEquals("call-2", client.generateStream(request(), part -> { }).toolCalls().get(0).id());
    }

    @ParameterizedTest @ValueSource(strings={"missing_done", "missing_finish", "bad_arguments", "bad_second_arguments"})
    void incompleteToolStreamsNeverReachToolExecution(String mode) {
        handler = exchange -> {
            start(exchange);
            send(exchange, event(call(0, "probe-call", "probe", mode.equals("bad_arguments") ? "{\"x\":" : "{}"), null));
            if (mode.equals("bad_second_arguments")) send(exchange, event(call(1, "probe-call-2", "probe", "{\"x\":"), null));
            if (!mode.equals("missing_finish")) send(exchange, event(json.createObjectNode(), "tool_calls"));
            if (!mode.equals("missing_done")) send(exchange, "data: [DONE]\n\n"); exchange.close();
        };
        var executions = new AtomicInteger();
        Tool probe = new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("probe", "probe", json.createObjectNode().put("type", "object")); }
            public ToolAccess access() { return ToolAccess.READ_ONLY; }
            public void validateArguments(JsonNode arguments, ToolContext context) { }
            public String execute(JsonNode arguments, ToolContext context) { executions.incrementAndGet(); return "ok"; }
        };
        var session = new Session("base");
        var error = assertThrows(LlmException.class, () -> new AgentLoop(client(), new ToolRegistry().register(probe))
                .run(session, "run", new ToolContext(WorkspaceBinding.none()), part -> { }));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind()); assertEquals(0, executions.get()); assertEquals(1, session.messages().size());
    }

    @Test void wholeStreamTimeoutStopsLateCallbacks() throws Exception {
        handler = exchange -> {
            start(exchange); send(exchange, text("partial"));
            try { release.await(3, TimeUnit.SECONDS); send(exchange, text("late") + done()); }
            catch (InterruptedException ignored) { } catch (IOException disconnected) { }
            finally { exchange.close(); }
        };
        var parts = new CopyOnWriteArrayList<String>();
        var error = assertThrows(LlmException.class, () -> client(Duration.ofMillis(400), new StreamingSettings()).generateStream(request(), parts::add));
        assertEquals(LlmException.Kind.TIMEOUT, error.kind());
        int count = parts.size(); release.countDown(); Thread.sleep(100); assertEquals(count, parts.size()); assertFalse(parts.contains("late"));
    }

    @Test void interruptionClosesStreamAndRetainsInterruptFlag() throws Exception {
        var first = new CountDownLatch(1);
        handler = exchange -> {
            start(exchange); send(exchange, text("partial"));
            try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            exchange.close();
        };
        var failure = new AtomicReference<LlmException>(); var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try { client().generateStream(request(), part -> first.countDown()); }
            catch (LlmException error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
        }); caller.start();
        try {
            assertTrue(first.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive()); assertEquals(LlmException.Kind.INTERRUPTED, failure.get().kind()); assertTrue(interrupted.get());
        } finally { caller.interrupt(); release.countDown(); caller.join(2000); }
    }

    @Test void jsonFallbackUsesOneRequestAndNonStreamingSwitchPreservesOldProtocol() {
        handler = exchange -> {
            byte[] body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完整回答\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        };
        var parts = new java.util.ArrayList<String>(); assertEquals("完整回答", client().generateStream(request(), parts::add).text());
        assertTrue(request.get().path("stream").asBoolean()); assertEquals(List.of("完整回答"), parts); assertEquals(1, requests.get());
        parts.clear(); client(Duration.ofSeconds(3), new StreamingSettings(false, true)).generateStream(request(), parts::add);
        assertFalse(request.get().path("stream").asBoolean()); assertFalse(request.get().has("stream_options")); assertEquals(List.of("完整回答"), parts);
    }

    @Test void usageOptionCanBeDisabledAndClosingRequestStillDisablesTools() {
        handler = exchange -> { start(exchange); send(exchange, text("ok") + done()); exchange.close(); };
        var closing = new LlmRequest(request().messages(), List.of(new ReadFileTool().definition()), false);
        var result = client(Duration.ofSeconds(3), new StreamingSettings(true, false)).generateStream(closing, part -> { });
        assertNull(result.usage()); assertFalse(request.get().has("stream_options"));
        assertEquals("none", request.get().path("tool_choice").asText());
    }

    @Test void httpErrorsDoNotEmitBodiesAndRemainClassified() {
        handler = exchange -> {
            byte[] bytes = "fixture-secret-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        };
        var error = assertThrows(LlmException.class, () -> client().generateStream(request(), part -> fail("No callback for HTTP errors")));
        assertEquals(LlmException.Kind.HTTP, error.kind()); assertEquals(429, error.statusCode()); assertFalse(error.getMessage().contains("fixture-secret"));
    }

    @ParameterizedTest @ValueSource(strings={"data: not-json\n\n", "data: {\"error\":{\"message\":\"fixture-secret\"}}\n\n", "data: [DONE]\n\n"})
    void malformedStreamsReturnSanitizedErrors(String data) {
        handler = exchange -> { start(exchange); send(exchange, data); exchange.close(); };
        var error = assertThrows(LlmException.class, () -> client().generateStream(request(), part -> { }));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind()); assertFalse(error.getMessage().contains("fixture-secret"));
    }

    @Test void contentFilterIsNotAnEmptySuccessfulAnswer() {
        handler = exchange -> { start(exchange); send(exchange, event(json.createObjectNode(), "content_filter")); exchange.close(); };
        var error = assertThrows(LlmException.class, () -> client().generateStream(request(), part -> { }));
        assertEquals(LlmException.Kind.MODEL_REJECTION, error.kind());
    }

    @Test void doneCompletesWithoutWaitingForTheServerToCloseItsSocket() throws Exception {
        handler = exchange -> {
            start(exchange); send(exchange, text("finished") + done());
            try { release.await(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            exchange.close();
        };
        var result = threads.submit(() -> client().generateStream(request(), part -> { }));
        assertEquals("finished", result.get(2, TimeUnit.SECONDS).text()); assertEquals(1, release.getCount()); release.countDown();
    }

    @Test void reasoningAndRoleMetadataAreNotDisplayedAsAnswerText() {
        handler = exchange -> {
            start(exchange); send(exchange, event(json.createObjectNode().put("role", "assistant").put("reasoning_content", "private-thinking"), null));
            send(exchange, text("public-answer") + done()); exchange.close();
        };
        var display = new StringBuilder(); var result = client().generateStream(request(), display::append);
        assertEquals("public-answer", display.toString()); assertEquals("public-answer", result.text());
    }
}
