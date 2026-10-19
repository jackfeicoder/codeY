package io.github.jackfeicoder.codey.cli;

import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实 JLine 编辑动作与管道输入；虚拟终端无需桌面窗口或厂商 API。 */
@Timeout(5)
class ConsoleInputTest {
    @Test
    void fixedFooterWorksWithRealLineEditorAndTerminalResize() throws Exception {
        var bytes = new ByteArrayInputStream("first\rsecond\r".getBytes(StandardCharsets.UTF_8));
        var display = new ByteArrayOutputStream();
        var terminal = new DumbTerminal("fixture", "xterm", bytes, display, StandardCharsets.UTF_8);
        terminal.setSize(new org.jline.terminal.Size(100, 25));
        try (var input = new ConsoleInput(new BufferedReader(new StringReader("")), new PrintWriter(display), terminal)) {
            var status = new RuntimeStatus(); status.model("fixture model");
            input.status(status);
            assertEquals("first", input.readLine("You> "));
            terminal.setSize(new org.jline.terminal.Size(28, 10));
            status.modes(io.github.jackfeicoder.codey.agent.WorkMode.PLAN, io.github.jackfeicoder.codey.policy.PermissionMode.FULL);
            assertEquals("second", input.readLine("You> "));
            input.status(null);
        }
        String text = display.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("react"));
        assertTrue(text.contains("plan"));
    }

    @Test
    void plainInputKeepsUnicodePromptsAndEof() throws Exception {
        var output = new StringWriter();
        try (var input = new ConsoleInput(new BufferedReader(new StringReader("你好\n/exit\n")), new PrintWriter(output))) {
            assertEquals("你好", input.readLine("You> "));
            assertEquals("/exit", input.readLine("选择会话> "));
            assertNull(input.readLine("You> "));
        }
        assertEquals("You> 选择会话> You> ", output.toString());
    }

    @Test
    void tabCompletesUniqueCommandAndPreservesLiteralWindowsPaths() throws Exception {
        assertEquals("/help", edit("/h\t\r"));
        assertEquals("/rename E:\\Desktop\\项目!", edit("/rename E:\\Desktop\\项目!\r"));
    }

    @Test
    void arrowKeyChangesMenuCandidateAndEnterConfirmsBeforeSubmission() throws Exception {
        String first = edit("/m\t\r\r");
        String next = edit("/m\t\u001b[B\r\r");
        assertTrue(List.of("/model", "/mode", "/more_session").contains(first), "first=" + first);
        assertTrue(List.of("/model", "/mode", "/more_session").contains(next), "next=" + next);
        assertNotEquals(first, next);
    }

    private String edit(String keys) throws Exception {
        return edit(keys, 30);
    }

    @Test
    void menuSupportsVeryShortTerminalWithoutCrashing() throws Exception {
        assertTrue(edit("/m\t\r", 1).startsWith("/m"));
        assertEquals("/help", edit("/h\t\r", 1));
    }

    private String edit(String keys, int rows) throws Exception {
        var bytes = new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8));
        try (var terminal = new DumbTerminal("fixture", "xterm", bytes, new ByteArrayOutputStream(), StandardCharsets.UTF_8)) {
            terminal.setSize(new org.jline.terminal.Size(100, rows));
            var reader = ConsoleInput.createReader(terminal, new CommandCompleter(List::of, () -> true));
            return reader.readLine("You> ").stripTrailing();
        }
    }
}
