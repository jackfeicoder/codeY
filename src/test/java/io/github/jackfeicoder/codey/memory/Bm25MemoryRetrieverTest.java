package io.github.jackfeicoder.codey.memory;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Bm25MemoryRetrieverTest {
    @Test void chineseAndCamelCaseAreTokenizedAndUnrelatedIsExcluded() {
        var a = MemoryStoreTest.entry("线程池相关面试示例"); var b = MemoryStoreTest.entry("redis pool config");
        var retriever = new Bm25MemoryRetriever();
        assertEquals(a, retriever.search("解释线程池", List.of(a, b)).get(0).entry());
        assertTrue(retriever.search("astronomy", List.of(a, b)).isEmpty());
        assertTrue(Bm25MemoryRetriever.tokens("threadPool read_file").containsAll(List.of("thread", "pool", "read", "file")));
    }
    @Test void titleAndTagsOutrankBodyOnlyAndResultsAreStable() {
        var plain = MemoryStoreTest.entry("Java sample"); var raw = MemoryStoreTest.entry("other");
        var tagged = new MemoryEntry(raw.id(), raw.scope(), null, raw.loading(), "Java", "sample", List.of("Java"), null, raw.createdAt(), raw.updatedAt());
        var retriever = new Bm25MemoryRetriever();
        var first = retriever.search("Java", List.of(plain, tagged));
        assertEquals(tagged, first.get(0).entry()); assertEquals(first, retriever.search("Java", List.of(plain, tagged)));
    }
    @Test void emptyQueryAndStopWordsHaveNoHits() {
        var retriever = new Bm25MemoryRetriever(); var entries = List.of(MemoryStoreTest.entry("Java"));
        assertTrue(retriever.search("", entries).isEmpty()); assertTrue(retriever.search("the and", entries).isEmpty());
    }
}
