package io.github.jackfeicoder.codey.task;

import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.util.*;

/** 从追加日志重建的检查点；字段仅由任务存储修改，不承担审批或连接恢复。 */
public final class TaskState {
    /** 日志种子及派生检查点的数据版本。 */
    public int schemaVersion = 1;
    public enum Status { RUNNING, COMMIT_PENDING, COMPLETED, FAILED, BUDGET_EXHAUSTED, INTERRUPTED }
    /** UUID 身份与归属会话；不会随继续执行改变。 */
    public String id, sessionId;
    /** 原始用户目标；恢复时仍使用它，不以“继续”覆盖。 */
    public String goal;
    /** 当前尝试的模型选择，仅保存非敏感配置别名和模型 ID。 */
    public String profile;
    /** ISO-8601 UTC 时间，排序和诊断使用。 */
    public String createdAt, updatedAt;
    /** 宿主错误类别或清理警告，不包含异常原始响应。 */
    public String reason = "";
    /** /init 等宿主额外任务说明，恢复时一并组装。 */
    public String instructions = "";
    /** 任务绑定的原工作区，恢复时与当前会话比较。 */
    public WorkspaceBinding workspace;
    public Status status;
    /** 已成功追加的最后一个事件序号。 */
    public long sequence;
    /** 每次继续重新分配调用次数预算；累计计数保留供诊断。 */
    public int attempts, modelCalls, toolCalls;
    /** 当前尝试开始时成功历史前缀的长度及指纹，含 system。 */
    public int anchorCount;
    public String anchorHash;
    /** 本任务的 user/assistant/tool 消息，执行中允许最后一组尚未配齐。 */
    public List<Message> messages = new ArrayList<>();
    /** 已落盘调度意图的调用 ID；缺少结果时只能判断为结果未知。 */
    public Set<String> started = new LinkedHashSet<>();
    /** 本任务已经加载的技能身份快照。 */
    public List<SkillRef> skills = List.of();

    /** 只存来源与内容指纹，恢复时重新校验文件；不持久化权限、进程或认证信息。 */
    public record SkillRef(String name, String source, String fingerprint) { }
    public boolean completed() { return status == Status.COMPLETED; }
}
