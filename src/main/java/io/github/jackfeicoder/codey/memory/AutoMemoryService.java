package io.github.jackfeicoder.codey.memory;

import io.github.jackfeicoder.codey.config.MemoryExtractionSettings;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** 成功持久化任务后的独立副流程；规则门控→单次提取→校验→仅新增按需记忆。 */
public final class AutoMemoryService {
    private final MemoryService memories;
    private final MemoryExtractionSettings settings;
    private final MemoryIntentDetector detector = new MemoryIntentDetector();
    private final MemoryExtractionJournal journal;
    private final MemoryExtractor extractor;
    public AutoMemoryService(MemoryService memories, Path dataDirectory, MemoryExtractionSettings settings) {
        this.memories = memories; this.settings = settings;
        journal = new MemoryExtractionJournal(dataDirectory); extractor = new MemoryExtractor(settings);
    }
    /** 摘要/提取正文不加入会话；展示信息由CLI清理控制字符。 */
    public record Outcome(boolean attempted, List<String> savedIds, List<MemoryCandidate> reviews, String detail) {
        public Outcome { savedIds = List.copyOf(savedIds); reviews = List.copyOf(reviews); }
    }
    public Outcome afterSavedTurn(WorkspaceBinding workspace, String sessionId, int messageCount, String input,
                                  LlmClient client, ContextBudgetPolicy budget, Consumer<String> progress) {
        if (!settings.enabled()) return skipped();
        var intent = detector.detect(input);
        if (!intent.triggered()) return skipped();
        // 未明确全局意图时，有工作区默认项目范围，防止项目知识被模型提升为用户级。
        if (intent.scopeHint() == null && workspace.mode() == WorkspaceBinding.Mode.DIRECTORY)
            intent = new MemoryIntentDetector.Intent(intent.kind(), intent.text(), MemoryScope.PROJECT);
        String key = MemoryExtractionJournal.turnKey(messageCount, input);
        var saved = new ArrayList<String>(); var reviews = new ArrayList<MemoryCandidate>();
        boolean claimed = false;
        try {
            if (!journal.claim(sessionId, key)) return skipped();
            claimed = true;
            progress.accept("[长期记忆] 识别到记忆意图，检查是否值得保存…");
            var selection = memories.select(workspace, intent.text(), 2000);
            var existing = new ArrayList<>(selection.always()); existing.addAll(selection.recalled());
            if (existing.size() > 8) existing = new ArrayList<>(existing.subList(0, 8));
            var candidates = extractor.extract(client, intent, workspace.mode() == WorkspaceBinding.Mode.DIRECTORY, existing, budget);
            int duplicates = 0;
            for (var candidate : candidates) {
                if (candidate.action() == MemoryCandidate.Action.REVIEW || intent.kind() == MemoryIntentDetector.Kind.CORRECTION
                        || intent.scopeHint() == null) reviews.add(candidate);
                else {
                    var entry = memories.rememberAutomatically(workspace, candidate, sessionId);
                    if (entry.isPresent()) saved.add(entry.get().id()); else duplicates++;
                }
            }
            journal.finish(sessionId, key, "COMPLETED", saved);
            return new Outcome(true, saved, reviews, "新增 " + saved.size() + " 条按需记忆；重复跳过 " + duplicates + " 条；待检查 " + reviews.size() + " 条。");
        } catch (RuntimeException failed) {
            if (claimed) try { journal.finish(sessionId, key, "FAILED", saved); } catch (RuntimeException ignored) { }
            String detail = failed instanceof MemoryException ? failed.getMessage() : "自动记忆处理失败，未继续执行。";
            return new Outcome(true, saved, reviews, detail + " 主任务已保存；已新增 " + saved.size() + " 条记忆，未自动重试。");
        }
    }
    private static Outcome skipped() { return new Outcome(false, List.of(), List.of(), ""); }
}
