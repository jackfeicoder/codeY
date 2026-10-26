package io.github.jackfeicoder.codey.context;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.tool.ToolDefinition;

/** 本地启发式估算器；适用于显示和比较，不冒充各厂商的精确 tokenizer。 */
public final class TokenEstimator {
    /** 标记算法版本，方便将来替换分词器并解释估算变化。 */
    public static final String METHOD = "heuristic-v1（非精确分词）";

    /** ASCII 按约 3 字符/token，非 ASCII 按 UTF-8 字节数的 2/3 估算；不读取凭证或远程接口。 */
    public long textTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        long units = 0;
        for (int index = 0; index < text.length();) {
            int point = text.codePointAt(index);
            index += Character.charCount(point);
            int bytes = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
            units += bytes == 1 ? 1 : 2L * bytes;
        }
        return (units + 2) / 3;
    }

    /** 消息正文及调用数据；角色/消息边界的估算单独归入协议开销，避免重复计算。 */
    public long messageBodyTokens(Message message) {
        long tokens = textTokens(message.content()) + textTokens(message.toolCallId());
        for (var call : message.toolCalls()) {
            tokens += textTokens(call.id()) + textTokens(call.name()) + textTokens(call.arguments()) + 8;
        }
        return tokens;
    }

    /** 统计工具名称、描述、完整 JSON Schema 和 function 包装；工具不是额外的 system.md。 */
    public long toolTokens(ToolDefinition tool) {
        var wrapper = JsonNodeFactory.instance.objectNode().put("type", "function");
        var function = wrapper.putObject("function");
        function.put("name", tool.name()).put("description", tool.description());
        function.set("parameters", tool.parameters());
        return textTokens(wrapper.toString());
    }
}
