package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实临时文件验证重启恢复、单写者、版本冲突、损坏隔离和 dirty 重试。 */
class JsonSessionStoreTest {
    @TempDir Path directory;

    /** 中文与消息顺序往返保持一致，恢复只包含一条 system，快照不含凭证。 */
    @Test
    void completedConversationSurvivesNewStoreAndRestoresOneSystem() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "基础提示词", "手动标题")) {
            id = service.snapshot().id();
            service.session().completeTurn("第一问", Message.assistant("第一答"));
            service.captureTurn();
            service.save();
        }
        JsonSessionStore reopened = new JsonSessionStore(directory);
        try (SessionService service = SessionService.resume(reopened, id, "other")) {
            assertEquals(List.of(Message.system("基础提示词"), Message.user("第一问"), Message.assistant("第一答")),
                    service.session().messages());
            service.save();
        }
        assertEquals("other", reopened.load(id).profileAlias());
        String json = Files.readString(directory.resolve("sessions").resolve(id).resolve("session.json"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("BASE_URL"));
        assertEquals(1, reopened.list().sessions().size());
    }

    /** 同一会话第二个写者失败，释放锁后可再次恢复。 */
    @Test
    void onlyOneWriterMayHoldSession() {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", null)) {
            id = service.snapshot().id();
            assertThrows(SessionException.class, () -> store.lock(id, false));
            assertEquals(id, store.load(id).id());
        }
        try (SessionStore.Lease lease = store.lock(id, false)) {
            assertEquals(id, lease.load().id());
        }
    }

    /** 外部改变 revision 后旧持锁对象不会覆盖更新的数据。 */
    @Test
    void staleRevisionNeverOverwritesExternalUpdate() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "title")) {
            Path file = directory.resolve("sessions").resolve(service.snapshot().id()).resolve("session.json");
            String changed = Files.readString(file).replace("\"revision\" : 0", "\"revision\" : 7");
            Files.writeString(file, changed);
            service.setTitle("new", SessionSnapshot.TitleSource.MANUAL, true);
            assertThrows(SessionException.class, service::save);
            assertTrue(service.dirty());
            assertEquals(7, store.load(service.snapshot().id()).revision());
            assertEquals("title", store.load(service.snapshot().id()).title());
        }
    }

    /** 单个损坏记录不影响其他会话，错误中不回显文件正文。 */
    @Test
    void corruptedSessionIsIsolatedAndNeverReplaced() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String bad;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "good")) {
            service.session().completeTurn("question", Message.assistant("answer"));
            service.captureTurn(); service.save();
        }
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "bad")) {
            bad = service.snapshot().id();
        }
        Path file = directory.resolve("sessions").resolve(bad).resolve("session.json");
        Files.writeString(file, "{ fixture-secret }");
        SessionStore.Listing listing = store.list();
        assertEquals(1, listing.sessions().size());
        assertEquals(1, listing.warnings().size());
        assertFalse(listing.warnings().get(0).contains("fixture-secret"));
        assertThrows(SessionException.class, () -> SessionService.resume(store, bad, "fixture"));
        assertEquals("{ fixture-secret }", Files.readString(file));
    }

    /** 不支持版本、重复字段、尾随数据与残缺消息均不进入恢复流程。 */
    @Test
    void rejectsUnknownVersionDuplicateFieldsAndTrailingData() throws Exception {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "title")) {
            id = service.snapshot().id();
        }
        Path file = directory.resolve("sessions").resolve(id).resolve("session.json");
        String original = Files.readString(file);
        for (String invalid : List.of(original.replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 2"),
                original.replace("\"revision\" : 0", "\"revision\" : 0, \"revision\":0"), original + " {}")) {
            Files.writeString(file, invalid);
            assertThrows(SessionException.class, () -> store.load(id));
        }
    }

    /** 用户标题不会参与路径定位；加载仅接受 UUID。 */
    @Test
    void refusesPathSelectorsAndHidesEmptySessions() {
        JsonSessionStore store = new JsonSessionStore(directory);
        try (SessionService ignored = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "title")) {
            assertTrue(store.list().sessions().isEmpty());
        }
        assertThrows(SessionException.class, () -> store.load("../outside"));
        assertThrows(SessionException.class, () -> store.lock("../outside", true));
    }

    /** 模拟一次保存失败，完整内存回答仍在，重试只保存一次轮次。 */
    @Test
    void failedSaveRetainsResultForRetry() {
        JsonSessionStore real = new JsonSessionStore(directory);
        SessionStore flaky = new SessionStore() {
            public Listing list() { return real.list(); }
            public SessionSnapshot load(String id) { return real.load(id); }
            public Lease lock(String id, boolean create) {
                Lease delegate = real.lock(id, create);
                return new Lease() {
                    boolean failed;
                    public SessionSnapshot load() { return delegate.load(); }
                    public void save(SessionSnapshot snapshot) {
                        if (snapshot.revision() > 0 && !failed) {
                            failed = true;
                            throw new SessionException("fixture write failure");
                        }
                        delegate.save(snapshot);
                    }
                    public void close() { delegate.close(); }
                };
            }
        };
        try (SessionService service = SessionService.create(flaky, WorkspaceBinding.none(), "fixture", "base", "title")) {
            service.session().completeTurn("q", Message.assistant("a"));
            service.captureTurn();
            assertThrows(SessionException.class, service::save);
            assertTrue(service.dirty());
            assertEquals(3, service.session().messages().size());
            service.save();
            assertFalse(service.dirty());
            assertEquals(2, real.load(service.snapshot().id()).messages().size());
        }
    }

    /** 生成中断的标记恢复为回退状态，不重新调用标题模型。 */
    @Test
    void pendingTitleAfterCrashFallsBackOnResume() {
        JsonSessionStore store = new JsonSessionStore(directory);
        String id;
        try (SessionService service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", null)) {
            id = service.snapshot().id();
            service.session().completeTurn("first", Message.assistant("answer"));
            service.captureTurn();
            service.setTitle("first", SessionSnapshot.TitleSource.PENDING, true);
            service.save();
        }
        try (SessionService service = SessionService.resume(store, id, "fixture")) {
            assertEquals(SessionSnapshot.TitleSource.FALLBACK, service.snapshot().titleSource());
            assertTrue(service.snapshot().titleGenerationAttempted());
            service.save();
        }
    }
}
