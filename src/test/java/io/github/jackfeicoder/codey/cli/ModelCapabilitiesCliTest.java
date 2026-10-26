package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 本地配置、真实会话库与 CLI 联测：切换容量、重新加载、失败回退，不发送真实 HTTP。 */
class ModelCapabilitiesCliTest {
    @TempDir Path directory;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private final List<LlmRequest> requests = new ArrayList<>();

    private JsonSessionStore run(String commands, String resume, Consumer<Integer> onRequest) throws Exception {
        Files.writeString(directory.resolve(".env"), """
                CODEY_PROFILE_NAMES=fixture,other
                CODEY_ACTIVE_PROFILE=fixture
                CODEY_PROFILE_FIXTURE_BASE_URL=http://127.0.0.1:1/v1
                CODEY_PROFILE_FIXTURE_AUTH=none
                CODEY_PROFILE_FIXTURE_MODEL=org/model-a,org/model-b,org/model-c
                CODEY_PROFILE_OTHER_BASE_URL=http://127.0.0.1:1/v1
                CODEY_PROFILE_OTHER_AUTH=none
                CODEY_PROFILE_OTHER_MODEL=org/model-a
                """);
        var store = new JsonSessionStore(directory.resolve("data"));
        var writer = new PrintWriter(output);
        var input = new ConsoleInput(new BufferedReader(new StringReader(commands)), writer);
        var options = new SessionLauncher.Options(null, resume == null, resume == null ? "capacity" : null, resume, null);
        int status = new SessionLauncher(directory, new SessionSettings(directory.resolve("data"), null, false), store, selection -> {
            var profiles = ConfigLoader.loadProfiles(directory, Map.of(), selection);
            var config = profiles.activeConfig();
            var capabilities = ModelCapabilitiesLoader.load(directory, Map.of(), profiles.activeProfile(), config.model());
            LlmClient client = request -> {
                requests.add(request); onRequest.accept(requests.size());
                return new LlmResponse(Message.assistant("answer"), "stop", new LlmResponse.TokenUsage(123L, 2L, 125L));
            };
            return new SessionLauncher.ModelChoice(profiles.activeSelection(), client, client, capabilities);
        }, () -> "base", input, writer, new PrintWriter(errors), options, new ToolRegistry(), DebugPrinter.NONE,
                () -> ConfigLoader.loadModelCatalog(directory, Map.of())).run();
        assertEquals(0, status);
        return store;
    }

    private void writeCapacity(long a, long b) throws IOException {
        Files.writeString(directory.resolve("models.json"), """
                {"models":[
                  {"profile":"fixture","modelId":"org/model-a","contextWindow":%d},
                  {"profile":"fixture","modelId":"org/model-b","contextWindow":%d}
                ]}
                """.formatted(a, b));
    }

    @Test void switchesPerModelWindowAndUnknownWithoutChangingHistoryOrLlmMessages() throws Exception {
        writeCapacity(128000, 1000000);
        var store = run("/status\nfirst\n/model fixture org/model-b\n/status\nsecond\n/model other org/model-a\n/status\n/exit\n", null, ignored -> { });
        String text = output.toString();
        assertTrue(text.matches("(?s).*fixture org/model-a \\| 已用 ≈[0-9.]+ / 128K \\(0.0%\\).*"), text);
        assertTrue(text.matches("(?s).*fixture org/model-b \\| 已用 ≈[0-9.]+ / 1M \\(0.0%\\).*"), text);
        assertTrue(text.matches("(?s).*other org/model-a \\| 已用 ≈[0-9.]+ / \\? \\(\\?%\\).*"), text);
        assertTrue(text.contains("models.json（本地配置）"));
        assertTrue(text.contains("已启用已知容量的请求前预算检查"));
        assertEquals("", errors.toString());
        assertEquals(List.of(Message.system("base"), Message.user("first"), Message.assistant("answer"), Message.user("second")), requests.get(1).messages());
        assertEquals(4, store.list().sessions().get(0).messages().size());
    }

    @Test void reselectingModelReloadsFileAndResumeUsesCurrentCapacity() throws Exception {
        writeCapacity(128000, 1000000);
        var store = run("first\n/model fixture org/model-a\n/status\n/exit\n", null, ignored -> {
            try { writeCapacity(256000, 1000000); } catch (IOException failed) { throw new UncheckedIOException(failed); }
        });
        assertTrue(output.toString().matches("(?s).*fixture org/model-a \\| 已用 ≈[0-9.]+ / 256K \\(0.0%\\).*"));
        String id = store.list().sessions().get(0).id();
        writeCapacity(512000, 1000000);
        output.getBuffer().setLength(0);
        run("/status\n/exit\n", id, ignored -> fail("Browsing/resume must not call a model"));
        assertTrue(output.toString().contains("/ 512K (0.0%)"));
        assertTrue(output.toString().contains("已用 ≈"));
        assertEquals(1, requests.size());
        assertEquals(2, store.load(id).messages().size());
    }

    @Test void invalidReloadKeepsCurrentModelWindowUsageAndHistory() throws Exception {
        writeCapacity(128000, 1000000);
        var store = run("first\n/model fixture org/model-b\n/status\n/exit\n", null, ignored -> {
            try { Files.writeString(directory.resolve("models.json"), "{secret-fixture"); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
        });
        assertTrue(errors.toString().contains("models.json"));
        assertFalse(errors.toString().contains("secret-fixture"));
        assertTrue(output.toString().contains("fixture org/model-a | 已用 ≈"));
        assertTrue(output.toString().contains("/ 128K (0.0%)"));
        assertTrue(output.toString().contains("最近实际输入：123 tok"));
        assertEquals("fixture org/model-a", store.list().sessions().get(0).profileAlias());
        assertEquals(2, store.list().sessions().get(0).messages().size());
    }
}
