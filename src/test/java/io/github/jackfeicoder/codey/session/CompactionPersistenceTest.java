package io.github.jackfeicoder.codey.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.context.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 摘要与全量消息原子持久化、旧版本兼容、指纹及写入失败重试。 */
class CompactionPersistenceTest {
    @TempDir Path directory;
    private SummaryDocument summary() {
        return new SummaryDocument(List.of("goal"), List.of("keep constraint"), List.of("fact"), List.of(), List.of(), List.of(), List.of(), List.of("next"));
    }
    private void seed(SessionService service) {
        for (int i = 0; i < 5; i++) service.session().completeTurn("q" + i, Message.assistant("a" + i));
        service.captureTurn(); service.save();
    }
    private CompactionState state(Session session) {
        return new CompactionState(4, CompactionState.fingerprint(session.messages().subList(1, 5)), summary(), Instant.now().toString());
    }

    @Test void resumesVersionThreeWithFullRawMessagesAndSummaryAcrossRenameAndModelSwitch() {
        var store = new JsonSessionStore(directory); String id; List<Message> raw; CompactionState state;
        try (var service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "title")) {
            seed(service); id = service.snapshot().id(); raw = service.session().messages(); state = state(service.session());
            service.session().applyCompaction(state); service.captureCompaction(); service.save();
            assertEquals(3, service.snapshot().schemaVersion()); assertEquals(raw.subList(1, raw.size()), service.snapshot().messages());
            service.setTitle("renamed", SessionSnapshot.TitleSource.MANUAL, true); service.save(); service.switchProfile("other");
        }
        try (var service = SessionService.resume(store, id, "third")) {
            assertEquals(raw, service.session().messages()); assertEquals(state, service.session().compaction());
            assertEquals("renamed", service.snapshot().title());
            service.session().completeTurn("continue", Message.assistant("continued")); service.captureTurn(); service.save();
        }
        assertEquals(state, store.load(id).compaction()); assertEquals(12, store.load(id).messages().size());
    }

    @Test void diskTamperingCannotApplySummaryToAnotherHistoricalPrefix() throws Exception {
        var store = new JsonSessionStore(directory); String id;
        try (var service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "title")) {
            seed(service); id = service.snapshot().id(); service.session().applyCompaction(state(service.session())); service.captureCompaction(); service.save();
        }
        var path = directory.resolve("sessions").resolve(id).resolve("session.json");
        var mapper = new ObjectMapper(); var tree = mapper.readTree(Files.readString(path));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("messages").get(0)).put("content", "tampered secret-fixture");
        Files.writeString(path, tree.toString());
        var error = assertThrows(SessionException.class, () -> store.load(id)); assertFalse(error.getMessage().contains("secret-fixture"));
    }

    @Test void invalidCoverageIsRejectedAndClearRemovesDerivedState() {
        var session = new Session("base");
        for (int i = 0; i < 5; i++) session.completeTurn("q" + i, Message.assistant("a" + i));
        var good = state(session); session.applyCompaction(good);
        assertThrows(IllegalArgumentException.class, () -> session.applyCompaction(new CompactionState(3,
                CompactionState.fingerprint(session.messages().subList(1, 4)), summary(), Instant.now().toString())));
        assertSame(good, session.compaction());
        session.clear(); assertNull(session.compaction()); assertEquals(List.of(Message.system("base")), session.messages());
    }

    @Test void failedSummarySavePreservesDirtyStateAndOriginalFileUntilRetry() {
        var actual = new JsonSessionStore(directory); var fail = new AtomicBoolean();
        SessionStore store = new SessionStore() {
            public Listing list() { return actual.list(); }
            public SessionSnapshot load(String id) { return actual.load(id); }
            public Lease lock(String id, boolean create) {
                var delegate = actual.lock(id, create);
                return new Lease() {
                    public SessionSnapshot load() { return delegate.load(); }
                    public void save(SessionSnapshot snapshot) {
                        if (fail.get()) throw new SessionException("fixture save failure");
                        delegate.save(snapshot);
                    }
                    public void close() { delegate.close(); }
                };
            }
        };
        try (var service = SessionService.create(store, WorkspaceBinding.none(), "fixture", "base", "title")) {
            seed(service); var original = actual.load(service.snapshot().id()); var state = state(service.session());
            service.session().applyCompaction(state); service.captureCompaction(); fail.set(true);
            assertThrows(SessionException.class, service::save); assertTrue(service.dirty()); assertEquals(original, actual.load(original.id()));
            fail.set(false); service.save(); assertFalse(service.dirty());
            assertEquals(state, actual.load(original.id()).compaction()); assertEquals(original.messages(), actual.load(original.id()).messages());
        }
    }
}
