package io.github.jackfeicoder.codey.memory;

import java.time.Instant;
import java.util.*;

/** 本地 BM25（k1=1.2，b=0.75）；标题/标签加权。分词通过MemoryTokenizer隔离，默认HanLP。 */
public final class Bm25MemoryRetriever implements MemoryRetriever {
    /** 查询和所有候选字段共用同一分词器，替换分词无需调整排序逻辑。 */
    private final MemoryTokenizer tokenizer;
    private static final MemoryTokenizer DEFAULT_TOKENIZER = new HanlpMemoryTokenizer();
    public Bm25MemoryRetriever() { this(DEFAULT_TOKENIZER); }
    public Bm25MemoryRetriever(MemoryTokenizer tokenizer) { this.tokenizer = Objects.requireNonNull(tokenizer); }
    @Override public List<Hit> search(String query, List<MemoryEntry> candidates) {
        String boundedQuery = query == null ? "" : query.substring(0,
                query.offsetByCodePoints(0, Math.min(4096, query.codePointCount(0, query.length()))));
        var terms = new HashSet<>(tokenizer.tokenize(boundedQuery));
        if (terms.isEmpty() || candidates.isEmpty()) return List.of();
        var documents = new ArrayList<Map<String, Integer>>();
        var frequency = new HashMap<String, Integer>();
        long totalLength = 0;
        for (var entry : candidates) {
            var counts = new HashMap<String, Integer>();
            add(counts, entry.content(), 1); add(counts, entry.title(), 2);
            entry.tags().forEach(tag -> add(counts, tag, 3));
            documents.add(counts);
            counts.keySet().forEach(term -> frequency.merge(term, 1, Integer::sum));
            totalLength += counts.values().stream().mapToInt(Integer::intValue).sum();
        }
        double average = Math.max(1, (double) totalLength / candidates.size());
        var hits = new ArrayList<Hit>();
        for (int i = 0; i < candidates.size(); i++) {
            var counts = documents.get(i);
            double length = counts.values().stream().mapToInt(Integer::intValue).sum(), score = 0;
            for (String term : terms) {
                int tf = counts.getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = frequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (candidates.size() - df + 0.5) / (df + 0.5));
                score += idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * length / average));
            }
            if (score > 0) hits.add(new Hit(candidates.get(i), score));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed()
                .thenComparing(h -> Instant.parse(h.entry().updatedAt()), Comparator.reverseOrder())
                .thenComparing(h -> h.entry().id()));
        return List.copyOf(hits);
    }
    private void add(Map<String, Integer> counts, String text, int weight) {
        tokenizer.tokenize(text).forEach(t -> counts.merge(t, weight, Integer::sum));
    }
    /** 保留包内诊断入口；生产检索使用实例注入的tokenizer。 */
    static List<String> tokens(String text) { return DEFAULT_TOKENIZER.tokenize(text); }
}
