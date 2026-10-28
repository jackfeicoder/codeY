package io.github.jackfeicoder.codey.memory;

import com.hankcs.hanlp.collection.trie.DoubleArrayTrie;
import com.hankcs.hanlp.collection.trie.bintrie.BinTrie;
import com.hankcs.hanlp.dictionary.CoreDictionary;
import com.hankcs.hanlp.corpus.tag.Nature;
import com.hankcs.hanlp.dictionary.DynamicCustomDictionary;
import com.hankcs.hanlp.seg.Dijkstra.DijkstraSegment;
import com.hankcs.hanlp.seg.Segment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** HanLP Portable本地中文分词，加英文标识符拆分；领域词典独立实例、初始化后只读。 */
public final class HanlpMemoryTokenizer implements MemoryTokenizer {
    /** 有界处理单段输入；记忆正文最多8192字符，查询另由Retriever限制。 */
    public static final int MAX_TEXT_CODE_POINTS = 8192;
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+");
    /** 简单功能词过滤；不去掉否定词，避免丢失约束语义。 */
    private static final Set<String> STOP = Set.of("的", "了", "是", "在", "和", "与", "请", "把", "我", "你", "一下", "the", "a", "an", "to", "of", "is", "and");
    private final Segment segment;

    /** 延迟初始化共享的只读分词器；纯英文输入无需加载中文词典。 */
    public HanlpMemoryTokenizer() { segment = null; }
    /** 显式领域词便于测试与宿主扩展，不修改HanLP的全局CustomDictionary。 */
    public HanlpMemoryTokenizer(List<String> domainWords) { segment = createSegment(List.copyOf(domainWords)); }

    @Override public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        int count = Math.min(text.codePointCount(0, text.length()), MAX_TEXT_CODE_POINTS);
        String input = Normalizer.normalize(text.substring(0, text.offsetByCodePoints(0, count)), Normalizer.Form.NFKC)
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        var result = new ArrayList<String>();
        var matcher = WORD.matcher(input);
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                Segment selected = segment == null ? DefaultSegment.INSTANCE : segment;
                for (var term : selected.seg(word)) {
                    if (WORD.matcher(term.word).matches() && !STOP.contains(term.word)) result.add(term.word);
                }
            } else if (!STOP.contains(word)) result.add(word);
        }
        return List.copyOf(result);
    }

    /** 词图最短路径分词；索引模式保留词典中的子词，不再机械生成所有单字和双字。 */
    private static Segment createSegment(List<String> words) {
        var entries = new TreeMap<String, CoreDictionary.Attribute>();
        for (String word : words) {
            if (word == null || !word.matches("[\\p{IsHan}]{2,32}")) throw new MemoryException("记忆领域词必须是2..32个汉字。");
            entries.put(word, new CoreDictionary.Attribute(Nature.nz, 10000));
        }
        Segment result = new DijkstraSegment().enableAllNamedEntityRecognize(false).enablePartOfSpeechTagging(false)
                .enableCustomDictionary(false).enableIndexMode(2);
        if (!entries.isEmpty()) {
            var dat = new DoubleArrayTrie<CoreDictionary.Attribute>();
            dat.build(entries);
            var dictionary = new DynamicCustomDictionary(dat, new BinTrie<CoreDictionary.Attribute>(), null);
            result.enableCustomDictionary(dictionary).enableCustomDictionaryForcing(true);
        }
        return result;
    }

    /** 领域词随JAR打包，不扫描工作区、不写词典缓存文件。 */
    private static final class DefaultSegment {
        private static final Segment INSTANCE = createSegment(loadWords());
        private static List<String> loadWords() {
            try (var stream = HanlpMemoryTokenizer.class.getResourceAsStream("/memory/domain-words.txt")) {
                if (stream == null) throw new MemoryException("缺少记忆领域词典资源。");
                String text = new String(stream.readNBytes(65537), StandardCharsets.UTF_8);
                if (text.getBytes(StandardCharsets.UTF_8).length > 65536) throw new MemoryException("记忆领域词典资源超限。");
                var words = text.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
                if (words.size() > 1024) throw new MemoryException("记忆领域词典条目超限。");
                return words;
            } catch (IOException failed) { throw new MemoryException("读取记忆领域词典失败。"); }
        }
    }
}
