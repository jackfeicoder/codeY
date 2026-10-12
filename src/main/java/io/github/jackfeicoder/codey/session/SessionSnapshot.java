package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 磁盘快照 DTO：只保存会话属性、提示词和成功文本，不保存 LlmConfig 或环境变量。 */
public record SessionSnapshot(int schemaVersion, String id, String title, TitleSource titleSource,
                              boolean titleGenerationAttempted, String createdAt, String updatedAt,
                              long revision, WorkspaceBinding workspace, String profileAlias,
                              String systemPrompt, String promptHash, List<Message> messages) {
    /** 标题的来源；PENDING 表示首问摘要已保存、模型生成尚未结束。 */
    public enum TitleSource { MANUAL, PENDING, LLM, FALLBACK }

    /** 校验版本、元数据及成对的 user/assistant，避免残缺历史进入模型请求。 */
    public SessionSnapshot {
        if (schemaVersion != 1 || !validId(id) || !validTitle(title) || titleSource == null
                || revision < 0 || workspace == null || profileAlias == null || profileAlias.isBlank()
                || createdAt == null || updatedAt == null
                || systemPrompt == null || systemPrompt.isBlank() || !hash(systemPrompt).equals(promptHash)
                || messages == null || messages.size() % 2 != 0) {
            throw new IllegalArgumentException("Invalid session snapshot.");
        }
        if (Instant.parse(updatedAt).isBefore(Instant.parse(createdAt))) {
            throw new IllegalArgumentException("Invalid session timestamps.");
        }
        messages = List.copyOf(messages);
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index); // system 单独保存，不出现在这个列表中。
            MessageRole expected = index % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT;
            if (message.role() != expected || !message.toolCalls().isEmpty()
                    || (expected == MessageRole.USER && message.content().isBlank())) {
                throw new IllegalArgumentException("Session contains an incomplete text turn.");
            }
        }
    }

    /** 新会话先使用手动标题或占位标题；UUID 是持久化的唯一标识。 */
    public static SessionSnapshot create(WorkspaceBinding workspace, String profile, String prompt, String title) {
        String now = Instant.now().toString(); // 磁盘使用 UTC，显示时再转为本地时间。
        return new SessionSnapshot(1, UUID.randomUUID().toString(), title == null ? "新会话" : title,
                title == null ? TitleSource.PENDING : TitleSource.MANUAL, false,
                now, now, 0, workspace, profile, prompt, hash(prompt), List.of());
    }

    /** 修改内容或标题时保持 ID 与创建时间；revision 由成功保存决定。 */
    public SessionSnapshot update(String title, TitleSource source, boolean attempted, List<Message> history) {
        return new SessionSnapshot(1, id, title, source, attempted, createdAt, Instant.now().toString(),
                revision, workspace, profileAlias, systemPrompt, promptHash, history);
    }

    /** 选择另一有效配置继续时只保存其别名，不保存旧凭证。 */
    public SessionSnapshot withProfile(String profile) {
        return new SessionSnapshot(1, id, title, titleSource, titleGenerationAttempted, createdAt, updatedAt,
                revision, workspace, profile, systemPrompt, promptHash, messages);
    }

    /** 构造下一次写入的版本，其他数据不变。 */
    public SessionSnapshot withRevision(long value) {
        return new SessionSnapshot(1, id, title, titleSource, titleGenerationAttempted, createdAt, updatedAt,
                value, workspace, profileAlias, systemPrompt, promptHash, messages);
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
