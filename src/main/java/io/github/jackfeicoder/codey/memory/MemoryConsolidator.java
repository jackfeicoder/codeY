package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.config.MemoryConsolidationSettings;
import io.github.jackfeicoder.codey.context.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.prompt.PromptLoader;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** 范围级模型整理：有界请求、持久水位门控、乐观版本校验和可恢复提交。 */
public final class MemoryConsolidator {
    private final JsonMemoryStore store;
    private final MemoryService memories;
    private final MemoryConsolidationSettings settings;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public MemoryConsolidator(JsonMemoryStore store, MemoryService memories, MemoryConsolidationSettings settings) {
        this(store, memories, settings, Clock.systemUTC());
    }
    public MemoryConsolidator(JsonMemoryStore store, MemoryService memories, MemoryConsolidationSettings settings, Clock clock) {
        this.store = store; this.memories = memories; this.settings = settings; this.clock = clock;
    }
    public record Outcome(boolean attempted, boolean changed, String detail, List<String> reviews) {
        public Outcome { reviews = List.copyOf(reviews); }
    }
    /** force忽略水位/冷却，但不绕过正在进行的整理、配置关闭及请求/数据验收。 */
    public Outcome tidy(WorkspaceBinding workspace, MemoryScope scope, boolean force, LlmClient client,
                        ContextBudgetPolicy budget, Consumer<String> progress) {
        String project = project(workspace, scope), runId = UUID.randomUUID().toString();
        if (!settings.enabled()) return skip("记忆整理已关闭。");
        JsonMemoryStore.Document claimed = null;
        try {
            var original = store.readDocument(scope, project);
            if (!eligible(original, force)) return skip("未达到整理条件、无新增内容、冷却中或已有整理在运行。");
            // 请求预算先检查，过大的范围不分块截断，也不写占位导致无效调用。
            LlmRequest request = request(original.entries(), budget);
            String print = fingerprint(original.entries());
            claimed = store.transact(scope, project, current -> {
                if (current.revision() != original.revision() || !eligible(current, force)) throw new MemoryException("记忆在整理开始前已变化或被其他整理占用。");
                var point = checkpoint(runId, current.entries(), true, print);
                return current.next(current.entries(), current.archived(), current.versions(), point);
            });
            progress.accept("[记忆整理] " + scope + " · " + original.entries().size() + " 条有效记忆，单次无工具整理…");
            var plan = generate(client, request);
            var applied = plan.apply(claimed.entries());
            memories.validateAlways(applied.entries());
            final var expected = claimed;
            store.transact(scope, project, current -> {
                if (current.revision() != expected.revision() || !fingerprint(current.entries()).equals(print))
                    throw new MemoryException("整理期间记忆已被修改，候选已放弃，请重新检查。");
                var archive = new ArrayList<>(current.archived()); archive.addAll(applied.archives());
                var versions = applied.changed() ? versionBefore(current, "模型整理") : current.versions();
                return current.next(applied.entries(), archive, versions, checkpoint(runId, applied.entries(), false, fingerprint(applied.entries())));
            });
            return new Outcome(true, applied.changed(), applied.changed() ? "整理已保存，可查看归档/版本并恢复。" : "未需改动，原条目保留。", applied.reviews());
        } catch (RuntimeException failed) {
            if (claimed != null) release(scope, project, runId);
            return new Outcome(claimed != null, false, failed instanceof MemoryException ? failed.getMessage() : "记忆整理失败；有效记忆保持原样。", List.of());
        }
    }
    private boolean eligible(JsonMemoryStore.Document document, boolean force) {
        long count = count(document.entries()), tokens = bodyTokens(document.entries());
        if (count == 0) return false;
        var point = document.checkpoint(); Instant now = clock.instant();
        if (point != null && point.running() && now.isBefore(Instant.parse(point.leaseUntil()))) return false;
        if (force) return true;
        if (count < settings.entryThreshold() && tokens < settings.tokenThreshold()) return false;
        if (point == null) return true;
        if (point.fingerprint().equals(fingerprint(document.entries()))) return false;
        if (Duration.between(Instant.parse(point.attemptedAt()), now).getSeconds() < settings.cooldownSeconds()) return false;
        return count - point.baselineCount() >= settings.minGrowth() || tokens - point.baselineTokens() >= 1024;
    }
    private LlmRequest request(List<MemoryEntry> entries, ContextBudgetPolicy budget) {
        int output = budget.maxOutputTokens() == null ? settings.maxOutputTokens() : (int)Math.min(settings.maxOutputTokens(), budget.maxOutputTokens());
        var payload = mapper.createObjectNode(); payload.set("entries", mapper.valueToTree(entries));
        var messages = List.of(Message.system(PromptLoader.loadMemoryTidy()), Message.user(payload.toString()));
        long tokens = messages.stream().mapToLong(new TokenEstimator()::messageBodyTokens).sum() + 11;
        Long available = budget.inputBudgetForOutput(output);
        if (tokens > settings.maxInputTokens() || available != null && tokens > available) throw new MemoryException("此范围超过整理请求预算，未截断或调用模型；可先手动清理或调整配置。");
        return new LlmRequest(messages, List.of(), false, output);
    }
    private MemoryTidyPlan generate(LlmClient client, LlmRequest request) {
        var pool = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "codey-memory-tidy"); t.setDaemon(true); return t; });
        Future<LlmResponse> task = pool.submit(() -> client.generate(request));
        try {
            var response = task.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
            if (response == null || !"stop".equals(response.finishReason()) || !response.toolCalls().isEmpty() || response.text() == null
                    || response.text().isBlank() || response.text().length() > 65536 || new TokenEstimator().textTokens(response.text()) > request.maxOutputTokens())
                throw new MemoryException("整理响应不完整、有工具调用或超限；未应用。");
            String text = response.text().strip();
            if (text.startsWith("```json\n") && text.endsWith("\n```")) text = text.substring(8, text.length()-4);
            try { var plan = mapper.readValue(text, MemoryTidyPlan.class); if (plan == null) throw new IllegalArgumentException(); return plan; }
            catch (Exception invalid) { throw new MemoryException("整理JSON结构无效；未应用。"); }
        } catch (TimeoutException timeout) { throw new MemoryException("记忆整理超时，已取消；原条目保留。"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new MemoryException("记忆整理已中断；原条目保留。"); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof LlmException error) {
                if (error.kind() == LlmException.Kind.INTERRUPTED) Thread.currentThread().interrupt();
                throw new MemoryException("整理模型调用失败：" + error.kind());
            }
            throw new MemoryException("整理模型调用失败。");
        } finally { task.cancel(true); pool.shutdownNow(); }
    }
    private void release(MemoryScope scope, String project, String runId) {
        try { store.transact(scope, project, current -> {
            var point = current.checkpoint();
            if (point == null || !point.runId().equals(runId)) return current;
            var done = new MemoryTidyCheckpoint(runId, point.attemptedAt(), point.leaseUntil(), false, point.fingerprint(), point.baselineCount(), point.baselineTokens());
            return current.next(current.entries(), current.archived(), current.versions(), done);
        }); } catch (RuntimeException ignored) { /* 占位有lease，异常或中断后可过期；不覆盖其他会话写入。 */ }
    }
    public List<MemoryArchive> archives(WorkspaceBinding workspace, MemoryScope scope) { return store.readDocument(scope, project(workspace,scope)).archived(); }
    public List<MemoryVersion> versions(WorkspaceBinding workspace, MemoryScope scope) { return store.readDocument(scope, project(workspace,scope)).versions(); }
    /** 恢复单次操作原文；替代条目已被手动改过时拒绝覆盖。恢复本身也保留前版本。 */
    public void restoreArchive(WorkspaceBinding workspace, MemoryScope scope, String id) {
        if (!MemoryEntry.validId(id)) throw new MemoryException("恢复需要完整归档ID。");
        store.transact(scope, project(workspace,scope), current -> {
            var archive = current.archived().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(() -> new MemoryException("当前范围没有此归档ID。"));
            var active = new LinkedHashMap<String,MemoryEntry>(); current.entries().forEach(e -> active.put(e.id(),e));
            if (!archive.replacement().equals(active.get(archive.replacement().id()))) throw new MemoryException("归档后的替代条目已变化或删除，拒绝覆盖；可查看范围版本。");
            if (!archive.kind().equals("ARCHIVE")) active.remove(archive.replacement().id());
            for (var entry : archive.originals()) if (active.putIfAbsent(entry.id(),entry) != null) throw new MemoryException("恢复ID已被占用，拒绝覆盖。");
            var entries = new ArrayList<>(active.values()); memories.validateAlways(entries);
            var archives = new ArrayList<>(current.archived()); archives.removeIf(a -> a.id().equals(id));
            return current.next(entries, archives, versionBefore(current,"恢复归档"), checkpoint(UUID.randomUUID().toString(),entries,false,fingerprint(entries)));
        });
    }
    /** 显式回滚整个作用域，包括归档；会撤销版本之后的修改，不影响其他项目。 */
    public void rollback(WorkspaceBinding workspace, MemoryScope scope, String id) {
        if (!MemoryEntry.validId(id)) throw new MemoryException("回滚需要完整版本ID。");
        store.transact(scope, project(workspace,scope), current -> {
            var version = current.versions().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> new MemoryException("当前范围没有此版本ID。"));
            memories.validateAlways(version.entries());
            return current.next(version.entries(), version.archived(), versionBefore(current,"范围回滚"),
                    checkpoint(UUID.randomUUID().toString(),version.entries(),false,fingerprint(version.entries())));
        });
    }
    private List<MemoryVersion> versionBefore(JsonMemoryStore.Document current, String reason) {
        var versions = new ArrayList<>(current.versions());
        versions.add(new MemoryVersion(UUID.randomUUID().toString(), clock.instant().toString(), reason, current.entries(),current.archived()));
        if (versions.size() > JsonMemoryStore.MAX_VERSIONS) versions.remove(0);
        return List.copyOf(versions);
    }
    private MemoryTidyCheckpoint checkpoint(String runId, List<MemoryEntry> entries, boolean running, String hash) {
        Instant now = clock.instant();
        return new MemoryTidyCheckpoint(runId, now.toString(), now.plusSeconds(settings.timeoutSeconds()+60L).toString(),running,hash,(int)count(entries),bodyTokens(entries));
    }
    private static long count(List<MemoryEntry> entries) { return entries.stream().filter(e -> e.loading() == MemoryLoading.ON_DEMAND).count(); }
    private static long bodyTokens(List<MemoryEntry> entries) { return entries.stream().filter(e -> e.loading() == MemoryLoading.ON_DEMAND).mapToLong(e -> new TokenEstimator().textTokens(e.content())).sum(); }
    private String fingerprint(List<MemoryEntry> entries) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(entries))); }
        catch (Exception failed) { throw new MemoryException("记忆版本摘要计算失败。"); }
    }
    private static String project(WorkspaceBinding workspace, MemoryScope scope) {
        if (scope == MemoryScope.USER) return null;
        String id = MemoryService.projectId(workspace); if (id == null) throw new MemoryException("项目整理需要绑定工作区。"); return id;
    }
    private static Outcome skip(String reason) { return new Outcome(false,false,reason,List.of()); }
}
