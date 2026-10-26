package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 上下文命令、会话预览和模式/工作区工具筛选联测；不请求真实模型。 */
class ContextCliTest {
    @TempDir Path directory;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private final List<LlmRequest> requests = new ArrayList<>();

    private JsonSessionStore run(String commands, SessionLauncher.Options options, Long capacity) throws Exception {
        var store = new JsonSessionStore(directory.resolve("sessions"));
        var writer = new PrintWriter(output);
        LlmClient client = request -> {
            requests.add(request);
            return new LlmResponse(Message.assistant("done"), "stop", new LlmResponse.TokenUsage(321L, 1L, 322L));
        };
        int exit = new SessionLauncher(directory, new SessionSettings(directory.resolve("data"), null, false), store,
                selection -> new SessionLauncher.ModelChoice("fixture model", client, client, new ModelCapabilities(capacity)),
                () -> "private-base-not-for-console", new ConsoleInput(new BufferedReader(new StringReader(commands)), writer),
                writer, new PrintWriter(errors), options, new ToolRegistry().register(new ReadFileTool()).register(new WriteFileTool()),
                DebugPrinter.NONE, List::of, 20, new PermissionSettings()).run();
        assertEquals(0, exit); assertEquals("", errors.toString());
        return store;
    }

    @Test void previewsBeforeFirstRequestFiltersPlanToolsAndKeepsActualUsageSeparate() throws Exception {
        var store = run("/context\n/mode plan\n/context\n/mode react\nhello\n/context\n/status\n/help\n/exit\n",
                new SessionLauncher.Options(directory.toString(), false, "fixture", null, null), 128000L);
        String text = output.toString();
        assertTrue(text.indexOf("已用 ≈") < text.indexOf("You>"));
        assertTrue(text.contains("完整 JSON Schema，2 个"));
        assertTrue(text.contains("完整 JSON Schema，1 个"));
        assertTrue(text.contains("本次用户输入：≈0 tok"));
        assertTrue(text.contains("/context"));
        assertTrue(text.contains("最近实际输入：321 tok"));
        assertFalse(text.contains("private-base-not-for-console"));
        assertEquals(1, requests.size());
        assertEquals(2, requests.get(0).tools().size());
        assertEquals(List.of(Message.system("private-base-not-for-console"), Message.user("hello")), requests.get(0).messages());
        assertEquals(2, store.list().sessions().get(0).messages().size());
    }

    @Test void resumeReestimatesHistoryWithoutNetworkOrPersistedUsage() throws Exception {
        var store = run("hello\n/exit\n", new SessionLauncher.Options(null, true, "fixture", null, null), 128000L);
        String id = store.list().sessions().get(0).id();
        output.getBuffer().setLength(0);
        run("/context\n/status\n/exit\n", new SessionLauncher.Options(null, false, null, id, null), null);
        String text = output.toString();
        assertEquals(1, requests.size()); // 恢复/查看不额外请求模型，未知容量明确跳过检查。
        assertTrue(text.matches("(?s).*成功历史（含过去工具调用与结果）：≈[1-9][0-9]* tok.*"));
        assertTrue(text.contains("完整 JSON Schema，0 个"));
        assertTrue(text.contains("最近实际输入：未知"));
        assertTrue(text.contains("/ ? (?%)"));
        assertTrue(text.contains("已用 ≈"));
        assertEquals(2, store.load(id).messages().size());
    }

    @Test void contextCommandInSessionBrowserDoesNotCreateSessionOrCallModel() throws Exception {
        var store = run("/context\n/exit\n", new SessionLauncher.Options(null, false, null, null, null), null);
        assertTrue(output.toString().contains("请先选择或新建会话"));
        assertTrue(requests.isEmpty()); assertTrue(store.list().sessions().isEmpty());
    }
}
