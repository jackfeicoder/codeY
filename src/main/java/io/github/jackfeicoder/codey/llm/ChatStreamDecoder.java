package io.github.jackfeicoder.codey.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.Reader;
import java.util.TreeMap;
import java.util.function.Consumer;

/** 有界 SSE 帧读取与 Chat delta 聚合；只回调正文，完整结果复用非流式协议校验。 */
final class ChatStreamDecoder {
    static final int MAX_RESPONSE_CHARS = 4 * 1024 * 1024;
    static final int MAX_EVENT_CHARS = 128 * 1024;
    private final ObjectMapper mapper;
    private final Consumer<String> onText;
    private final StringBuilder content = new StringBuilder();
    /** 工具调用按 index 合并，允许不同调用交错到达，但不提前创建可执行 ToolCall。 */
    private final TreeMap<Integer, Call> calls = new TreeMap<>();
    private String finishReason;
    private JsonNode usage;
    private boolean sawContent;
    private int readChars;
    private boolean skipLf;
    /** 仅用于定位异常分片的序号，不记录或打印原始响应。 */
    private int frame;

    ChatStreamDecoder(ObjectMapper mapper, Consumer<String> onText) { this.mapper = mapper; this.onText = onText; }

    /** 必须收到明确 [DONE]，并保留 finish_reason；EOF/坏帧都不返回半截工具调用。 */
    ObjectNode read(Reader reader) throws IOException {
        StringBuilder event = new StringBuilder();
        boolean first = true;
        while (true) {
            String line = line(reader);
            if (line == null) {
                if (event.toString().strip().equals("[DONE]")) return finish();
                throw invalid("LLM stream ended before [DONE].");
            }
            if (first && line.startsWith("\ufeff")) line = line.substring(1);
            first = false;
            if (line.isEmpty()) {
                if (!event.isEmpty()) {
                    String data = event.toString(); event.setLength(0);
                    if (data.strip().equals("[DONE]")) return finish();
                    chunk(data);
                }
            } else if (line.equals("data") || line.startsWith("data:")) {
                String data = line.equals("data") ? "" : line.substring(5);
                if (data.startsWith(" ")) data = data.substring(1);
                if (event.length() + data.length() + 1 > MAX_EVENT_CHARS) throw invalid("LLM SSE event exceeds the size limit.");
                if (!event.isEmpty()) event.append('\n');
                event.append(data);
            }
            // 注释/心跳、event/id/retry 等 SSE 元数据不参与模型消息。
        }
    }

    /** 接受 LF/CRLF/CR；逐字符受界，避免 readLine 在遇到巨型帧时先无界分配。 */
    private String line(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            int ch = next(reader);
            if (ch < 0) return line.isEmpty() ? null : line.toString();
            if (skipLf) { skipLf = false; if (ch == '\n') continue; }
            if (ch == '\n') return line.toString();
            if (ch == '\r') {
                skipLf = true;
                return line.toString();
            }
            if (line.length() >= MAX_EVENT_CHARS) throw invalid("LLM SSE line exceeds the size limit.");
            line.append((char) ch);
        }
    }
    private int next(Reader reader) throws IOException {
        int value = reader.read();
        if (value >= 0 && ++readChars > MAX_RESPONSE_CHARS) throw invalid("LLM stream exceeds the response size limit.");
        return value;
    }

    private void chunk(String data) throws IOException {
        frame++;
        JsonNode root;
        try { root = mapper.readTree(data); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw invalid("LLM stream contains malformed JSON."); }
        if (root == null || !root.isObject() || root.hasNonNull("error")) throw invalid("LLM stream returned an invalid or error payload.");
        if (root.hasNonNull("usage")) usage = root.get("usage");
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.size() > 1) throw invalid("LLM stream requires a single completion choice.");
        if (choices.isEmpty()) {
            if (!root.hasNonNull("usage")) throw invalid("Empty streaming choices require usage metadata.");
            return;
        }
        JsonNode choice = choices.get(0);
        if (!choice.path("index").isInt() || choice.path("index").intValue() != 0)
            throw invalid("Unsupported streaming completion index.");
        JsonNode delta = choice.get("delta");
        if (delta == null || !delta.isObject()) throw invalid("LLM stream delta must be an object.");
        if (finishReason != null && (!delta.isEmpty() || choice.hasNonNull("finish_reason"))) throw invalid("LLM sent completion data after finish_reason.");
        if (delta.hasNonNull("role") && !"assistant".equals(text(delta, "role"))) throw invalid("LLM stream role must be assistant.");
        if (delta.hasNonNull("function_call")) throw invalid("Legacy function_call streaming is unsupported.");
        String refusal = text(delta, "refusal");
        if (refusal != null && !refusal.isBlank()) throw rejected();
        String part = text(delta, "content");
        if (part != null) {
            sawContent = true; content.append(part);
            if (!part.isEmpty()) onText.accept(part);
        }
        JsonNode toolCalls = delta.get("tool_calls");
        if (toolCalls != null && !toolCalls.isNull()) {
            if (!toolCalls.isArray() || toolCalls.size() > 16) throw invalid("Invalid streaming tool-call group.");
            for (JsonNode item : toolCalls) addCall(item);
        }
        String reason = text(choice, "finish_reason");
        if (reason != null) {
            if (reason.isBlank()) throw invalid("LLM stream finish_reason is blank.");
            if (reason.equals("content_filter")) throw rejected();
            finishReason = reason;
        }
    }

    private void addCall(JsonNode item) {
        JsonNode index = item.get("index");
        if (index == null || !index.isInt() || index.intValue() < 0 || index.intValue() >= 16)
            throw invalid("LLM stream tool index is outside the supported range.");
        Call call = calls.computeIfAbsent(index.intValue(), ignored -> new Call());
        String id = text(item, "id");
        // 部分兼容网关在后续 delta 中发送空 ID 占位：它等同未携带 ID，不能覆盖已有身份。
        // 非空 ID 必须始终一致；不猜测拼接 ID，也不把冲突 index 自动改成另一个工具。
        if (id != null && !id.isEmpty()) {
            String location = " (index=" + index.intValue() + ", frame=" + frame + ", incoming_id_chars=" + id.length() + ").";
            if (id.length() > 1024) throw invalid("LLM stream tool-call ID exceeds 1024 characters" + location);
            if (id.isBlank()) throw invalid("LLM stream tool-call ID contains only whitespace" + location);
            if (call.id != null && !call.id.equals(id)) throw invalid("LLM stream tool-call ID conflict (index="
                    + index.intValue() + ", frame=" + frame + ", stored_id_chars=" + call.id.length()
                    + ", incoming_id_chars=" + id.length() + "). Different nonempty IDs share one index.");
            call.id = id;
        }
        String type = text(item, "type");
        if (type != null && !type.equals("function")) throw invalid("Only function tool calls are supported.");
        JsonNode function = item.get("function");
        if (function != null && !function.isNull()) {
            if (!function.isObject()) throw invalid("LLM stream function must be an object.");
            String name = text(function, "name");
            if (name != null) call.name.append(name);
            String arguments = text(function, "arguments");
            if (arguments != null) call.arguments.append(arguments);
            if (call.name.length() > 64 || call.arguments.length() > 64 * 1024) throw invalid("LLM streaming tool fields exceed the size limit.");
        }
    }

    private ObjectNode finish() {
        if (finishReason == null) throw invalid("LLM stream is missing finish_reason.");
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= content.length() || !Character.isLowSurrogate(content.charAt(i))) throw invalid("LLM stream text contains incomplete Unicode.");
            } else if (Character.isLowSurrogate(ch)) throw invalid("LLM stream text contains incomplete Unicode.");
        }
        ObjectNode root = mapper.createObjectNode();
        var choice = root.putArray("choices").addObject().put("finish_reason", finishReason);
        var message = choice.putObject("message").put("role", "assistant");
        if (sawContent) message.put("content", content.toString()); else message.putNull("content");
        if (!calls.isEmpty()) {
            var array = message.putArray("tool_calls");
            int expected = 0;
            for (var entry : calls.entrySet()) {
                if (entry.getKey() != expected++) throw invalid("LLM stream tool indices are incomplete.");
                Call call = entry.getValue();
                var node = array.addObject().put("type", "function");
                if (call.id != null) node.put("id", call.id);
                node.putObject("function").put("name", call.name.toString()).put("arguments", call.arguments.toString());
            }
        }
        if (usage != null) root.set("usage", usage);
        return root;
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("LLM stream field must be text: " + field + ".");
        return value.textValue();
    }
    private static final class Call {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
    private static LlmException rejected() { return new LlmException(LlmException.Kind.MODEL_REJECTION, "Model did not return a usable answer."); }
    private static LlmException invalid(String message) { return new LlmException(LlmException.Kind.INVALID_RESPONSE, message); }
}
