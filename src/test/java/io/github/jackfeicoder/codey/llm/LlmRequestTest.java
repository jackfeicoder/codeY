package io.github.jackfeicoder.codey.llm;

import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.tool.ToolDefinition;
import io.github.jackfeicoder.codey.tool.builtin.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 请求工具列表的独立快照、重复名称约束与普通聊天兼容性。 */
class LlmRequestTest {
    /** 创建后外部注册列表或参数树的变化不改动已发送请求。 */
    @Test
    void takesImmutableDefinitionSnapshot() {
        var definition = new ReadFileTool().definition();
        List<ToolDefinition> definitions = new ArrayList<>(List.of(definition));
        LlmRequest request = new LlmRequest(List.of(Message.user("q")), definitions);
        definitions.clear();
        ((com.fasterxml.jackson.databind.node.ObjectNode) definition.parameters()).remove("properties");
        assertEquals(1, request.tools().size());
        assertTrue(request.tools().get(0).parameters().has("properties"));
        assertThrows(UnsupportedOperationException.class, () -> request.tools().clear());
    }

    /** null 项、null 列表和同名定义在网络请求前被拒绝。 */
    @Test
    void rejectsAmbiguousToolLists() {
        var messages = List.of(Message.user("q"));
        var definition = new ReadFileTool().definition();
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest(messages, null));
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest(messages, Arrays.asList(definition, null)));
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest(messages, List.of(definition, definition)));
    }
}
