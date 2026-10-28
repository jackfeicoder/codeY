package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实启动器、命令分发与存储；模型替身观察请求，不使用真实密钥。 */
class MemoryCliTest {
    @TempDir Path root;
    private final List<LlmRequest> requests = new ArrayList<>();
    private final StringWriter output = new StringWriter(), errors = new StringWriter();
    private MemoryService service() { return new MemoryService(new JsonMemoryStore(root.resolve("data")), new MemorySettings()); }
    private WorkspaceBinding workspace(String name) throws Exception {
        var path = root.resolve(name); Files.createDirectories(path);
        return new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, path.toString());
    }
    private void run(String commands, WorkspaceBinding workspace) throws Exception {
        LlmClient client = request -> { requests.add(request); return new LlmResponse(Message.assistant("answer"), "stop", null); };
        var data = root.resolve("data");
        int status = new SessionLauncher(root, new SessionSettings(data, null, false), new JsonSessionStore(data),
                selected -> new SessionLauncher.ModelChoice("fixture demo", client, client), () -> "base",
                new BufferedReader(new StringReader(commands)), new PrintWriter(output), new PrintWriter(errors),
                new SessionLauncher.Options(workspace.path(), workspace.mode() == WorkspaceBinding.Mode.NONE, "fixture", null, null), new ToolRegistry()).run();
        assertEquals(0, status);
    }
    @Test void explicitCommandsPersistAndReloadAcrossSessionsAndProjects() throws Exception {
        var a = workspace("a"); var b = workspace("b");
        run("/remember --user --always PINNED_LANGUAGE\n/remember --tags Redis,compat Redis背景仅项目A\n/memory\nhello\nRedis\n/context\n/exit\n", a);
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).messages().get(0).content().contains("PINNED_LANGUAGE"));
        assertFalse(requests.get(0).messages().get(1).content().contains("Redis背景"));
        assertTrue(requests.get(1).messages().get(3).content().contains("Redis背景仅项目A"));
        assertTrue(output.toString().contains("用户常驻记忆"));
        assertEquals(2, service().list(a).size());
        run("Redis\n/exit\n", b);
        assertTrue(requests.get(2).messages().get(0).content().contains("PINNED_LANGUAGE"));
        assertFalse(requests.get(2).messages().toString().contains("Redis背景仅项目A"));
        run("Redis\n/exit\n", a);
        assertTrue(requests.get(3).messages().get(1).content().contains("Redis背景仅项目A"));
        var snapshots = new JsonSessionStore(root.resolve("data")).list().sessions();
        assertTrue(snapshots.stream().flatMap(s -> s.messages().stream()).noneMatch(m -> m.content() != null && m.content().contains("长期记忆结束")));
        assertEquals("", errors.toString());
    }
    @Test void updateAndForgetHaveImmediateEffectAndCommandsNeverBecomeChatHistory() throws Exception {
        var item = service().remember(WorkspaceBinding.none(), MemoryScope.USER, MemoryLoading.ALWAYS, null, "OLD_RULE", List.of(), null, null);
        run("/remember --user --always --update " + item.id() + " NEW_RULE\nhello\n/forget " + item.id() + "\nhello again\n/exit\n", WorkspaceBinding.none());
        assertTrue(requests.get(0).messages().get(0).content().contains("NEW_RULE"));
        assertFalse(requests.get(0).messages().get(0).content().contains("OLD_RULE"));
        assertFalse(requests.get(1).messages().get(0).content().contains("NEW_RULE"));
        assertTrue(service().list(WorkspaceBinding.none()).isEmpty());
        var saved = new JsonSessionStore(root.resolve("data")).list().sessions().get(0);
        assertEquals(4, saved.messages().size()); assertEquals("hello", saved.messages().get(0).content());
    }
    @Test void projectAlwaysAndImplicitNoWorkspaceAreRejectedWithoutModelCalls() throws Exception {
        run("/remember no scope\n/remember --project --always rule\n/exit\n", WorkspaceBinding.none());
        assertTrue(requests.isEmpty()); assertTrue(service().list(WorkspaceBinding.none()).isEmpty());
        assertTrue(errors.toString().contains("--user"));
    }
    @Test void quotedOptionsContentAndInvalidFlagsAreHandledWithoutShellParsing() throws Exception {
        var writer = new PrintWriter(output);
        var commands = new MemoryCommands(service(), WorkspaceBinding.none(), null, writer);
        commands.remember("--user --title \"面试 偏好\" --tags Java,并发 -- Keep `literal` \"quotes\"");
        var entry = service().list(WorkspaceBinding.none()).get(0);
        assertEquals("面试 偏好", entry.title()); assertEquals("Keep `literal` \"quotes\"", entry.content());
        assertEquals(List.of("Java", "并发"), entry.tags());
        for (String invalid : List.of("--user --project text", "--user --always --on-demand text", "--user --bogus text", "--user --title", "--user --title \"open", "--user --tags a, text"))
            assertThrows(MemoryException.class, () -> commands.remember(invalid));
        commands.list("并发"); assertTrue(output.toString().contains(entry.id()));
    }
    @Test void corruptMemoryWarnsAndDoesNotOverwriteOrInjectPartialContent() throws Exception {
        Files.createDirectories(root.resolve("data/memory"));
        Files.writeString(root.resolve("data/memory/user.json"), "{broken SECRET");
        run("hello\n/exit\n", WorkspaceBinding.none());
        assertTrue(errors.toString().contains("记忆未加载")); assertFalse(errors.toString().contains("SECRET"));
        assertEquals("{broken SECRET", Files.readString(root.resolve("data/memory/user.json")));
        assertFalse(requests.get(0).messages().toString().contains("SECRET"));
    }
}
