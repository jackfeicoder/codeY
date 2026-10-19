package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.tool.ToolCall;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 JLine 展示与消息隔离：配色仅影响控制台，不污染历史、模型请求或管道。 */
@Timeout(5)
class ConsoleThemeTest {
    @Test void plainThemeAndProgressKeepExistingMachineReadableOutput() throws Exception {
        var text = new StringWriter();
        var out = new PrintWriter(text);
        try (var input = new ConsoleInput(new BufferedReader(new StringReader("")), out)) {
            input.theme().assistant(out, "# 回答\n```java\nint x = 1;\n```");
            input.progressPrinter(out).round(2);
            input.progressPrinter(out).tool(2, new ToolCall("id", "read_file", "{\"path\":\"README.md\"}"));
        }
        assertTrue(text.toString().startsWith("CodeY> # 回答"));
        assertTrue(text.toString().contains("[第 2 轮] 请求模型"));
        assertTrue(text.toString().contains("调用 read_file · README.md"));
        assertFalse(text.toString().contains("\u001b"));
    }

    @Test void modelTextEscapesTerminalCommandsWithoutLosingLongAnswerOrCrLf() {
        var text = new StringWriter();
        String answer = "# 中文😀\r\n```java\r\nint x = 1;\r\n```\r\n"
                + "正文".repeat(1800) + "\u001b[2J尾部";
        new ConsoleTheme(true).assistant(new PrintWriter(text), answer);
        String visible = AttributedString.fromAnsi(text.toString()).toString().replace("\r\n", "\n");
        assertTrue(visible.contains("● CodeY"));
        assertTrue(visible.contains("# 中文😀\n"));
        assertTrue(visible.contains("正文".repeat(1800)));
        assertTrue(visible.contains("\\u001b[2J尾部"));
        assertFalse(text.toString().contains("\u001b[2J"));
        assertFalse(visible.contains("\\u000d"));
        assertNotEquals(new ConsoleTheme(true).text(ConsoleTheme.Tone.USER, "消息"),
                new ConsoleTheme(true).text(ConsoleTheme.Tone.TOOL, "消息"));
        var noColor = new StringWriter();
        new ConsoleTheme(true, false).assistant(new PrintWriter(noColor), "正文\u001b[2J");
        assertTrue(noColor.toString().contains("● CodeY"));
        assertTrue(noColor.toString().contains("正文\\u001b[2J"));
        assertFalse(noColor.toString().contains("\u001b"));
    }

    @Test void actualEditorThemeShowsFrameAndKeepsAssistantHistoryFreeOfAnsi() throws Exception {
        var bytes = new ByteArrayInputStream("你好\r".getBytes(StandardCharsets.UTF_8));
        var display = new ByteArrayOutputStream();
        var terminal = new DumbTerminal("fixture", "xterm", bytes, display, StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(100, 25));
        var out = new PrintWriter(display, true, StandardCharsets.UTF_8);
        try (var input = new ConsoleInput(new BufferedReader(new StringReader("")), out, terminal)) {
            String question = input.readLine("You> ");
            assertEquals("你好", question);
            var session = new Session("base");
            var agent = new AgentLoop(request -> {
                assertEquals("你好", request.messages().get(1).content());
                assertFalse(request.messages().toString().contains("\u001b"));
                return new LlmResponse(Message.assistant("# 答案\n内容😀"), "stop", null);
            }, new ToolRegistry(), 20, input.progressPrinter(out));
            assertTrue(new ChatCli(agent, session, new ToolContext(WorkspaceBinding.none()), input, out, out).answer(question));
            assertEquals("# 答案\n内容😀", session.messages().get(2).content());
        }
        String screen = AttributedString.fromAnsi(display.toString(StandardCharsets.UTF_8)).toString();
        // JLine 会按终端能力把 Unicode 框线转换为 ACS 或 ASCII 横线。
        assertTrue(screen.contains("─".repeat(30)) || screen.contains("-".repeat(30)));
        assertTrue(screen.contains("❯"));
        assertTrue(screen.contains("思考中 · 等待模型响应"));
        assertTrue(screen.contains("● CodeY"));
    }
}
