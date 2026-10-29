package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.context.TokenEstimator;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** 模型只提供操作列表；未提到的条目原样保留，不接受整份文件替换或任意路径。 */
public record MemoryTidyPlan(List<Operation> operations) {
    public enum Action { KEEP, MERGE, SHORTEN, ARCHIVE, REVIEW }
    public record Operation(Action action, List<String> ids, String title, String content, List<String> tags,
                            String reason, String supersededBy, String evidence) {
        public Operation {
            if (action == null || ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> !MemoryEntry.validId(id))
                    || new HashSet<>(ids).size() != ids.size() || reason == null || reason.isBlank() || reason.length() > 500)
                throw new MemoryException("整理操作结构无效。");
            ids = List.copyOf(ids);
            if (action == Action.MERGE || action == Action.SHORTEN) {
                if (title == null || title.isBlank() || title.length() > 120 || content == null || content.isBlank() || content.length() > 8192
                        || tags == null || tags.size() > 16 || tags.stream().anyMatch(t -> t == null || t.isBlank() || t.length() > 40))
                    throw new MemoryException("整理替换内容无效。");
                tags = List.copyOf(tags);
            } else if (title != null || content != null || tags != null) throw new MemoryException("此整理操作不接受替换正文。");
            if (action == Action.MERGE ? ids.size() < 2 : ids.size() != 1) throw new MemoryException("整理来源数量无效。");
            if (action == Action.ARCHIVE) {
                if (!MemoryEntry.validId(supersededBy) || ids.contains(supersededBy) || evidence == null || evidence.isBlank() || evidence.length() > 512)
                    throw new MemoryException("归档必须指定有效替代条目和原文依据。");
            } else if (supersededBy != null || evidence != null) throw new MemoryException("非归档操作不接受替代依据。");
        }
    }
    public MemoryTidyPlan {
        if (operations == null || operations.size() > 64 || operations.stream().anyMatch(Objects::isNull)) throw new MemoryException("整理方案数量无效。");
        operations = List.copyOf(operations);
    }
    public record Applied(List<MemoryEntry> entries, List<MemoryArchive> archives, List<String> reviews) {
        public Applied { entries = List.copyOf(entries); archives = List.copyOf(archives); reviews = List.copyOf(reviews); }
        public boolean changed() { return !archives.isEmpty(); }
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern REPLACED = Pattern.compile("已改为|已替换|不再使用|已废弃|已停用|已失效|已取消|已过期");

    /** 整组先验收，产生请求副本；所有来源均须来自本次范围快照。 */
    public Applied apply(List<MemoryEntry> originals) {
        if (!originals.isEmpty() && originals.stream().anyMatch(e -> e.scope() != originals.get(0).scope()
                || !Objects.equals(e.projectId(), originals.get(0).projectId()))) throw new MemoryException("不能跨范围整理记忆。");
        var source = new LinkedHashMap<String, MemoryEntry>(); originals.forEach(e -> source.put(e.id(), e));
        var remaining = new LinkedHashMap<>(source);
        var used = new HashSet<String>(); var newArchives = new ArrayList<MemoryArchive>(); var reviews = new ArrayList<String>();
        var changed = new HashSet<String>();
        for (var operation : operations) {
            var entries = new ArrayList<MemoryEntry>();
            for (String id : operation.ids()) {
                if (!source.containsKey(id) || !used.add(id)) throw new MemoryException("整理引用不存在、跨范围或重复操作的ID。");
                entries.add(source.get(id));
            }
            if (operation.action() == Action.KEEP) continue;
            if (operation.action() == Action.REVIEW || entries.stream().anyMatch(e -> e.loading() == MemoryLoading.ALWAYS)) {
                reviews.add(String.join(",", operation.ids()) + "：" + operation.reason() + "（未修改；请显式检查，常驻规则另行管理）"); continue;
            }
            String now = Instant.now().toString();
            if (operation.action() == Action.ARCHIVE) {
                var old = entries.get(0); var next = source.get(operation.supersededBy());
                if (next == null || !Instant.parse(next.updatedAt()).isAfter(Instant.parse(old.updatedAt()))
                        || !(old.content().contains(operation.evidence()) || next.content().contains(operation.evidence()))
                        || !REPLACED.matcher(operation.evidence()).find())
                    throw new MemoryException("归档缺少明确替代原文或较新的替代条目；不能仅因年代/未使用而归档。");
                remaining.remove(old.id());
                newArchives.add(new MemoryArchive(UUID.randomUUID().toString(), "ARCHIVE", operation.reason(), now, entries, next));
            } else {
                var first = entries.get(0);
                var replacement = new MemoryEntry(first.id(), first.scope(), first.projectId(), MemoryLoading.ON_DEMAND,
                        operation.title(), operation.content(), operation.tags(), first.sourceSessionId(), first.createdAt(), now);
                long before = entries.stream().mapToLong(MemoryTidyPlan::entryTokens).sum();
                if (entryTokens(replacement) >= before) throw new MemoryException("合并/精简没有减少估算占用，方案未应用。");
                entries.forEach(e -> remaining.remove(e.id())); remaining.put(replacement.id(), replacement); changed.add(replacement.id());
                newArchives.add(new MemoryArchive(UUID.randomUUID().toString(), operation.action().name(), operation.reason(), now, entries, replacement));
            }
        }
        // 归档依赖的替代条目必须仍是最终有效且未经本方案改写，避免隐式链式删除。
        for (var archive : newArchives) if (archive.kind().equals("ARCHIVE") && !archive.replacement().equals(remaining.get(archive.replacement().id())))
            throw new MemoryException("归档依赖的替代条目被本方案移除或改写。");
        for (String id : changed) {
            var entry = remaining.get(id);
            if (entry != null && remaining.values().stream().anyMatch(e -> !e.id().equals(id) && normalized(e.content()).equals(normalized(entry.content()))))
                throw new MemoryException("整理引入了与其他有效条目完全重复的内容。");
        }
        return new Applied(new ArrayList<>(remaining.values()), newArchives, reviews);
    }
    static long entryTokens(MemoryEntry entry) {
        try { return new TokenEstimator().textTokens(MAPPER.writeValueAsString(entry)); }
        catch (java.io.IOException failed) { throw new MemoryException("记忆估算失败。"); }
    }
    private static String normalized(String text) { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
