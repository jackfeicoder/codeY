package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本地会话库验证超限后可继续操作、切模型保留历史、预算配置错误回退与未知窗口。 */
class ContextBudgetCliTest {
    @TempDir Path directory;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private final List<LlmRequest> requests = new ArrayList<>();

    private JsonSessionStore run(String commands) throws Exception {
        var store = new JsonSessionStore(directory.resolve("sessions"));
        var writer = new PrintWriter(output);
        LlmClient client = request -> {
            requests.add(request);
            return new LlmResponse(Message.assistant("done"), "stop", new LlmResponse.TokenUsage(321L, 1L, 322L));
        };
        int status = new SessionLauncher(directory, new SessionSettings(directory.resolve("data"), null, false), store,
                selection -> {
                    String alias = selection == null ? "small" : selection.split(" ")[0];
                    var capacity = switch (alias) {
                        case "small" -> new ModelCapabilities(10000L, 1000L, 1000L);
                        case "large" -> new ModelCapabilities(128000L);
                        case "bad" -> new ModelCapabilities(2L);
                        default -> ModelCapabilities.unknown();
                    };
                    return new SessionLauncher.ModelChoice(alias + " demo", client, client, capacity);
                }, () -> "private-base", new ConsoleInput(new BufferedReader(new StringReader(commands)), writer),
                writer, new PrintWriter(errors), new SessionLauncher.Options(null, true, "budget", null, null),
                new ToolRegistry(), DebugPrinter.NONE, List::of, 20, new PermissionSettings(), new ContextBudgetSettings()).run();
        assertEquals(0, status);
        return store;
    }

    @Test void overLimitTurnIsNotSavedAndLargerModelKeepsOldHistoryAndCanContinue() throws Exception {
        String large = "x".repeat(30000);
        var store = run("first\n" + large + "\n/context\n/model large demo\n" + large + "\n/status\n/exit\n");
        assertTrue(errors.toString().contains("上下文预算超限"));
        assertTrue(errors.toString().contains("completed 0 model requests and 0 tool calls"));
        assertEquals(2, requests.size());
        assertEquals(List.of(Message.system("private-base"), Message.user("first"), Message.assistant("done"), Message.user(large)), requests.get(1).messages());
        var snapshot = store.list().sessions().get(0);
        assertEquals(4, snapshot.messages().size()); assertEquals("large demo", snapshot.profileAlias());
        assertTrue(output.toString().contains("输出预留：1000 tok；安全余量：1000 tok"));
        assertTrue(output.toString().contains("输出预留：8192 tok；安全余量：4096 tok"));
        assertTrue(output.toString().contains("80% 输入预算"));
        assertTrue(output.toString().contains("最近实际输入：321 tok"));
    }

    @Test void invalidModelBudgetKeepsOldSelectionActualUsagePreferenceAndMessages() throws Exception {
        var store = run("first\n/model bad demo\n/status\n/exit\n");
        assertTrue(errors.toString().contains("Configuration error: Context output reserve plus safety margin"));
        assertEquals(1, requests.size());
        var snapshot = store.list().sessions().get(0);
        assertEquals("small demo", snapshot.profileAlias()); assertEquals(2, snapshot.messages().size());
        assertEquals("small demo", new ModelPreferenceStore(directory.resolve("data")).load().orElseThrow());
        assertTrue(output.toString().contains("最近实际输入：321 tok"));
        assertFalse(output.toString().contains("已切换模型：bad"));
    }

    @Test void switchingToUnknownCapacitySkipsCheckAndShowsUnknownWithoutGuessingWindow() throws Exception {
        var store = run("/model unknown demo\n/context\n" + "x".repeat(30000) + "\n/status\n/exit\n");
        assertEquals("", errors.toString()); assertEquals(1, requests.size());
        assertTrue(output.toString().contains("容量未知，未执行容量检查"));
        assertTrue(output.toString().contains("/ ? (?%)"));
        assertEquals(2, store.list().sessions().get(0).messages().size());
    }

    @Test void switchingToSmallerModelKeepsHistoryButNextRequestIsBlocked() throws Exception {
        var store = run("/model large demo\n" + "x".repeat(30000) + "\n/model small demo\n/context\ncontinue\n/exit\n");
        assertEquals(1, requests.size());
        assertTrue(errors.toString().contains("上下文预算超限"));
        assertTrue(output.toString().contains("超过输入预算"));
        assertEquals(2, store.list().sessions().get(0).messages().size());
        assertEquals("small demo", store.list().sessions().get(0).profileAlias());
    }
}
