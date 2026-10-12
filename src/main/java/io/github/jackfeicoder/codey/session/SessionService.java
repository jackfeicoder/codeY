package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

/** 管理一个活动会话的内存状态、快照和写锁；存储失败时保留 dirty 状态供重试。 */
public final class SessionService implements AutoCloseable {
    /** 活动期间持有的独占存储访问权。 */
    private final SessionStore.Lease lease;
    /** 真正用于模型请求的内存消息。 */
    private final Session session;
    /** 最新内容及最后成功保存的 revision。 */
    private SessionSnapshot snapshot;
    /** true 表示内存数据尚未成功落盘，切换或新请求前应重试。 */
    private boolean dirty;

    /** 由 create/resume 装配持锁对象，恢复提示词与消息。 */
    private SessionService(SessionStore.Lease lease, SessionSnapshot snapshot, boolean dirty) {
        this.lease = lease;
        this.snapshot = snapshot;
        this.session = Session.restore(snapshot);
        this.dirty = dirty;
    }

    /** 创建并保存空会话，空会话不出现在默认历史列表中。 */
    public static SessionService create(SessionStore store, WorkspaceBinding workspace,
                                        String profile, String prompt, String title) {
        SessionSnapshot snapshot = SessionSnapshot.create(workspace, profile, prompt, title);
        SessionStore.Lease lease = store.lock(snapshot.id(), true);
        try {
            lease.save(snapshot);
            return new SessionService(lease, snapshot, false);
        } catch (RuntimeException failed) {
            lease.close();
            throw failed;
        }
    }

    /** 获得写锁后加载最新快照，避免用历史列表中陈旧的数据恢复。 */
    public static SessionService resume(SessionStore store, String id, String profile) {
        SessionStore.Lease lease = store.lock(id, false);
        try {
            SessionSnapshot stored = lease.load();
            SessionSnapshot snapshot = stored.withProfile(profile);
            if (snapshot.titleSource() == SessionSnapshot.TitleSource.PENDING
                    && snapshot.titleGenerationAttempted()) {
                snapshot = snapshot.update(snapshot.title(), SessionSnapshot.TitleSource.FALLBACK,
                        true, snapshot.messages());
            }
            return new SessionService(lease, snapshot, !snapshot.equals(stored));
        } catch (RuntimeException failed) {
            lease.close();
            throw failed;
        }
    }

    /** 返回活动会话供 ChatCli 提交成功轮次。 */
    public Session session() { return session; }
    /** 返回不可变快照，供列表显示或标题决策使用。 */
    public SessionSnapshot snapshot() { return snapshot; }
    /** 查询是否存在待重试保存的数据。 */
    public boolean dirty() { return dirty; }

    /** 从刚完成的内存轮次生成待保存快照；首轮无手动标题时先使用本地摘要。 */
    public void captureTurn() {
        var messages = session.messages(); // 去掉单独存储的 system，保留完整成功轮次。
        var history = messages.subList(1, messages.size());
        String title = snapshot.title();
        if (snapshot.messages().isEmpty() && snapshot.titleSource() != SessionSnapshot.TitleSource.MANUAL) {
            title = SessionSnapshot.fallbackTitle(history.get(0).content());
        }
        snapshot = snapshot.update(title, snapshot.titleSource(), snapshot.titleGenerationAttempted(), history);
        dirty = true;
    }

    /** 修改标题或生成状态，但不修改对话列表；调用方接着 save 保存。 */
    public void setTitle(String title, SessionSnapshot.TitleSource source, boolean attempted) {
        snapshot = snapshot.update(title, source, attempted, snapshot.messages());
        dirty = true;
    }

    /** 成功后清除 dirty；失败不更新 revision，下一次可重试相同内容。 */
    public void save() {
        if (dirty) {
            SessionSnapshot candidate = snapshot.withRevision(Math.addExact(snapshot.revision(), 1));
            lease.save(candidate);
            snapshot = candidate;
            dirty = false;
        }
    }

    /** 仅释放锁；退出或切换前由入口显式 save，避免关闭时掩盖未保存错误。 */
    @Override
    public void close() { lease.close(); }
}
