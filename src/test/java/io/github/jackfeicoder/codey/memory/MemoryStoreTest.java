package io.github.jackfeicoder.codey.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实临时文件、文件锁和原子替换；损坏内容不覆盖，不读取用户数据。 */
class MemoryStoreTest {
    @TempDir Path root;
    static MemoryEntry entry(String content) {
        return new MemoryEntry(UUID.randomUUID().toString(), MemoryScope.USER, null, MemoryLoading.ON_DEMAND,
                "title", content, List.of("tag"), null, "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z");
    }
    @Test void absentReadHasNoSideEffectAndRoundTripIsImmutable() throws Exception {
        var store = new JsonMemoryStore(root);
        assertTrue(store.load(MemoryScope.USER, null).isEmpty()); assertFalse(Files.exists(root.resolve("memory")));
        var entry = entry("中文😀"); store.update(MemoryScope.USER, null, old -> List.of(entry));
        assertEquals(List.of(entry), new JsonMemoryStore(root).load(MemoryScope.USER, null));
        assertThrows(UnsupportedOperationException.class, () -> store.load(MemoryScope.USER, null).clear());
        assertEquals(2, new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("memory/user.json"))).path("schemaVersion").intValue());
    }
    @Test void separateInstancesReadLatestUnderLockInsteadOfOverwritingOldList() {
        var a = new JsonMemoryStore(root); var b = new JsonMemoryStore(root);
        var first = entry("a"); var second = entry("b");
        a.update(MemoryScope.USER, null, old -> List.of(first));
        b.update(MemoryScope.USER, null, old -> { var next = new ArrayList<>(old); next.add(second); return next; });
        assertEquals(List.of(first, second), a.load(MemoryScope.USER, null));
    }
    @Test void existingFileLockRejectsMutationWithoutChangingOriginal() throws Exception {
        var store = new JsonMemoryStore(root); store.update(MemoryScope.USER, null, old -> List.of(entry("a")));
        String original = Files.readString(root.resolve("memory/user.json"));
        try (var channel = FileChannel.open(root.resolve("memory/user.json.lock"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertThrows(MemoryException.class, () -> store.update(MemoryScope.USER, null, old -> List.of()));
        }
        assertEquals(original, Files.readString(root.resolve("memory/user.json")));
    }
    @Test void invalidMutationLeavesOriginalAndNoTempFiles() throws Exception {
        var store = new JsonMemoryStore(root); var item = entry("a");
        store.update(MemoryScope.USER, null, old -> List.of(item));
        var original = Files.readAllBytes(root.resolve("memory/user.json"));
        assertThrows(MemoryException.class, () -> store.update(MemoryScope.USER, null, old -> List.of(item, item)));
        assertArrayEquals(original, Files.readAllBytes(root.resolve("memory/user.json")));
        try (var files = Files.list(root.resolve("memory"))) { assertFalse(files.anyMatch(f -> f.toString().endsWith(".tmp"))); }
    }
    @Test void corruptVersionDuplicatesAndTrailingJsonAreRejectedWithoutOverwrite() throws Exception {
        Files.createDirectories(root.resolve("memory")); var file = root.resolve("memory/user.json");
        var store = new JsonMemoryStore(root);
        for (String json : List.of("null", "{broken SECRET", "{\"schemaVersion\":2,\"entries\":[]}",
                "{\"schemaVersion\":1,\"schemaVersion\":1,\"entries\":[]}", "{\"schemaVersion\":1,\"entries\":[]} {}")) {
            Files.writeString(file, json);
            var error = assertThrows(MemoryException.class, () -> store.load(MemoryScope.USER, null));
            assertFalse(error.getMessage().contains("SECRET"));
            assertThrows(MemoryException.class, () -> store.update(MemoryScope.USER, null, old -> List.of()));
            assertEquals(json, Files.readString(file));
        }
    }
    @Test void sizeAndBucketIdentityAreValidated() throws Exception {
        Files.createDirectories(root.resolve("memory"));
        Files.write(root.resolve("memory/user.json"), new byte[JsonMemoryStore.MAX_BYTES + 1]);
        var store = new JsonMemoryStore(root);
        assertThrows(MemoryException.class, () -> store.load(MemoryScope.USER, null));
        assertThrows(MemoryException.class, () -> store.load(MemoryScope.PROJECT, "../other"));
        assertThrows(MemoryException.class, () -> store.update(MemoryScope.PROJECT, "a".repeat(64), old -> List.of(entry("wrong scope"))));
    }
    @Test void entryRejectsProjectAlwaysAndInvalidMetadata() {
        var item = entry("text");
        assertThrows(MemoryException.class, () -> new MemoryEntry(item.id(), MemoryScope.PROJECT, "a".repeat(64),
                MemoryLoading.ALWAYS, "title", "text", List.of(), null, item.createdAt(), item.updatedAt()));
        assertThrows(MemoryException.class, () -> new MemoryEntry("../id", MemoryScope.USER, null,
                MemoryLoading.ON_DEMAND, "title", "text", List.of(), null, item.createdAt(), item.updatedAt()));
    }
}
