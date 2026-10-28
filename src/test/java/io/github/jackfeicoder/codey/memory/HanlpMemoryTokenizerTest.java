package io.github.jackfeicoder.codey.memory;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实Portable词典验证中文词语、领域词隔离、英文与Unicode边界。 */
class HanlpMemoryTokenizerTest {
    private final MemoryTokenizer tokenizer = new HanlpMemoryTokenizer();
    @Test void domainTermsRemainWholeAndDoNotGenerateAllCharacterFragments() {
        var tokens = tokenizer.tokenize("解释一下线程池与进程池");
        assertTrue(tokens.containsAll(List.of("线程池", "进程池")), tokens.toString());
        assertFalse(tokens.contains("池")); assertFalse(tokens.contains("程池"));
        assertFalse(tokens.contains("线"));
        assertTrue(tokenizer.tokenize("上下文管理与工具调用").containsAll(List.of("上下文", "工具调用")));
    }
    @Test void threadPoolDoesNotRecallProcessPoolOnlyBecauseOfSharedCharacters() {
        var thread = MemoryStoreTest.entry("线程池"); var process = MemoryStoreTest.entry("进程池");
        var retriever = new Bm25MemoryRetriever(tokenizer);
        assertTrue(retriever.search("线程池", List.of(process)).isEmpty());
        assertEquals(List.of(thread), retriever.search("线程池", List.of(thread, process)).stream().map(MemoryRetriever.Hit::entry).toList());
    }
    @Test void standardChineseDictionaryAndDomainDictionaryBothParticipate() {
        assertTrue(tokenizer.tokenize("中华人民共和国").contains("中华人民共和国"));
        assertTrue(tokenizer.tokenize("权限审批和依赖注入").containsAll(List.of("权限审批", "依赖注入")));
    }
    @Test void normalizationAndEnglishIdentifiersAreKept() {
        assertEquals(List.of("java", "xml", "http", "request", "thread", "pool", "read", "file", "2"),
                tokenizer.tokenize("Ｊａｖａ XMLHttpRequest threadPool read_file_2"));
    }
    @Test void emptyPunctuationStopWordsAndSupplementaryCharactersDoNotProduceBrokenTokens() {
        assertTrue(tokenizer.tokenize(null).isEmpty()); assertTrue(tokenizer.tokenize(" ").isEmpty());
        assertTrue(tokenizer.tokenize("😀 !!! ").isEmpty());
        assertTrue(tokenizer.tokenize("的 了 是 和 the and").isEmpty());
        for (String token : tokenizer.tokenize("𠀀😀线程池")) {
            assertTrue(token.codePoints().allMatch(cp -> cp < 0xd800 || cp > 0xdfff));
        }
        assertTrue(tokenizer.tokenize("𠀀😀线程池").contains("线程池"));
    }
    @Test void customWordsArePerInstanceAndDoNotMutateGlobalDefault() {
        var before = tokenizer.tokenize("甲乙丙丁戊");
        var custom = new HanlpMemoryTokenizer(List.of("甲乙丙丁戊"));
        assertTrue(custom.tokenize("甲乙丙丁戊").contains("甲乙丙丁戊"));
        assertEquals(before, tokenizer.tokenize("甲乙丙丁戊"));
        assertThrows(MemoryException.class, () -> new HanlpMemoryTokenizer(List.of("../词典")));
    }
    @Test void repeatedOccurrencesAreRetainedForBm25AndInputIsBounded() {
        assertEquals(2, tokenizer.tokenize("线程池 线程池").stream().filter("线程池"::equals).count());
        var result = tokenizer.tokenize("x".repeat(10000));
        assertEquals(1, result.size()); assertEquals(HanlpMemoryTokenizer.MAX_TEXT_CODE_POINTS, result.get(0).length());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }
    @Test void sharedReadOnlySegmenterCanBeCalledConcurrently() throws Exception {
        var expected = tokenizer.tokenize("线程池与上下文管理");
        var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<List<String>>>();
            for (int i = 0; i < 24; i++) work.add(() -> tokenizer.tokenize("线程池与上下文管理"));
            for (var future : pool.invokeAll(work)) assertEquals(expected, future.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void bm25UsesTheInjectedTokenizerForBothQueryAndDocuments() {
        var retriever = new Bm25MemoryRetriever(text -> List.of("same"));
        assertEquals(1, retriever.search("unrelated query", List.of(MemoryStoreTest.entry("different body"))).size());
    }
}
