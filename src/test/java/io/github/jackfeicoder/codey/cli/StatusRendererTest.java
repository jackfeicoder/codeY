package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.PermissionMode;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 状态栏宽度、终端控制字符与 token 来源标记；不依赖真实 API 或桌面窗口。 */
class StatusRendererTest {
    @Test void alignsChineseTextByTerminalColumnsAndHandlesNarrowWidths() {
        for (int width : new int[]{1, 10, 30, 60, 100}) {
            String line = StatusRenderer.render("react · ask", "供应商/模型😀 · 上次输入 123 tok", width);
            assertTrue(new AttributedString(line).columnLength() <= Math.max(1, width-1), line);
        }
        assertTrue(StatusRenderer.render("plan · full", "model", 80).startsWith("plan · full"));
        assertTrue(StatusRenderer.render("plan · full", "model", 80).endsWith("model"));
        assertTrue(new AttributedString(StatusRenderer.render("超长模式".repeat(100), "model", 50)).columnLength() <= 49);
        assertTrue(new AttributedString(StatusRenderer.render("x".repeat(100), "model", 50)).columnLength() <= 49);
    }
    @Test void escapesTerminalControlCharactersAndResetsStaleUsageOnModelSwitch() {
        String line = StatusRenderer.render("react", "bad\u001b[31m\nmodel\u202E", 100);
        assertFalse(line.contains("\u001b")); assertFalse(line.contains("\n")); assertFalse(line.contains("\u202E"));
        var status = new RuntimeStatus();
        status.modes(WorkMode.PLAN, PermissionMode.FULL);
        status.model("fixture A");
        status.usage(new LlmResponse.TokenUsage(99L, 1L, 100L));
        assertEquals("plan · full", status.left());
        assertTrue(status.right().contains("上次输入 99"));
        status.model("fixture B");
        assertTrue(status.right().contains("上次输入 ?"));
        status.usage(null); assertTrue(status.right().contains("上限 ?"));
    }
}
