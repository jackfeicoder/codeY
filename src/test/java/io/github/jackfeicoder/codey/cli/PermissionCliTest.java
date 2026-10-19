package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 真实会话库与内存终端验证模式切换、按文件批准、恢复、管道边界及状态展示。 */
class PermissionCliTest {
    @TempDir Path directory;
    private StringWriter output;
    private StringWriter errors;
    private JsonSessionStore store;

    private int run(String input, LlmClient client, PermissionSettings settings, String resume) throws Exception {
        output = new StringWriter(); errors = new StringWriter();
        store = new JsonSessionStore(directory.resolve("sessions"));
        var writer = new PrintWriter(output);
        var console = new ConsoleInput(new BufferedReader(new StringReader(input)), writer);
        return new SessionLauncher(directory, new SessionSettings(directory.resolve("data"), null, false), store,
                profile -> new SessionLauncher.ModelChoice(profile == null ? "fixture model-a" : profile, client, client),
                () -> "base", console, writer, new PrintWriter(errors),
                new SessionLauncher.Options(resume == null ? directory.toString() : null, false,
                        resume == null ? "fixture" : null, resume, null),
                new ToolRegistry().register(new ReadFileTool()).register(new WriteFileTool()).register(new EditFileTool()),
                DebugPrinter.NONE, List::of, 20, settings).run();
    }
    private static LlmResponse calls(String id, String name, String json) {
        return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall(id, name, json))), "tool_calls", null);
    }
    private static LlmResponse done() { return new LlmResponse(Message.assistant("done"), "stop", new LlmResponse.TokenUsage(123L, 3L, 126L)); }

    @Test void askApprovesEachFileOnceAndSwitchingModelRetainsGrant() throws Exception {
        var round = new AtomicInteger();
        LlmClient client = request -> switch(round.incrementAndGet()) {
            case 1 -> calls("create", "write_file", "{\"path\":\"a.txt\",\"content\":\"old\"}");
            case 3 -> calls("edit", "edit_file", "{\"path\":\"a.txt\",\"oldText\":\"old\",\"newText\":\"new\"}");
            case 5 -> calls("other", "write_file", "{\"path\":\"b.txt\",\"content\":\"b\"}");
            default -> done();
        };
        assertEquals(0, run("first\ny\n/model fixture model-b\nsecond\nthird\ny\n/status\n/exit\n", client,
                new PermissionSettings(PermissionMode.ASK, true), null));
        assertEquals("new", Files.readString(directory.resolve("a.txt")));
        assertEquals("b", Files.readString(directory.resolve("b.txt")));
        assertEquals(2, output.toString().lines().filter(line -> line.startsWith("待批准：")).count());
        assertTrue(output.toString().contains("文件授权 2"));
        assertTrue(output.toString().contains("上次输入 123 tok / 上限 ?"));
        assertEquals(12, store.list().sessions().get(0).messages().size());
    }
    @Test void ordinaryPipeDoesNotConsumeNextChatAsApproval() throws Exception {
        var count = new AtomicInteger();
        LlmClient client = request -> {
            if (count.incrementAndGet() == 1) return calls("write", "write_file", "{\"path\":\"a\",\"content\":\"x\"}");
            if (count.get() == 2) assertTrue(request.messages().get(request.messages().size()-1).content().contains("APPROVAL_REQUIRED"));
            if (count.get() == 3) assertEquals("next question", request.messages().get(request.messages().size()-1).content());
            return done();
        };
        run("first\nnext question\n/exit\n", client, new PermissionSettings(), null);
        assertEquals(3, count.get());
        assertFalse(Files.exists(directory.resolve("a")));
        assertFalse(output.toString().contains("[y] 批准"));
    }
    @Test void planAndFullAreIndependentAndModeDirectivesAreNotPersisted() throws Exception {
        var count = new AtomicInteger();
        LlmClient client = request -> {
            int n = count.incrementAndGet();
            if (n <= 2) {
                assertEquals(List.of("read_file"), request.tools().stream().map(ToolDefinition::name).toList());
                assertTrue(request.messages().get(0).content().contains("当前工作模式：PLAN"));
                if (n == 1) return calls("forced", "write_file", "{\"path\":\"a\",\"content\":\"x\"}");
                assertTrue(request.messages().get(request.messages().size()-1).content().contains("PERMISSION_DENIED"));
            } else if (n == 3) return calls("allowed", "write_file", "{\"path\":\"a\",\"content\":\"x\"}");
            return done();
        };
        run("/permissions full\ny\n/mode plan\nplan task\n/mode react\nexecute task\n/mode multi\n/status\n/exit\n",
                client, new PermissionSettings(PermissionMode.ASK, true), null);
        assertEquals("x", Files.readString(directory.resolve("a")));
        assertFalse(output.toString().contains("待批准："));
        assertTrue(output.toString().contains("multi 尚待实现"));
        assertTrue(output.toString().contains("工作模式：react；权限：full"));
        var saved = store.list().sessions().get(0);
        assertEquals("base", saved.systemPrompt());
        assertTrue(saved.messages().stream().noneMatch(m -> m.content() != null && m.content().contains("当前工作模式：PLAN")));
    }
    @Test void permissionSwitchRevokesGrantAndRestoredSessionStartsAskWithoutGrant() throws Exception {
        var count = new AtomicInteger();
        LlmClient client = request -> {
            int n = count.incrementAndGet();
            if (n % 2 == 1) return calls("write-"+n, "write_file", "{\"path\":\"a\",\"content\":\"value"+n+"\",\"overwrite\":true}");
            return done();
        };
        run("first\ny\n/permissions auto\n/permissions ask\nsecond\ny\n/exit\n", client,
                new PermissionSettings(PermissionMode.ASK, true), null);
        assertEquals(2, output.toString().lines().filter(line -> line.startsWith("待批准：")).count());
        String id = store.list().sessions().get(0).id();
        run("third\ny\n/exit\n", client, new PermissionSettings(PermissionMode.ASK, true), id);
        assertEquals(1, output.toString().lines().filter(line -> line.startsWith("待批准：")).count());
        assertEquals("value5", Files.readString(directory.resolve("a")));
    }
    @Test void eofOrNegativeAnswerDoesNotWriteAndFullNeedsExplicitConfirmation() throws Exception {
        var count = new AtomicInteger();
        LlmClient client = request -> count.incrementAndGet() == 1
                ? calls("write", "write_file", "{\"path\":\"a\",\"content\":\"x\"}") : done();
        run("/permissions full\nn\nfirst\nn\n/status\n/exit\n", client,
                new PermissionSettings(PermissionMode.ASK, true), null);
        assertFalse(Files.exists(directory.resolve("a")));
        assertTrue(output.toString().contains("权限：ask"));
        count.set(0);
        run("first\n", client, new PermissionSettings(PermissionMode.ASK, true), null);
        assertFalse(Files.exists(directory.resolve("a")));
    }
    @Test void modeAndStatusCommandsNeverReachModelAndMultiIsNotFalselyEnabled() throws Exception {
        run("/permissions\n/mode\n/status\n/mode multi\n/mode plan\n/permissions auto\n/permissions revoke\n/status\n/exit\n",
                request -> { fail("Commands must not call a model"); return done(); }, new PermissionSettings(), null);
        assertTrue(output.toString().contains("工作模式：plan；权限：auto"));
        assertTrue(output.toString().contains("上次输入 ? tok / 上限 ?"));
    }
}
