package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.SessionSettings;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实临时存储与替代模型验证全局入口、恢复、命令、标题和失败处理。 */
class SessionLauncherTest {
    @TempDir Path directory;

    /** 自动标题独立请求一次，手动改名后重启按标题恢复，模型看到完整历史。 */
    @Test
    void automaticTitleRenameAndResumePreserveIdentityAndHistory() throws Exception {
        Harness first = new Harness(new JsonSessionStore(directory), true);
        assertEquals(0, first.run("/new\n3\n\nfirst\nsecond\n/rename 用户标题\n/exit\n", emptyOptions()));
        assertEquals(2, first.requests.size());
        assertEquals(1, first.titleCalls);
        SessionSnapshot saved = first.store.list().sessions().get(0);
        assertEquals("用户标题", saved.title());
        assertEquals(SessionSnapshot.TitleSource.MANUAL, saved.titleSource());
        assertEquals(4, saved.messages().size());
        Harness resumed = new Harness(new JsonSessionStore(directory), true);
        assertEquals(0, resumed.run("third\n/exit\n", new SessionLauncher.Options(null, false, null, "用户标题", null)));
        assertEquals(saved.id(), resumed.store.list().sessions().get(0).id());
        assertEquals(6, resumed.requests.get(0).messages().size());
        assertEquals(Message.user("third"), resumed.requests.get(0).messages().get(5));
        assertEquals(0, resumed.titleCalls);
    }

    /** 所有工作区一起展示，前 10 条和后续批次稳定编号，列表不依赖模型。 */
    @Test
    void globalPaginationIsStableAndNeverCreatesModel() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        for (int index = 0; index < 23; index++) {
            seed(store, "title-" + index, index % 2 == 0 ? WorkspaceBinding.none()
                    : new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, workspace.toRealPath().toString()));
        }
        Harness harness = new Harness(store, false);
        harness.run("/more_session\n/more_session\n/more_session\n/exit\n", emptyOptions());
        assertEquals(0, harness.modelChoices);
        String text = harness.output.toString();
        assertTrue(text.contains("已显示 10 / 23 条"));
        assertTrue(text.contains("已显示 20 / 23 条"));
        assertTrue(text.contains("已显示 23 / 23 条"));
        assertTrue(text.contains("没有更多会话"));
        assertTrue(text.contains("普通聊天"));
        assertTrue(text.contains(workspace.toRealPath().toString()));
        assertEquals(23, text.replace("选择会话> ", "").lines()
                .filter(line -> line.matches("\\d+ \\| .*" )).count());
    }

    /** 重名时展示候选项，不任意加载；完整 ID 可明确恢复。 */
    @Test
    void duplicateTitlesRequireExplicitId() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String first = seed(store, "同名", WorkspaceBinding.none());
        String second = seed(store, "同名", WorkspaceBinding.none());
        Harness harness = new Harness(store, false);
        harness.run("同名\n" + first + "\n/exit\n", emptyOptions());
        assertEquals(1, harness.modelChoices);
        assertTrue(harness.output.toString().contains("匹配多个会话"));
        assertTrue(harness.output.toString().contains(second));
    }

    /** 短 ID 可命中第一页以外的会话，问题与其他标题相同仍按普通问题发送。 */
    @Test
    void idSelectionAndActiveChatDoNotConfuseTitlesWithQuestions() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id = seed(store, "oldest", WorkspaceBinding.none());
        for (int index = 0; index < 11; index++) seed(store, "title-" + index, WorkspaceBinding.none());
        Harness harness = new Harness(store, false);
        harness.run(id.substring(0, 8) + "\ntitle-1\n/exit\n", emptyOptions());
        assertEquals(1, harness.requests.size());
        assertEquals(Message.user("title-1"), harness.requests.get(0).messages().get(3));
        assertEquals(4, store.load(id).messages().size());
    }

    /** /clear 使用新 ID，旧会话保留，新会话不带旧历史；/new 可重新选工作区。 */
    @Test
    void clearAndNewRetainOldSessions() throws Exception {
        Harness harness = new Harness(new JsonSessionStore(directory), false);
        harness.run("first\n/clear\nsecond\n/new\n1\n第三个\nthird\n/exit\n",
                new SessionLauncher.Options(null, true, "第一个", null, null));
        assertEquals(3, harness.store.list().sessions().size());
        assertEquals(3, harness.requests.size());
        assertTrue(harness.requests.stream().allMatch(r -> r.messages().size() == 2));
        assertEquals(0, harness.titleCalls);
        assertTrue(harness.store.list().sessions().stream()
                .anyMatch(s -> s.workspace().mode() == WorkspaceBinding.Mode.DIRECTORY));
    }

    /** 人工标题优先，不调用标题模型；失败的自动标题只尝试一次。 */
    @Test
    void manualTitlesSkipGenerationAndFailedAutomaticTitlesFallBackOnce() throws Exception {
        Harness manual = new Harness(new JsonSessionStore(directory.resolve("manual")), true);
        manual.run("q\n/exit\n", new SessionLauncher.Options(null, true, "自定义", null, null));
        assertEquals(0, manual.titleCalls);
        Harness failed = new Harness(new JsonSessionStore(directory.resolve("failed")), true);
        failed.titleFails = true;
        failed.run("\n首问\n第二问\n/exit\n", new SessionLauncher.Options(null, true, null, null, null));
        assertEquals(1, failed.titleCalls);
        SessionSnapshot snapshot = failed.store.list().sessions().get(0);
        assertEquals("首问", snapshot.title());
        assertEquals(SessionSnapshot.TitleSource.FALLBACK, snapshot.titleSource());
        assertEquals(4, snapshot.messages().size());
    }

    /** 失效工作区可以预览历史，但不静默改为启动目录或建立模型连接。 */
    @Test
    void missingWorkspaceShowsHistoryWithoutModelSelection() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        Path workspace = Files.createDirectory(directory.resolve("gone"));
        String id = seed(store, "原项目", new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, workspace.toRealPath().toString()));
        Files.delete(workspace);
        Harness harness = new Harness(store, false);
        harness.run("/exit\n", new SessionLauncher.Options(null, false, null, id, null));
        assertEquals(0, harness.modelChoices);
        assertTrue(harness.output.toString().contains("原工作区已失效"));
        assertTrue(harness.output.toString().contains("answer"));
    }

    /** 旧 Profile 无效时用户明确输入另一个配置，不替换原凭证，也不重发上次问题。 */
    @Test
    void missingProfileRequiresExplicitSelection() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "missing", "base", "title")) {
            id = service.snapshot().id();
            service.session().completeTurn("q", Message.assistant("answer"));
            service.captureTurn(); service.save();
        }
        Harness harness = new Harness(store, false);
        harness.run("fixture\n/exit\n", new SessionLauncher.Options(null, false, null, id, null));
        assertEquals(2, harness.modelChoices);
        assertTrue(harness.requests.isEmpty());
        assertEquals("fixture", store.load(id).profileAlias());
    }

    /** EOF 不创建隐式会话，打开列表与改名也不请求模型。 */
    @Test
    void emptyInputListsHistoryAndExitsWithoutModelOrSession() throws Exception {
        Harness harness = new Harness(new JsonSessionStore(directory), true);
        assertEquals(0, harness.run("", emptyOptions()));
        assertEquals(0, harness.modelChoices);
        assertTrue(harness.store.list().sessions().isEmpty());
    }

    /** 保存持续失败时阻止下一轮调用，退出明确失败，不丢弃 dirty 状态后继续工作。 */
    @Test
    void writeFailurePreventsFurtherModelCallsAndReturnsFailureOnExit() throws Exception {
        JsonSessionStore real = new JsonSessionStore(directory);
        SessionStore failing = new SessionStore() {
            public Listing list() { return real.list(); }
            public SessionSnapshot load(String id) { return real.load(id); }
            public Lease lock(String id, boolean create) {
                Lease delegate = real.lock(id, create);
                return new Lease() {
                    public SessionSnapshot load() { return delegate.load(); }
                    public void save(SessionSnapshot snapshot) {
                        if (snapshot.revision() > 0) throw new SessionException("fixture storage unavailable");
                        delegate.save(snapshot);
                    }
                    public void close() { delegate.close(); }
                };
            }
        };
        Harness harness = new Harness(failing, false);
        assertEquals(1, harness.run("first\nsecond\n/exit\n",
                new SessionLauncher.Options(null, true, "title", null, null)));
        assertEquals(1, harness.requests.size());
        assertTrue(harness.errors.toString().contains("未保存"));
        assertTrue(real.list().sessions().isEmpty());
    }

    /** /resume 的参数可用纯数字标题，浏览模式的裸数字仍表示列表序号。 */
    @Test
    void numericTitleCanBeSelectedExplicitlyAndEofInsideNewDoesNotLoop() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        seed(store, "123", WorkspaceBinding.none());
        Harness harness = new Harness(store, false);
        harness.run("/resume 123\n/exit\n", emptyOptions());
        assertEquals(1, harness.modelChoices);
        Harness eof = new Harness(store, false);
        assertEquals(0, eof.run("/new\n", emptyOptions()));
        assertEquals(0, eof.modelChoices);
    }

    /** 空会话不在默认列表，但用户已知完整 ID 时仍可恢复后开始第一轮。 */
    @Test
    void knownFullIdCanRestoreEmptySession() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "empty")) {
            id = service.snapshot().id();
        }
        Harness harness = new Harness(store, false);
        harness.run("first\n/exit\n", new SessionLauncher.Options(null, false, null, id, null));
        assertEquals(1, harness.requests.size());
        assertEquals(2, store.load(id).messages().size());
    }

    private String seed(SessionStore store, String title, WorkspaceBinding workspace) {
        try (SessionService service = SessionService.create(store, workspace, "fixture", "base", title)) {
            service.session().completeTurn("question", Message.assistant("answer"));
            service.captureTurn(); service.save();
            return service.snapshot().id();
        }
    }

    private static SessionLauncher.Options emptyOptions() {
        return new SessionLauncher.Options(null, false, null, null, null);
    }

    /** 可检查调用次数与 wire 前消息的终端替身。 */
    private final class Harness {
        private final SessionStore store;
        private final boolean titles;
        private final StringWriter output = new StringWriter();
        private final StringWriter errors = new StringWriter();
        private final List<LlmRequest> requests = new ArrayList<>();
        private int titleCalls;
        private int modelChoices;
        private boolean titleFails;

        private Harness(SessionStore store, boolean titles) { this.store = store; this.titles = titles; }

        private int run(String text, SessionLauncher.Options options) throws Exception {
            return new SessionLauncher(directory, new SessionSettings(directory, null, titles), store, profile -> {
                modelChoices++;
                if ("missing".equals(profile)) throw new ConfigException("fixture profile missing");
                return new SessionLauncher.ModelChoice(profile == null ? "fixture" : profile, request -> {
                    requests.add(request);
                    return new LlmResponse(Message.assistant("answer"), "stop", null);
                }, request -> {
                    titleCalls++;
                    return new LlmResponse(Message.assistant(titleFails ? "two\nlines" : "模型标题"), "stop", null);
                });
            }, () -> "base", new BufferedReader(new StringReader(text)), new PrintWriter(output), new PrintWriter(errors), options).run();
        }
    }
}
