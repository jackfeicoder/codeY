package io.github.jackfeicoder.codey.memory;

import io.github.jackfeicoder.codey.config.MemorySettings;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** 管理作用域、重复检测、整条更新和有界召回；不自动提取或向量化对话。 */
public final class MemoryService {
    /** 存储依赖不接触模型，替换实现不改变业务语义。 */
    private final MemoryStore store;
    /** 常驻、召回与条目数量的独立上限。 */
    private final MemorySettings settings;
    /** 首版BM25，后续可替换混合检索。 */
    private final MemoryRetriever retriever;
    public MemoryService(MemoryStore store, MemorySettings settings) { this(store, settings, new Bm25MemoryRetriever()); }
    public MemoryService(MemoryStore store, MemorySettings settings, MemoryRetriever retriever) {
        this.store = Objects.requireNonNull(store); this.settings = Objects.requireNonNull(settings);
        this.retriever = Objects.requireNonNull(retriever);
    }
    /** 工作区真实路径的摘要；Windows忽略大小写。移动项目会产生新标识，首版不自动合并worktree。 */
    public static String projectId(WorkspaceBinding workspace) {
        if (workspace.mode() != WorkspaceBinding.Mode.DIRECTORY) return null;
        try {
            String path = Path.of(workspace.path()).toRealPath().toString();
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) path = path.toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failed) { throw new MemoryException("项目工作区不可用，记忆作用域未确定。"); }
    }
    /** 只返回用户记忆与当前项目记忆。未绑定工作区时不会遍历其他项目。 */
    public List<MemoryEntry> list(WorkspaceBinding workspace) {
        var entries = new ArrayList<>(store.load(MemoryScope.USER, null));
        String project = projectId(workspace);
        if (project != null) entries.addAll(store.load(MemoryScope.PROJECT, project));
        return List.copyOf(entries);
    }
    /** updateId为空为新增；更新必须使用同作用域的完整ID，不根据相似度覆盖。 */
    public MemoryEntry remember(WorkspaceBinding workspace, MemoryScope scope, MemoryLoading loading,
                                String title, String content, List<String> tags, String sessionId, String updateId) {
        String project = scope == MemoryScope.PROJECT ? projectId(workspace) : null;
        if (scope == MemoryScope.PROJECT && project == null) throw new MemoryException("项目记忆需要工作区；用户级请显式指定 --user。");
        if (scope == MemoryScope.PROJECT && loading == MemoryLoading.ALWAYS) throw new MemoryException("项目常驻规则请写入 AGENTS.md，不在记忆库重复保存。");
        if (updateId != null && !MemoryEntry.validId(updateId)) throw new MemoryException("更新需要完整记忆 ID。");
        if (content == null || content.isBlank()) throw new MemoryException("记忆内容为空。");
        String clean = content.strip();
        String display = title == null ? clean.substring(0, clean.offsetByCodePoints(0, Math.min(48, clean.codePointCount(0, clean.length())))) : title;
        String id = updateId == null ? UUID.randomUUID().toString() : updateId;
        var selected = new MemoryEntry[1];
        store.update(scope, project, existing -> {
            var entries = new ArrayList<>(existing);
            MemoryEntry old = null;
            if (updateId != null) old = entries.stream().filter(e -> e.id().equals(updateId)).findFirst()
                    .orElseThrow(() -> new MemoryException("当前作用域没有此记忆 ID。"));
            for (var entry : entries) {
                if (!entry.id().equals(id) && normalize(entry.content()).equals(normalize(clean)))
                    throw new MemoryException("已存在相同内容，记忆 ID：" + entry.id() + "；请使用 --update 更新。");
            }
            String now = Instant.now().toString();
            var entry = new MemoryEntry(id, scope, project, loading, display, clean, tags, sessionId,
                    old == null ? now : old.createdAt(), now);
            entries.removeIf(e -> e.id().equals(id)); entries.add(entry);
            validateAlways(entries);
            selected[0] = entry;
            return entries;
        });
        return selected[0];
    }
    /** 删除范围仅限用户与当前项目，其他项目即使知道ID也不会被遍历或删除。 */
    public void forget(WorkspaceBinding workspace, String id) {
        if (!MemoryEntry.validId(id)) throw new MemoryException("删除需要完整记忆 ID。");
        var entry = list(workspace).stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new MemoryException("用户或当前项目中没有此记忆 ID。"));
        store.update(entry.scope(), entry.projectId(), existing -> {
            var copy = new ArrayList<>(existing);
            if (!copy.removeIf(e -> e.id().equals(id))) throw new MemoryException("记忆已被其他会话删除，请刷新。");
            return copy;
        });
    }
    /** 常驻不静默裁剪；剩余预算只约束按需条目，常驻本身超总预算交给上下文硬检查。 */
    public MemorySelection select(WorkspaceBinding workspace, String query, long availableTokens) {
        var entries = list(workspace);
        var always = entries.stream().filter(e -> e.loading() == MemoryLoading.ALWAYS).toList();
        validateAlways(entries);
        var base = new MemorySelection(always, List.of());
        long room = Math.min(settings.recallTokens(), Math.max(0, availableTokens - base.alwaysTokens()));
        room = Math.max(0, room - new io.github.jackfeicoder.codey.context.TokenEstimator().textTokens("\n[当前用户任务]\n"));
        var candidates = entries.stream().filter(e -> e.loading() == MemoryLoading.ON_DEMAND).toList();
        var result = new ArrayList<MemoryEntry>();
        var seen = new HashSet<String>(); always.forEach(e -> seen.add(normalize(e.content())));
        for (var hit : retriever.search(query, candidates)) {
            if (result.size() == settings.maxResults()) break;
            if (seen.contains(normalize(hit.entry().content()))) continue;
            var trial = new ArrayList<>(result); trial.add(hit.entry());
            if (new MemorySelection(List.of(), trial).recalledTokens() > room) continue;
            result.add(hit.entry()); seen.add(normalize(hit.entry().content()));
        }
        return new MemorySelection(always, result);
    }
    private void validateAlways(List<MemoryEntry> entries) {
        var pinned = entries.stream().filter(e -> e.loading() == MemoryLoading.ALWAYS).toList();
        if (new MemorySelection(pinned, List.of()).alwaysTokens() > settings.alwaysTokens())
            throw new MemoryException("用户常驻记忆超过 " + settings.alwaysTokens() + " token，请缩短或改为按需记忆；未静默截断。");
    }
    /** 仅做Unicode/空白/大小写归一化后的完全重复检测，不把语义相似当成相同。 */
    private static String normalize(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
