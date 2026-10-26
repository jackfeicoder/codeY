package io.github.jackfeicoder.codey.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.config.AuthMode;
import io.github.jackfeicoder.codey.config.LlmConfig;
import io.github.jackfeicoder.codey.config.StreamingSettings;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.tool.ToolCall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Chat Completions 适配器：支持完整 JSON 与 SSE，实例绑定不可变模型和流式设置。
 * 流程：消息转 JSON → 发送 POST → 检查状态 → 读取/聚合协议 → 完整 LlmResponse。
 * 不按厂商写分支，也不执行工具；兼容性边界由请求/响应协议决定。
 */
public final class ChatCompletionsClient implements LlmClient {
    /** 当前实例选中的模型地址、认证、模型 ID 与超时。 */
    private final LlmConfig config;
    /** 网络传输组件；默认多个模型实例复用同一个客户端与连接池。 */
    private final HttpClient httpClient;
    /** JSON 编解码器，初始化后不再改变配置，可供重复请求使用。 */
    private final ObjectMapper mapper;
    /** 实际请求地址，由基础地址追加 /chat/completions 得到。 */
    private final URI endpoint;
    /** 全局传输兼容开关，generate 始终非流式，generateStream 根据此配置选择。 */
    private final StreamingSettings streaming;

    /** 根据一套模型配置创建适配器，复用默认的 HTTP 客户端。 */
    public ChatCompletionsClient(LlmConfig config) {
        this(config, defaultHttpClient(), new StreamingSettings());
    }

    /** 注入 HttpClient 便于复用连接及测试，默认客户端不跟随重定向。 */
    public ChatCompletionsClient(LlmConfig config, HttpClient httpClient) {
        this(config, httpClient, new StreamingSettings());
    }

    public ChatCompletionsClient(LlmConfig config, StreamingSettings streaming) {
        this(config, defaultHttpClient(), streaming);
    }

    /** 注入传输与兼容设置，测试无需真实厂商或凭证。 */
    public ChatCompletionsClient(LlmConfig config, HttpClient httpClient, StreamingSettings streaming) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.streaming = Objects.requireNonNull(streaming, "streaming");
        // 拒绝 JSON 尾部多余内容及重复字段，避免错误响应被静默接受。
        this.mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.endpoint = URI.create(config.baseUrl().toString().replaceAll("/+$", "") + "/chat/completions");
    }

    /** 获取懒加载的共享网络客户端，避免每次切换 Profile 都创建连接池。 */
    private static HttpClient defaultHttpClient() {
        return SharedHttpClient.INSTANCE;
    }

    /** 静态内部类在首次使用时初始化，实现线程安全的延迟创建。 */
    private static final class SharedHttpClient {
        /** 默认连接建立超时 30 秒；整次请求另受模型配置的超时约束。 */
        private static final HttpClient INSTANCE = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 执行一次非流式调用，返回完整回答与元数据。
     * sendAsync 提供可取消的网络任务，随后限时等待，因此此方法对调用方仍是同步的。
     */
    @Override
    public LlmResponse generate(LlmRequest request) {
        Objects.requireNonNull(request, "request");
        String body = encode(request, false); // 完整正文用于请求，不输出到控制台。
        HttpRequest httpRequest; // 构建完成的 HTTP 请求，包含头、地址、超时与 JSON 请求体。
        try {
            // builder 组装请求；encode 负责把 Java 消息映射成协议字段。
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(config.timeout())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (config.authMode() == AuthMode.BEARER) {
                builder.header("Authorization", "Bearer " + config.apiKey());
            }
            httpRequest = builder.build();
        } catch (IllegalArgumentException invalid) {
            throw new LlmException(LlmException.Kind.INVALID_REQUEST, "Invalid HTTP request configuration.");
        }

        CompletableFuture<HttpResponse<String>> future; // 异步网络任务，可在超时或中断时取消。
        try {
            future = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalid) {
            throw new LlmException(LlmException.Kind.INVALID_REQUEST, "Invalid HTTP request configuration.");
        }
        HttpResponse<String> response; // 完整 HTTP 响应，BodyHandler 已将正文按 UTF-8 解码。
        try {
            // 等待完整响应体，保证慢响应体也受总超时约束。
            response = future.get(config.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new LlmException(LlmException.Kind.TIMEOUT, "LLM request exceeded its timeout.");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            // InterruptedException 会清除标志，恢复它让上层知道任务已被中断。
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.INTERRUPTED, "LLM request was interrupted.");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof HttpTimeoutException) {
                throw new LlmException(LlmException.Kind.TIMEOUT, "LLM request exceeded its timeout.");
            }
            throw new LlmException(LlmException.Kind.NETWORK, "LLM request failed due to a network error.");
        }
        int status = response.statusCode(); // 非 2xx 按 HTTP 错误处理，异常说明仍保持脱敏。
        if (status < 200 || status >= 300) {
            throw new LlmException(LlmException.Kind.HTTP, status, "LLM request failed with HTTP status " + status + ".");
        }
        return decode(response.body());
    }

    /** SSE 正文即时回调；总超时覆盖响应头和整个流，超时/取消后禁止晚到片段继续打印。 */
    @Override
    public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) {
        Objects.requireNonNull(request, "request"); Objects.requireNonNull(onText, "onText");
        if (!streaming.enabled()) return LlmClient.super.generateStream(request, onText);
        String body = encode(request, true);
        HttpRequest httpRequest;
        try {
            var builder = HttpRequest.newBuilder(endpoint).timeout(config.timeout())
                    .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (config.authMode() == AuthMode.BEARER) builder.header("Authorization", "Bearer " + config.apiKey());
            httpRequest = builder.build();
        } catch (IllegalArgumentException invalid) {
            throw new LlmException(LlmException.Kind.INVALID_REQUEST, "Invalid HTTP request configuration.");
        }
        long deadline = System.nanoTime() + config.timeout().toNanos();
        var headers = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        var reader = Executors.newSingleThreadExecutor(action -> {
            var thread = new Thread(action, "codey-llm-stream"); thread.setDaemon(true); return thread;
        });
        Object deliveryLock = new Object();
        boolean[] active = {true}; // 回调与关闭使用同一锁，避免错误提示后又插入模型片段。
        Future<LlmResponse> completion = null;
        InputStream input = null;
        try {
            var response = await(headers, deadline);
            input = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new LlmException(LlmException.Kind.HTTP, response.statusCode(),
                        "LLM request failed with HTTP status " + response.statusCode() + ".");
            InputStream stream = input;
            completion = reader.submit(() -> readStream(stream, part -> {
                synchronized (deliveryLock) { if (active[0]) onText.accept(part); }
            }));
            return await(completion, deadline);
        } finally {
            synchronized (deliveryLock) { active[0] = false; }
            if (completion != null) completion.cancel(true);
            close(input);
            // 头部等待期间超时/中断时也接管晚到的响应体，避免遗漏关闭。
            headers.whenComplete((response, failed) -> { if (response != null) close(response.body()); });
            headers.cancel(true);
            reader.shutdownNow();
        }
    }

    private LlmResponse readStream(InputStream input, Consumer<String> onText) {
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)))) {
            // 部分兼容平台忽略 stream=true 而返回完整 JSON，原地解析，绝不额外重发请求。
            reader.mark(4096);
            int first, skipped = 0;
            do {
                first = reader.read();
                if (++skipped >= 4096) throw invalidResponse("LLM response has an oversized whitespace prefix.");
            } while (first >= 0 && (Character.isWhitespace(first) || first == 0xfeff));
            reader.reset();
            if (first == '{') {
                StringBuilder json = new StringBuilder(); char[] buffer = new char[4096]; int count;
                while ((count = reader.read(buffer)) >= 0) {
                    if (json.length() + count > ChatStreamDecoder.MAX_RESPONSE_CHARS) throw invalidResponse("LLM response exceeds the size limit.");
                    json.append(buffer, 0, count);
                }
                if (!json.isEmpty() && json.charAt(0) == '\ufeff') json.deleteCharAt(0);
                var response = decode(json.toString());
                if (!response.text().isEmpty()) onText.accept(response.text());
                return response;
            }
            return decode(new ChatStreamDecoder(mapper, onText).read(reader).toString());
        } catch (CharacterCodingException invalid) {
            throw invalidResponse("LLM stream is not valid UTF-8.");
        } catch (IOException failed) {
            throw new LlmException(LlmException.Kind.NETWORK, "LLM stream failed due to a network error.");
        }
    }

    /** 同一个 deadline 约束响应头与分片读取；读线程异常只传程序控制的说明。 */
    private static <T> T await(Future<T> future, long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException timeout) {
            future.cancel(true);
            throw new LlmException(LlmException.Kind.TIMEOUT, "LLM request exceeded its timeout.");
        } catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.INTERRUPTED, "LLM request was interrupted.");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof LlmException expected) throw expected;
            if (failed.getCause() instanceof HttpTimeoutException)
                throw new LlmException(LlmException.Kind.TIMEOUT, "LLM request exceeded its timeout.");
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new LlmException(LlmException.Kind.NETWORK, "LLM request failed due to a network error.");
        }
    }
    private static void close(InputStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { }
    }

    /** 将请求消息显式映射为 Chat Completions JSON，避免默认字段名与接口不一致。 */
    private String encode(LlmRequest request, boolean stream) {
        ObjectNode root = mapper.createObjectNode(); // 请求顶层对象：model、stream、messages、可选 tools。
        root.put("model", config.model());
        root.put("stream", stream);
        if (request.maxOutputTokens() != null) root.put("max_tokens", request.maxOutputTokens());
        if (stream && streaming.includeUsage()) root.putObject("stream_options").put("include_usage", true);
        ArrayNode messages = root.putArray("messages"); // 保存按历史顺序排列的协议消息。
        for (Message message : request.messages()) {
            ObjectNode encoded = messages.addObject(); // 当前 Java Message 对应的 JSON 对象。
            encoded.put("role", message.role().wireValue());
            if (message.content() == null) {
                encoded.putNull("content");
            } else {
                encoded.put("content", message.content());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = encoded.putArray("tool_calls"); // assistant 发起的工具调用列表。
                for (ToolCall call : message.toolCalls()) {
                    // call 的 arguments 仍以字符串发送，但先验证其内容是完整 JSON 对象。
                    validateArguments(call.arguments(), LlmException.Kind.INVALID_REQUEST);
                    ObjectNode encodedCall = calls.addObject(); // 工具调用外层保存 id 与 type。
                    encodedCall.put("id", call.id());
                    encodedCall.put("type", "function");
                    ObjectNode function = encodedCall.putObject("function"); // 函数名称与 JSON 参数字符串。
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
            if (message.toolCallId() != null) {
                // Java 的 toolCallId 显式转换为协议要求的 tool_call_id。
                encoded.put("tool_call_id", message.toolCallId());
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = root.putArray("tools"); // 工具说明单独编码，不拼接进 system 提示词。
            for (var definition : request.tools()) {
                ObjectNode encoded = tools.addObject().put("type", "function");
                ObjectNode function = encoded.putObject("function");
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.set("parameters", definition.parameters());
            }
            if (!request.toolCallsAllowed()) root.put("tool_choice", "none");
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException invalid) {
            throw new LlmException(LlmException.Kind.INVALID_REQUEST, "Failed to encode the LLM request.");
        }
    }

    /** 解析首个 completion：结构校验 → 文本/工具请求 → 结束原因 → 可空 Token 用量。 */
    private LlmResponse decode(String body) {
        try {
            JsonNode root = mapper.readTree(body); // HTTP 正文对应的 JSON 树，未知额外字段可保留而不使用。
            if (root == null || !root.isObject() || root.hasNonNull("error")) {
                throw invalidResponse("LLM returned an invalid or error response payload.");
            }
            JsonNode choices = root.get("choices"); // 模型候选结果数组；当前请求采用默认单候选。
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw invalidResponse("LLM response is missing completion choices.");
            }
            JsonNode choice = choices.get(0); // 当前仅消费第一条完整候选。
            String finishReason = requiredText(choice, "finish_reason"); // 正常结束、工具调用或截断等原因。
            JsonNode message = choice.get("message"); // 模型的 assistant 消息，可能含文本或工具调用。
            if (message == null || !message.isObject() || !"assistant".equals(requiredText(message, "role"))) {
                throw invalidResponse("LLM response must contain an assistant message.");
            }
            JsonNode rejection = message.get("refusal"); // 部分服务提供的拒绝结果字段，与格式错误区分。
            if (rejection != null && !rejection.isNull() && !rejection.isTextual()) {
                throw invalidResponse("LLM refusal must be text when supplied.");
            }
            if ("content_filter".equals(finishReason)
                    || (rejection != null && rejection.isTextual() && !rejection.textValue().isBlank())) {
                throw new LlmException(LlmException.Kind.MODEL_REJECTION, "Model did not return a usable answer.");
            }
            JsonNode contentNode = message.get("content"); // 文本 JSON 节点；纯工具调用时允许为 null。
            if (contentNode != null && !contentNode.isNull() && !contentNode.isTextual()) {
                throw invalidResponse("Only text message content is supported in this client.");
            }
            String content = contentNode == null || contentNode.isNull() ? null : contentNode.textValue();
            List<ToolCall> calls = new ArrayList<>(); // 保存已验证且尚未执行的工具调用请求。
            JsonNode callNodes = message.get("tool_calls"); // 服务返回的调用数组，不是工具执行结果。
            if (callNodes != null && !callNodes.isNull()) {
                if (!callNodes.isArray()) {
                    throw invalidResponse("LLM tool_calls must be an array.");
                }
                for (JsonNode call : callNodes) {
                    // 将嵌套的 function 结构还原成内部的扁平 ToolCall。
                    if (!"function".equals(requiredText(call, "type"))) {
                        throw invalidResponse("Only function tool calls are supported.");
                    }
                    JsonNode function = call.get("function"); // 函数名称与参数的协议对象。
                    String arguments = requiredText(function, "arguments"); // 模型输出的原始 JSON 参数文本。
                    validateArguments(arguments, LlmException.Kind.INVALID_RESPONSE);
                    calls.add(new ToolCall(requiredText(call, "id"), requiredText(function, "name"), arguments));
                }
            }
            if ("tool_calls".equals(finishReason) && calls.isEmpty()) {
                throw invalidResponse("LLM ended with tool_calls but supplied no tool calls.");
            }
            if ("function_call".equals(finishReason) || message.hasNonNull("function_call")) {
                throw invalidResponse("Legacy function_call responses are not supported; use tool_calls.");
            }
            JsonNode usage = root.get("usage"); // 可选的服务商 Token 统计。
            LlmResponse.TokenUsage tokenUsage = null; // 缺失表示未知，不自行填入 0 或推算。
            if (usage != null && !usage.isNull()) {
                if (!usage.isObject()) {
                    throw invalidResponse("LLM usage must be an object when supplied.");
                }
                tokenUsage = new LlmResponse.TokenUsage(tokenCount(usage, "prompt_tokens"),
                        tokenCount(usage, "completion_tokens"), tokenCount(usage, "total_tokens"));
            }
            return new LlmResponse(Message.assistant(content, calls), finishReason, tokenUsage);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            // 不传播 Jackson 的原始错误，它可能包含响应正文。
            throw invalidResponse("LLM response is malformed or violates the message contract.");
        }
    }

    /** arguments 必须是完整 JSON 对象；kind 区分发送前参数错误与模型响应错误。 */
    private void validateArguments(String arguments, LlmException.Kind kind) {
        try {
            JsonNode parsed = mapper.readTree(arguments); // 验证对象语法，具体工具字段由执行层校验。
            if (parsed == null || !parsed.isObject()) {
                throw new LlmException(kind, "Tool arguments must be a complete JSON object.");
            }
        } catch (JsonProcessingException invalid) {
            throw new LlmException(kind, "Tool arguments must be a complete JSON object.");
        }
    }

    /** 从 JSON 对象读取非空文本字段，缺失时返回已脱敏的协议错误。 */
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field); // 安全访问可能缺失的子节点。
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidResponse("LLM response is missing a required text field: " + field + ".");
        }
        return value.textValue();
    }

    /** 提取非负整数 Token 数；字段缺失时保持 null，以区别已知的 0。 */
    private static Long tokenCount(JsonNode usage, String field) {
        JsonNode count = usage.get(field); // 需要读取的 prompt/completion/total 计数字段。
        if (count == null || count.isNull()) {
            return null;
        }
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0) {
            throw invalidResponse("LLM usage contains an invalid token count.");
        }
        return count.longValue();
    }

    /** 统一创建响应格式错误；message 是程序控制的说明，不含原始正文。 */
    private static LlmException invalidResponse(String message) {
        return new LlmException(LlmException.Kind.INVALID_RESPONSE, message);
    }
}
