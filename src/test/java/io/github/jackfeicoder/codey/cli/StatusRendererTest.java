package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.policy.PermissionMode;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 状态栏宽度、终端控制字符与 token 来源标记；不依赖真实 API 或桌面窗口。 */
class StatusRendererTest {
    @Test void estimateTakesPriorityOverActualUsageAndRetainsWidthColorAndRatio() {
        var status = new RuntimeStatus();
        status.model("fixture model", new io.github.jackfeicoder.codey.config.ModelCapabilities(1048576L));
        status.usage(new LlmResponse.TokenUsage(321L, 1L, 322L));
        status.context(new io.github.jackfeicoder.codey.context.ContextSnapshot(
                new io.github.jackfeicoder.codey.llm.LlmRequest(java.util.List.of(io.github.jackfeicoder.codey.session.Message.system("base"))),
                new io.github.jackfeicoder.codey.context.ContextSnapshot.TokenBreakdown(118496, 0, 0, 0, 0, 0, 0, 0)));
        assertEquals(321L, status.lastInputTokens()); assertEquals(118496L, status.usedTokens());
        assertTrue(status.right().contains("已用 ≈118.5K / 1.05M (11.3%)"));
        status.usage(null); // usage 缺失不抹掉本地上下文估算。
        assertEquals(118496L, status.usedTokens());
        for (int width : new int[]{40, 60, 100}) {
            var line = StatusRenderer.styled(status, width);
            assertTrue(line.columnLength() <= width - 1);
            assertTrue(line.toString().contains("≈118.5K"), line.toString());
            assertTrue(line.toString().contains("11.3%"), line.toString());
            assertEquals(org.jline.utils.AttributedStyle.DEFAULT.foreground(org.jline.utils.AttributedStyle.MAGENTA).bold(),
                    line.styleAt(line.toString().indexOf("≈")));
        }
        status.model("other"); assertNull(status.usedTokens()); assertNull(status.context());
    }
    @Test void knownWindowIsRenderedAndReplacedWithUnknownOnSwitch() {
        var status = new RuntimeStatus();
        status.model("供应商/很长的模型名😀".repeat(20), new io.github.jackfeicoder.codey.config.ModelCapabilities(1000000L));
        status.usage(new LlmResponse.TokenUsage(12345L, 1L, 12346L));
        for (int width : new int[]{60, 100, 180}) {
            var line = StatusRenderer.styled(status, width);
            assertTrue(line.columnLength() <= width - 1);
            assertTrue(line.toString().contains("1M"), line.toString());
            assertTrue(line.toString().contains("12.3K"), line.toString());
            assertTrue(line.toString().contains("1.2%"), line.toString());
            assertEquals(ConsoleTheme.style(ConsoleTheme.Tone.COMMAND), line.styleAt(line.toString().indexOf("1M")));
        }
        // 截图场景：用原始 118496 / 1048576 计算占比，缩写不改变统计；窄屏仍保留百分比。
        status.model("deepseek deepseek-flash", new io.github.jackfeicoder.codey.config.ModelCapabilities(1048576L));
        status.usage(new LlmResponse.TokenUsage(118496L, 1L, 118497L));
        assertTrue(status.right().endsWith("已用 118.5K / 1.05M (11.3%)"));
        assertEquals(118496L, status.lastInputTokens());
        for (int width : new int[]{40, 60, 100}) {
            var line = StatusRenderer.styled(status, width);
            assertTrue(line.columnLength() <= width - 1);
            assertTrue(line.toString().contains("11.3%"), line.toString());
        }
        status.usage(null);
        assertEquals(1048576L, status.contextWindow());
        status.model("fixture unknown");
        assertNull(status.contextWindow());
        assertTrue(status.right().endsWith("/ ? (?%)"));
    }
    @Test void styledFooterPreservesFirstLetterAndTokensBeforeLongModelName() {
        var status = new RuntimeStatus();
        status.model("供应商/超长模型😀".repeat(30));
        status.usage(new LlmResponse.TokenUsage(12345L, 1L, 12346L));
        for (int width : new int[]{20, 28, 40, 60, 100, 180}) {
            var line = StatusRenderer.styled(status, width);
            assertTrue(line.toString().startsWith("  react · ask"), line.toString());
            assertTrue(line.columnLength() <= width - 1);
            if (width >= 40) {
                assertTrue(line.toString().contains("12.3K"), line.toString());
                assertTrue(line.toString().contains("?%"), line.toString());
            }
            assertEquals(ConsoleTheme.style(ConsoleTheme.Tone.USER), line.styleAt(2));
            assertEquals(ConsoleTheme.style(ConsoleTheme.Tone.WARNING), line.styleAt(line.toString().indexOf("ask")));
        }
        status.modes(WorkMode.PLAN, PermissionMode.AUTO);
        var auto = StatusRenderer.styled(status, 100);
        assertEquals(ConsoleTheme.style(ConsoleTheme.Tone.SUCCESS), auto.styleAt(auto.toString().indexOf("auto")));
        status.modes(WorkMode.REACT, PermissionMode.FULL);
        var full = StatusRenderer.styled(status, 100);
        assertEquals(ConsoleTheme.style(ConsoleTheme.Tone.ERROR), full.styleAt(full.toString().indexOf("full")));
    }
    @Test void alignsChineseTextByTerminalColumnsAndHandlesNarrowWidths() {
        for (int width : new int[]{1, 10, 30, 60, 100}) {
            String line = StatusRenderer.render("react · ask", "供应商/模型😀 · 已用 123 / 1M (0.0%)", width);
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
        assertTrue(status.right().contains("已用 99"));
        status.model("fixture B");
        assertTrue(status.right().contains("已用 ?"));
        status.usage(null); assertTrue(status.right().contains("/ ? (?%)"));
    }
}
