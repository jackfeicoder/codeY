package io.github.jackfeicoder.codey.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class MemoryExtractionJournalTest {
    @TempDir Path root;
    @Test void parallelClaimsAllowOnlyOneCallerAndFinishDoesNotStoreUserText() throws Exception {
        String id = UUID.randomUUID().toString(), key = MemoryExtractionJournal.turnKey(2, "PRIVATE_USER_TEXT");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var work = List.<Callable<Boolean>>of(() -> new MemoryExtractionJournal(root).claim(id, key), () -> new MemoryExtractionJournal(root).claim(id, key));
            int claims = 0;
            for (var result : pool.invokeAll(work)) if (result.get()) claims++;
            assertEquals(1, claims);
        } finally { pool.shutdownNow(); }
        var journal = new MemoryExtractionJournal(root);
        journal.finish(id, key, "COMPLETED", List.of(UUID.randomUUID().toString()));
        var text = Files.readString(root.resolve("memory/extractions").resolve(id).resolve(key + ".json"));
        assertFalse(text.contains("PRIVATE_USER_TEXT")); assertTrue(text.contains("COMPLETED")); assertFalse(journal.claim(id, key));
    }
    @Test void invalidIdentitiesAreRejectedAndDistinctTurnPositionsHaveDistinctKeys() {
        var journal = new MemoryExtractionJournal(root);
        assertThrows(MemoryException.class, () -> journal.claim("../id", "a".repeat(64)));
        assertNotEquals(MemoryExtractionJournal.turnKey(2, "same"), MemoryExtractionJournal.turnKey(4, "same"));
    }
}
