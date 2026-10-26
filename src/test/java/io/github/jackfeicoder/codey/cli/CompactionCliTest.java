package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.context.SummaryDocument;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真正的命令分发、恢复和本地 JSON 保存；模型替身区分摘要与业务请求。 */
class CompactionCliTest {
    @TempDir Path directory;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private final List<LlmRequest> requests = new ArrayList<>();
    private final JsonSessionStore store() { return new JsonSessionStore(directory.resolve("store")); }
    private String seed() {
        try (var service = SessionService.create(store(), WorkspaceBinding.none(), "fixture demo", "base", "fixture")) {
            for (int i = 0; i < 4; i++) service.session().completeTurn("old" + i, Message.assistant("x".repeat(6000)));
            for (int i = 0; i < 3; i++) service.session().completeTurn("recent" + i, Message.assistant("done"));
            service.captureTurn(); service.save(); return service.snapshot().id();
        }
    }
    private void run(String id, String commands, boolean failBusiness) throws IOException {
        var writer = new PrintWriter(output);
        LlmClient client = request -> {
            requests.add(request);
            if (request.maxOutputTokens() != null) return new LlmResponse(Message.assistant(new SummaryDocument(
                    List.of("目标"), List.of("保留约束"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of("下一步")).json()), "stop", null);
            if (failBusiness) throw new LlmException(LlmException.Kind.HTTP, "fixture HTTP failure");
            return new LlmResponse(Message.assistant("business done"), "stop", new LlmResponse.TokenUsage(123L, 3L, 126L));
        };
        int status = new SessionLauncher(directory, new SessionSettings(directory.resolve("data"), null, false), store(),
                selection -> new SessionLauncher.ModelChoice(selection == null ? "fixture demo" : selection, client, client,
                        new ModelCapabilities(12000L)), () -> "base",
                new ConsoleInput(new BufferedReader(new StringReader(commands)), writer), writer, new PrintWriter(errors),
                new SessionLauncher.Options(null, false, null, id, null), new ToolRegistry(), DebugPrinter.NONE, List::of,
                20, new PermissionSettings(), new ContextBudgetSettings(1024, 1024, new BigDecimal("0.8")),
                new CompactionSettings(true, new BigDecimal("0.8"), new BigDecimal("0.4"), 3, 512, 8, 5)).run();
        assertEquals(0, status);
    }

    @Test void contextPreviewHasNoNetworkAndManualCompactionDoesNotCreateUserOrTitleTurn() throws IOException {
        String id = seed(); var raw = store().load(id).messages();
        run(id, "/context\n/status\n/exit\n", false); assertTrue(requests.isEmpty());
        run(id, "/compact\n/context\n/exit\n", false);
        assertFalse(requests.isEmpty()); assertTrue(requests.stream().allMatch(r -> r.maxOutputTokens() != null));
        var snapshot = store().load(id); assertEquals(raw, snapshot.messages()); assertEquals(3, snapshot.schemaVersion());
        assertNotNull(snapshot.compaction()); assertTrue(output.toString().contains("手动压缩：压缩成功"));
        assertTrue(output.toString().contains("当前摘要：覆盖原始历史前"));
    }

    @Test void autoSummarySurvivesBusinessFailureAndResumeAndSwitchReuseItWithoutNetwork() throws IOException {
        String id = seed(); var raw = store().load(id).messages(); run(id, "continue\n/exit\n", true);
        var saved = store().load(id); assertEquals(raw, saved.messages()); assertNotNull(saved.compaction());
        assertEquals(1, requests.stream().filter(r -> r.maxOutputTokens() == null).count());
        assertTrue(errors.toString().contains("This turn was not saved"));
        requests.clear(); run(id, "/context\n/model other demo\n/status\n/exit\n", false);
        assertTrue(requests.isEmpty()); assertEquals(saved.compaction(), store().load(id).compaction());
        assertEquals("other demo", store().load(id).profileAlias());
        run(id, "continue again\n/exit\n", false);
        assertEquals(1, requests.size()); assertNull(requests.get(0).maxOutputTokens());
        assertTrue(requests.get(0).messages().get(1).content().contains("结构化摘要"));
        assertEquals(raw.size() + 2, store().load(id).messages().size());
    }
}
