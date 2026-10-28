package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.config.MemoryExtractionSettings;
import io.github.jackfeicoder.codey.context.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.prompt.PromptLoader;
import io.github.jackfeicoder.codey.session.Message;
import java.util.*;
import java.util.concurrent.*;

/** 一次非流式、无工具、有输入/输出/总时限的专用请求；不提交业务对话历史。 */
public final class MemoryExtractor {
    private final MemoryExtractionSettings settings;
    private final JsonMapper mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public MemoryExtractor(MemoryExtractionSettings settings) { this.settings = settings; }
    public record Document(List<MemoryCandidate> memories) {
        public Document {
            if (memories == null || memories.size() > 3 || memories.stream().anyMatch(Objects::isNull)) throw new MemoryException("记忆候选数量无效。");
            memories = List.copyOf(memories);
        }
    }
    /** 只发送清理后的本轮用户话语和少量现有候选，不发送工具结果或模型猜测作为事实。 */
    public List<MemoryCandidate> extract(LlmClient client, MemoryIntentDetector.Intent intent, boolean hasWorkspace,
                                         List<MemoryEntry> existing, ContextBudgetPolicy budget) {
        int output = budget.maxOutputTokens() == null ? settings.maxOutputTokens()
                : (int) Math.min(settings.maxOutputTokens(), budget.maxOutputTokens());
        var data = mapper.createObjectNode().put("userInput", intent.text()).put("intent", intent.kind().name())
                .put("hasWorkspace", hasWorkspace).put("scopeHint", intent.scopeHint() == null ? "UNSPECIFIED" : intent.scopeHint().name());
        var known = data.putArray("existingMemories");
        for (var entry : existing) known.addObject().put("id", entry.id()).put("scope", entry.scope().name())
                .put("title", entry.title()).put("content", entry.content());
        var messages = List.of(Message.system(PromptLoader.loadMemoryExtract()), Message.user(data.toString()));
        var estimator = new TokenEstimator();
        long tokens = messages.stream().mapToLong(estimator::messageBodyTokens).sum() + 11;
        Long modelLimit = budget.inputBudgetForOutput(output);
        if (tokens > settings.maxInputTokens() || modelLimit != null && tokens > modelLimit)
            throw new MemoryException("记忆提取输入超过独立或模型预算，已跳过。");
        var request = new LlmRequest(messages, List.of(), false, output);
        var pool = Executors.newSingleThreadExecutor(r -> { var thread = new Thread(r, "codey-memory-extract"); thread.setDaemon(true); return thread; });
        Future<LlmResponse> future = pool.submit(() -> client.generate(request));
        try {
            var response = future.get(settings.timeoutSeconds(), TimeUnit.SECONDS);
            if (response == null || !"stop".equals(response.finishReason()) || !response.toolCalls().isEmpty()
                    || response.text() == null || response.text().isBlank() || response.text().length() > 32768
                    || estimator.textTokens(response.text()) > output)
                throw new MemoryException("记忆提取响应不完整或超限，未保存候选。");
            String text = response.text().strip();
            if (text.startsWith("```json\n") && text.endsWith("\n```")) text = text.substring(8, text.length() - 4);
            Document document;
            try { document = mapper.readValue(text, Document.class); }
            catch (Exception invalid) { throw new MemoryException("记忆提取JSON无效，未保存候选。"); }
            if (document == null) throw new MemoryException("记忆提取JSON为空。");
            // 全组先校验，再由宿主决定保存；模型提供的ID必须来自本次发送的已有记忆。
            for (var candidate : document.memories()) {
                if (!intent.text().contains(candidate.evidence())) throw new MemoryException("记忆候选缺少本轮用户原文依据。");
                if (intent.scopeHint() != null && candidate.scope() != intent.scopeHint()
                        || candidate.scope() == MemoryScope.PROJECT && !hasWorkspace)
                    throw new MemoryException("记忆候选作用域与用户输入不一致。");
                if (candidate.relatedId() != null && existing.stream().noneMatch(e -> e.id().equals(candidate.relatedId()) && e.scope() == candidate.scope()))
                    throw new MemoryException("记忆候选引用了未提供的旧记忆。");
                if (candidate.content().matches("(?is).*(api[_ -]?key|token|password|密钥|密码)\\s*[:：=].*"))
                    throw new MemoryException("候选疑似包含凭证，不自动记录。");
            }
            return document.memories();
        } catch (TimeoutException timeout) { throw new MemoryException("记忆提取超时，已取消；本次不重试。"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new MemoryException("记忆提取已中断。"); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof LlmException error) {
                if (error.kind() == LlmException.Kind.INTERRUPTED) Thread.currentThread().interrupt();
                throw new MemoryException("记忆提取模型调用失败：" + error.kind());
            }
            throw new MemoryException("记忆提取模型调用失败。");
        } finally { future.cancel(true); pool.shutdownNow(); }
    }
}
