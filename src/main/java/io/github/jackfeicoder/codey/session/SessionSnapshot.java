package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import io.github.jackfeicoder.codey.context.CompactionState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 磁盘快照 DTO：保存属性、提示词和完整文本/工具轮次，不保存模型配置或环境变量。 */
public record SessionSnapshot(int schemaVersion, String id, String title, TitleSource titleSource,
                              boolean titleGenerationAttempted, String createdAt, String updatedAt,
                              long revision, WorkspaceBinding workspace, String profileAlias,
                              String systemPrompt, String promptHash, List<Message> messages, CompactionState compaction) {
    /** 兼容原 v1/v2 构造入口和缺少 compaction 字段的磁盘记录。 */
    public SessionSnapshot(int schemaVersion, String id, String title, TitleSource titleSource,
                           boolean titleGenerationAttempted, String createdAt, String updatedAt,
                           long revision, WorkspaceBinding workspace, String profileAlias,
                           String systemPrompt, String promptHash, List<Message> messages) {
        this(schemaVersion, id, title, titleSource, titleGenerationAttempted, createdAt, updatedAt, revision,
                workspace, profileAlias, systemPrompt, promptHash, messages, null);
    }
    /** 标题的来源；PENDING 表示首问摘要已保存、模型生成尚未结束。 */
    public enum TitleSource { MANUAL, PENDING, LLM, FALLBACK }

    /** v1 文本、v2 完整工具消息、v3 另含摘要状态；均校验原始完整轮次。 */
    public SessionSnapshot {
        if ((schemaVersion != 1 && schemaVersion != 2 && schemaVersion != 3) || !validId(id) || !validTitle(title) || titleSource == null
                || revision < 0 || workspace == null || profileAlias == null || profileAlias.isBlank()
                || createdAt == null || updatedAt == null
                || systemPrompt == null || systemPrompt.isBlank() || !hash(systemPrompt).equals(promptHash)
                || messages == null) {
            throw new IllegalArgumentException("Invalid session snapshot.");
        }
        if (Instant.parse(updatedAt).isBefore(Instant.parse(createdAt))) {
            throw new IllegalArgumentException("Invalid session timestamps.");
        }
        messages = List.copyOf(messages);
        MessageHistory.validate(messages); // system 单独保存；验证工具结果完整配对。
        if (schemaVersion == 3 && compaction == null) throw new IllegalArgumentException("Version 3 requires compaction state.");
        if (compaction != null) {
            if (schemaVersion != 3) throw new IllegalArgumentException("Compaction requires snapshot version 3.");
            compaction.validate(messages);
        }
        if (schemaVersion == 1 && messages.stream().anyMatch(message ->
                message.role() == MessageRole.TOOL || !message.toolCalls().isEmpty())) {
            throw new IllegalArgumentException("Version 1 only supports text turns.");
        }
    }

    /** 新会话先使用手动标题或占位标题；UUID 是持久化的唯一标识。 */
    public static SessionSnapshot create(WorkspaceBinding workspace, String profile, String prompt, String title) {
        String now = Instant.now().toString(); // 磁盘使用 UTC，显示时再转为本地时间。
        return new SessionSnapshot(2, UUID.randomUUID().toString(), title == null ? "新会话" : title,
                title == null ? TitleSource.PENDING : TitleSource.MANUAL, false,
                now, now, 0, workspace, profile, prompt, hash(prompt), List.of());
    }

    /** 修改内容或标题时保持 ID 与创建时间；revision 由成功保存决定。 */
    public SessionSnapshot update(String title, TitleSource source, boolean attempted, List<Message> history) {
        return new SessionSnapshot(compaction == null ? 2 : 3, id, title, source, attempted, createdAt, Instant.now().toString(),
                revision, workspace, profileAlias, systemPrompt, promptHash, history, compaction);
    }

    /** 选择另一有效配置继续时只保存其别名，不保存旧凭证。 */
    public SessionSnapshot withProfile(String profile) {
        return new SessionSnapshot(compaction == null ? 2 : 3, id, title, titleSource, titleGenerationAttempted, createdAt, updatedAt,
                revision, workspace, profile, systemPrompt, promptHash, messages, compaction);
    }

    /** 构造下一次写入的版本，其他数据不变。 */
    public SessionSnapshot withRevision(long value) {
        return new SessionSnapshot(compaction == null ? 2 : 3, id, title, titleSource, titleGenerationAttempted, createdAt, updatedAt,
                value, workspace, profileAlias, systemPrompt, promptHash, messages, compaction);
    }

    /** 摘要与完整原记录进入同一原子快照，沿用 revision/文件锁机制。 */
    public SessionSnapshot withCompaction(CompactionState state) {
        return new SessionSnapshot(state == null ? 2 : 3, id, title, titleSource, titleGenerationAttempted, createdAt, Instant.now().toString(),
                revision, workspace, profileAlias, systemPrompt, promptHash, messages, state);
    }

    /** ID 必须是标准 UUID，不能作为任意路径输入。 */
    public static boolean validId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** 标题允许重名与 Unicode，禁止控制字符，最多 40 个码点。 */
    public static boolean validTitle(String value) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 40
                && value.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
    }

    /** 无额外模型调用的摘要占位；按码点截取，避免截断代理对字符。 */
    public static String fallbackTitle(String input) {
        String text = input.replaceAll("\\s+", " ").strip();
        int count = Math.min(40, text.codePointCount(0, text.length()));
        text = text.substring(0, text.offsetByCodePoints(0, count));
        return validTitle(text) ? text : "新会话";
    }

    /** 提示词摘要用于提示版本变化，不作为执行权限或会话身份。 */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.");
        }
    }
}
