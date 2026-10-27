package io.github.jackfeicoder.codey.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** 固定摘要结构，字段区分事实、已完成变更与待办；结构验证不等于事实正确性验证。 */
public record SummaryDocument(List<String> goals, List<String> constraints, List<String> facts,
                              List<String> decisions, List<String> changes, List<String> verification,
                              List<String> openIssues, List<String> nextSteps) {
    /** 资源保护上限独立于动态 Token 预算，允许大窗口模型保存较丰富的摘要。 */
    public static final int MAX_CHARS = 1024 * 1024;
    public static final int MAX_ITEM_CHARS = 65536;
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SummaryDocument {
        goals = checked(goals); constraints = checked(constraints); facts = checked(facts); decisions = checked(decisions);
        changes = checked(changes); verification = checked(verification); openIssues = checked(openIssues); nextSteps = checked(nextSteps);
        var all = List.of(goals, constraints, facts, decisions, changes, verification, openIssues, nextSteps);
        if (all.stream().mapToInt(List::size).sum() == 0
                || all.stream().flatMap(List::stream).mapToLong(String::length).sum() > MAX_CHARS) throw invalid();
    }

    /** 接受纯 JSON 或一个完整 JSON 围栏；拒绝截断、尾随内容、重复键和未知字段。 */
    public static SummaryDocument parse(String text) {
        if (text == null || text.length() > MAX_CHARS) throw invalid();
        text = text.strip();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
        try {
            var tree = MAPPER.readTree(text);
            if (tree == null || !tree.isObject() || tree.size() != 8) throw invalid();
            for (String name : List.of("goals", "constraints", "facts", "decisions", "changes", "verification", "openIssues", "nextSteps")) {
                if (!tree.path(name).isArray()) throw invalid();
                for (var value : tree.path(name)) if (!value.isTextual()) throw invalid();
            }
            return MAPPER.treeToValue(tree, SummaryDocument.class);
        } catch (java.io.IOException | RuntimeException failed) { throw invalid(); }
    }

    /** 确定顺序的 JSON，用于后续请求和本地统计，展示层不直接输出全文。 */
    public String json() {
        try {
            String text = MAPPER.writeValueAsString(this);
            if (text.length() > MAX_CHARS) throw invalid();
            return text;
        } catch (java.io.IOException failed) { throw invalid(); }
    }
    private static List<String> checked(List<String> values) {
        if (values == null || values.size() > 64 || values.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > MAX_ITEM_CHARS)) throw invalid();
        return List.copyOf(values);
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid structured context summary."); }
}
