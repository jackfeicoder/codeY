package io.github.jackfeicoder.codey.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.jackfeicoder.codey.config.AuthMode;
import io.github.jackfeicoder.codey.config.LlmConfig;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageRole;
import io.github.jackfeicoder.codey.tool.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 非流式 HTTP 集成测试：启动本地服务 → 构造假配置 → 调用客户端 → 检查结果 → 关闭资源。 */
class ChatCompletionsClientTest {
    /** 构造和检查测试 JSON 的解析器。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 跨线程保存实际收到的请求 JSON，供主测试线程断言。 */
    private final AtomicReference<JsonNode> capturedBody = new AtomicReference<>();
    /** 记录认证头，验证 Bearer 或 NONE 行为。 */
    private final AtomicReference<String> capturedAuth = new AtomicReference<>();
    /** 记录最终请求路径，验证基础地址前缀没有丢失。 */
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    /** 实际 HTTP 请求次数，用于确认未重试或本地提前失败。 */
    private final AtomicInteger requests = new AtomicInteger();
    /** 仅监听本机临时端口的测试服务。 */
    private HttpServer server;
    /** 处理测试服务请求的线程池，测试结束统一关闭。 */
    private ExecutorService executor;
    /** 当前临时服务的基础地址，不指向真实模型平台。 */
    private URI baseUrl;
    /** 当前测试自定义的响应行为，volatile 保证服务线程可见。 */
    private volatile HttpHandler responder;

    /** 为每个测试启动临时本地端口，记录请求并提供可替换的响应处理器。 */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        responder = exchange -> respond(exchange, 200, responseTree().toString());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(mapper.readTree(exchange.getRequestBody()));
            responder.handle(exchange);
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /** 关闭测试 HTTP 服务和线程池，避免测试间残留任务。 */
    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** 验证地址前缀、认证、模型、消息编码和中文回答及 Token 用量。 */
    @Test
    void sendsSelectedConfigAndPreservesUnicodeResponseAndUsage() {
        // 本次模型调用返回的完整响应。
        LlmResponse result = client(baseUrl.resolve("/compatible-mode/v1/"), AuthMode.BEARER,
                Duration.ofSeconds(5)).generate(request());
        assertEquals("/compatible-mode/v1/chat/completions", capturedPath.get());
        assertEquals("Bearer fixture-key", capturedAuth.get());
        assertEquals("fixture-model", capturedBody.get().get("model").textValue());
        assertFalse(capturedBody.get().get("stream").booleanValue());
        assertEquals("system", capturedBody.get().get("messages").get(0).get("role").textValue());
        assertEquals("解释 Java", capturedBody.get().get("messages").get(1).get("content").textValue());
        assertEquals("你好，CodeY", result.text());
        assertEquals(MessageRole.ASSISTANT, result.message().role());
        assertEquals("stop", result.finishReason());
        assertEquals(5L, result.usage().promptTokens());
        assertEquals(7L, result.usage().completionTokens());
        assertEquals(12L, result.usage().totalTokens());
    }

    /** 验证 NONE 模式不会发送 Authorization 请求头。 */
    @Test
    void noneAuthOmitsAuthorizationHeader() {
        client(baseUrl, AuthMode.NONE, Duration.ofSeconds(5)).generate(request());
        assertNull(capturedAuth.get());
        assertEquals("/chat/completions", capturedPath.get());
    }

    /** 验证 Java 字段映射为 tool_calls、function 和 tool_call_id 协议结构。 */
    @Test
    void explicitlyEncodesToolRequestAndResultMessages() {
        // 当前工具调用样例，调用 ID 与结果消息配对。
        ToolCall call = new ToolCall("call_read", "read_file", "{\"path\":\"Main.java\"}");
        client().generate(new LlmRequest(List.of(Message.user("解释 Main.java"),
                Message.assistantToolCalls(List.of(call)), Message.tool(call.id(), "文件内容"))));
        // 实际请求中的 JSON 消息数组。
        JsonNode messages = capturedBody.get().get("messages");
        assertTrue(messages.get(1).get("content").isNull());
        // 实际发送的工具请求 JSON，用于检查嵌套字段。
        JsonNode encodedCall = messages.get(1).get("tool_calls").get(0);
        assertEquals("function", encodedCall.get("type").textValue());
        assertEquals(call.arguments(), encodedCall.get("function").get("arguments").textValue());
        assertEquals("tool", messages.get(2).get("role").textValue());
        assertEquals(call.id(), messages.get(2).get("tool_call_id").textValue());
        assertFalse(messages.get(2).has("toolCallId"));
    }

    /** 验证多个工具请求按顺序解析，并且客户端只调用模型一次。 */
    @Test
    void parsesMultipleToolCallsWithoutExecutingThem() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        // 响应树中的 assistant 消息节点，用于添加工具调用。
        ObjectNode message = responseMessage(root);
        message.putNull("content");
        // 响应中第一个工具调用的 JSON 节点。
        ObjectNode first = message.putArray("tool_calls").addObject();
        first.put("id", "call_read").put("type", "function");
        first.putObject("function").put("name", "read_file").put("arguments", "{\"path\":\"Main.java\"}");
        // 响应中第二个工具调用的 JSON 节点。
        ObjectNode second = ((com.fasterxml.jackson.databind.node.ArrayNode) message.get("tool_calls")).addObject();
        second.put("id", "call_list").put("type", "function");
        second.putObject("function").put("name", "list_files").put("arguments", "{}");
        ((ObjectNode) root.get("choices").get(0)).put("finish_reason", "tool_calls");
        responder = exchange -> respond(exchange, 200, root.toString());
        // 本次模型调用返回的完整响应。
        LlmResponse result = client().generate(request());
        assertEquals("", result.text());
        assertNull(result.message().content());
        assertEquals(List.of("call_read", "call_list"), result.toolCalls().stream().map(ToolCall::id).toList());
        assertEquals("tool_calls", result.finishReason());
        assertEquals(1, requests.get());
    }

    /** 验证缺失统计为 null，部分字段缺失不会被填为零。 */
    @Test
    void absentUsageIsUnknownAndPartialUsageDoesNotBecomeZero() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        root.remove("usage");
        responder = exchange -> respond(exchange, 200, root.toString());
        assertNull(client().generate(request()).usage());
        root.putObject("usage").put("prompt_tokens", 3);
        // 本次模型调用返回的完整响应。
        LlmResponse result = client().generate(request());
        assertEquals(3L, result.usage().promptTokens());
        assertNull(result.usage().completionTokens());
        assertNull(result.usage().totalTokens());
    }

    /** 验证长度限制结束被保留并可识别为截断。 */
    @Test
    void exposesLengthFinishReasonWithoutClaimingCompleteAnswer() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        ((ObjectNode) root.get("choices").get(0)).put("finish_reason", "length");
        responder = exchange -> respond(exchange, 200, root.toString());
        assertTrue(client().generate(request()).isTruncated());
    }

    /** 验证 HTTP 错误保留状态与重试分类，不回显服务响应，也不自动重试。 */
    @ParameterizedTest
    @ValueSource(ints = {401, 403, 408, 429, 500, 503})
    void httpErrorsPreserveStatusWithoutEchoingRemoteBody(int status) {
        responder = exchange -> respond(exchange, status, "fixture-key private-provider-detail");
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class, () -> client().generate(request()));
        assertEquals(LlmException.Kind.HTTP, error.kind());
        assertEquals(status, error.statusCode());
        assertEquals(status == 408 || status == 429 || status >= 500, error.isRetryable());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("fixture-key"));
        assertEquals(1, requests.get(), "No automatic retries in this milestone");
    }

    /** 验证默认客户端直接报告重定向，避免凭证转发到新地址。 */
    @Test
    void defaultClientDoesNotFollowRedirects() {
        responder = exchange -> {
            exchange.getResponseHeaders().set("Location", baseUrl.resolve("/another-endpoint").toString());
            respond(exchange, 302, "redirect");
        };
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class, () -> client().generate(request()));
        assertEquals(302, error.statusCode());
        assertEquals(1, requests.get());
    }

    /** 验证非法、重复、残缺或多段 JSON 均分类报错且不泄露正文。 */
    @ParameterizedTest
    @ValueSource(strings = {"not-json fixture-key", "{", "{}", "{\"choices\":[]}",
            "{\"error\":{\"message\":\"fixture-key\"}}", "{\"choices\":[{}]}",
            "{\"choices\":[],\"choices\":[]}", "{} {}"})
    void rejectsMalformedOrIncompleteResponsesWithoutLeakingBody(String body) {
        responder = exchange -> respond(exchange, 200, body);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class, () -> client().generate(request()));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind());
        assertFalse(error.isRetryable());
        assertNull(error.getCause());
        assertFalse(error.toString().contains("fixture-key"));
    }

    /** 验证历史工具参数无效时在发请求之前失败。 */
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "{} {}", "{\"path\":", "null"})
    void rejectsMalformedOutgoingArgumentsBeforeNetworkCall(String arguments) {
        // 当前工具调用样例，调用 ID 与结果消息配对。
        ToolCall call = new ToolCall("call_1", "read_file", arguments);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class,
                () -> client().generate(new LlmRequest(List.of(Message.assistantToolCalls(List.of(call))))));
        assertEquals(LlmException.Kind.INVALID_REQUEST, error.kind());
        assertEquals(0, requests.get());
    }

    /** 验证模型返回的工具参数必须是完整 JSON 对象。 */
    @Test
    void rejectsIncompleteIncomingToolArguments() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        // 当前工具调用样例，调用 ID 与结果消息配对。
        ObjectNode call = responseMessage(root).putArray("tool_calls").addObject();
        call.put("id", "call_1").put("type", "function");
        call.putObject("function").put("name", "read_file").put("arguments", "{\"path\":");
        responder = exchange -> respond(exchange, 200, root.toString());
        assertEquals(LlmException.Kind.INVALID_RESPONSE,
                assertThrows(LlmException.class, () -> client().generate(request())).kind());
    }

    /** 验证响应角色和 Token 数满足契约。 */
    @Test
    void rejectsNonAssistantRoleAndInvalidTokenCounts() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        responseMessage(root).put("role", "user");
        responder = exchange -> respond(exchange, 200, root.toString());
        assertEquals(LlmException.Kind.INVALID_RESPONSE,
                assertThrows(LlmException.class, () -> client().generate(request())).kind());
        responseMessage(root).put("role", "assistant");
        ((ObjectNode) root.get("usage")).put("total_tokens", -1);
        assertEquals(LlmException.Kind.INVALID_RESPONSE,
                assertThrows(LlmException.class, () -> client().generate(request())).kind());
    }

    /** 验证服务明确拒绝结果时使用独立错误类别。 */
    @Test
    void classifiesModelRejectionSeparatelyFromMalformedJson() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        responseMessage(root).putNull("content").put("refusal", "provider text");
        responder = exchange -> respond(exchange, 200, root.toString());
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class, () -> client().generate(request()));
        assertEquals(LlmException.Kind.MODEL_REJECTION, error.kind());
        assertFalse(error.isRetryable());
    }

    /** 验证空的拒绝字段不影响正常回答。 */
    @Test
    void emptyRejectionFieldDoesNotHideValidAnswer() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        responseMessage(root).put("refusal", "");
        responder = exchange -> respond(exchange, 200, root.toString());
        assertEquals("你好，CodeY", client().generate(request()).text());
    }

    /** 验证工具结束原因具有实际调用，并明确拒绝旧式 function_call。 */
    @Test
    void toolFinishReasonRequiresActualCallsAndLegacyCallsAreExplicitlyRejected() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = responseTree();
        // 响应的首个候选结果，可修改结束原因构造场景。
        ObjectNode choice = (ObjectNode) root.get("choices").get(0);
        choice.put("finish_reason", "tool_calls");
        responder = exchange -> respond(exchange, 200, root.toString());
        assertEquals(LlmException.Kind.INVALID_RESPONSE,
                assertThrows(LlmException.class, () -> client().generate(request())).kind());
        choice.put("finish_reason", "function_call");
        assertEquals(LlmException.Kind.INVALID_RESPONSE,
                assertThrows(LlmException.class, () -> client().generate(request())).kind());
    }

    /** 发送响应头后故意延迟正文，验证整段正文仍受等待超时限制。 */
    @Test
    void timeoutAlsoCoversSlowResponseBodyAfterHeaders() {
        responder = exchange -> {
            // 响应正文的 UTF-8 字节，用于设置长度和发送数据。
            byte[] bytes = responseTree().toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes, 0, 1);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(3000);
                exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            // 预期抛出的异常，用于核对类别、字段定位或脱敏。
            LlmException error = assertThrows(LlmException.class,
                    () -> client(baseUrl, AuthMode.BEARER, Duration.ofMillis(250)).generate(request()));
            assertEquals(LlmException.Kind.TIMEOUT, error.kind());
            assertTrue(error.isRetryable());
        });
    }

    /** 中断等待线程，验证取消请求并恢复线程中断标志。 */
    @Test
    void interruptionCancelsWaitingRequestAndRestoresThreadFlag() throws Exception {
        // 同步测试线程与服务线程，确认请求到达后再中断调用。
        CountDownLatch received = new CountDownLatch(1);
        responder = exchange -> {
            received.countDown();
            try {
                Thread.sleep(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        };
        // 保存正在等待模型结果的线程，供测试主动中断。
        AtomicReference<Thread> caller = new AtomicReference<>();
        // 发起模型请求的独立测试线程池，与服务线程池分开。
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            // 后台调用的返回任务，用于检查中断后线程标志是否恢复。
            Future<Boolean> result = callers.submit(() -> {
                caller.set(Thread.currentThread());
                // 预期抛出的异常，用于核对类别、字段定位或脱敏。
                LlmException error = assertThrows(LlmException.class, () -> client().generate(request()));
                assertEquals(LlmException.Kind.INTERRUPTED, error.kind());
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(received.await(2, TimeUnit.SECONDS));
            caller.get().interrupt();
            assertTrue(result.get(2, TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }
    }

    /** 先停止本地服务，再验证连接失败的网络错误类别。 */
    @Test
    void connectionFailureIsNetworkError() {
        server.stop(0);
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class, () -> client().generate(request()));
        assertEquals(LlmException.Kind.NETWORK, error.kind());
    }

    /** 验证非法认证头在本地失败，错误说明不泄露密钥。 */
    @Test
    void invalidHeaderDoesNotExposeCredential() {
        // 本测试加载或构造的模型配置，用于检查字段与认证。
        LlmConfig config = new LlmConfig(baseUrl, "fixture-key\ninvalid", "fixture-model", Duration.ofSeconds(5));
        // 预期抛出的异常，用于核对类别、字段定位或脱敏。
        LlmException error = assertThrows(LlmException.class,
                () -> new ChatCompletionsClient(config).generate(request()));
        assertEquals(LlmException.Kind.INVALID_REQUEST, error.kind());
        assertFalse(error.toString().contains("fixture-key"));
        assertEquals(0, requests.get());
    }

    /** 验证请求需要消息，并保存不可修改的列表副本。 */
    @Test
    void requestRejectsEmptyHistoryAndCopiesInputList() {
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest(List.of()));
        // 测试对话列表，用于验证顺序与防御性复制。
        List<Message> history = new ArrayList<>(List.of(Message.user("hello")));
        // 已复制消息列表的请求对象，用于检查外部修改隔离。
        LlmRequest request = new LlmRequest(history);
        history.clear();
        assertEquals(1, request.messages().size());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
    }

    /** 创建绑定假配置的客户端；重载允许指定地址、认证和超时。 */
    private ChatCompletionsClient client() {
        return client(baseUrl, AuthMode.BEARER, Duration.ofSeconds(5));
    }

    /** 创建绑定假配置的客户端；重载允许指定地址、认证和超时。 */
    private ChatCompletionsClient client(URI url, AuthMode auth, Duration timeout) {
        return new ChatCompletionsClient(new LlmConfig(url, "fixture-key", "fixture-model", timeout, auth));
    }

    /** 创建一组系统指令与用户输入，作为默认 HTTP 测试请求。 */
    private static LlmRequest request() {
        return new LlmRequest(List.of(Message.system("You are CodeY."), Message.user("解释 Java")));
    }

    /** 构造包含文本、结束原因与统计的标准响应树，可被测试局部修改。 */
    private ObjectNode responseTree() {
        // 可修改的响应 JSON 根节点，用于构造成功或异常样例。
        ObjectNode root = mapper.createObjectNode();
        // 响应的首个候选结果，可修改结束原因构造场景。
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0).put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", "你好，CodeY");
        root.putObject("usage").put("prompt_tokens", 5).put("completion_tokens", 7).put("total_tokens", 12);
        return root;
    }

    /** 定位响应树中的 assistant 消息，便于测试构造异常或工具响应。 */
    private static ObjectNode responseMessage(ObjectNode root) {
        return (ObjectNode) root.get("choices").get(0).get("message");
    }

    /** 按给定状态和 UTF-8 正文发送响应，并关闭输出流。 */
    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        // 响应正文的 UTF-8 字节，用于设置长度和发送数据。
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
