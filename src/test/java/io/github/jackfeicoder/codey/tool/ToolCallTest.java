package io.github.jackfeicoder.codey.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** 工具请求数据测试：验证字段必填和参数文本原样保留，不执行真实工具。 */
    /** 验证参数文本原样保存，无参数调用以空对象表达。 */
class ToolCallTest {
    @Test
    void keepsOriginalArgumentsForLaterJsonParsing() {
        // 测试使用的原始 JSON 参数文本。
        String arguments = "{ \"path\": \"src/Main.java\", \"limit\": 20 }";
        assertEquals(arguments, new ToolCall("call_1", "read_file", arguments).arguments());
        assertEquals("{}", new ToolCall("call_2", "list_files", "{}").arguments());
    }

    /** 验证调用 ID 非空，便于后续结果关联。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingId(String id) {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall(id, "read_file", "{}"));
    }

    /** 验证工具名称非空，便于注册表查找。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingName(String name) {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("call_1", name, "{}"));
    }

    /** 验证参数文本非空，具体 JSON 校验由 LLM 层负责。 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingArguments(String arguments) {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("call_1", "read_file", arguments));
    }
}
