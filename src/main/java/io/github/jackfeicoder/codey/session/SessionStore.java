package io.github.jackfeicoder.codey.session;

import java.util.List;

/** 本地持久化接口；上层不依赖具体目录布局、JSON 编解码或文件锁实现。 */
public interface SessionStore {
    /** 扫描所有工作区的会话；单个损坏记录通过 warnings 展示，不阻断其他记录。 */
    Listing list();
    /** 无锁读取供标题/ID 查找与预览使用，不代表获得了写权限。 */
    SessionSnapshot load(String id);
    /** 新建或恢复时取得独占写锁，直到 Lease.close。 */
    Lease lock(String id, boolean create);

    /** 一次列表快照及可展示的错误定位信息。 */
    record Listing(List<SessionSnapshot> sessions, List<String> warnings) {
        public Listing {
            sessions = List.copyOf(sessions);
            warnings = List.copyOf(warnings);
        }
    }

    /** 持锁的会话访问；save 校验版本并原子替换，close 释放操作系统锁。 */
    interface Lease extends AutoCloseable {
        SessionSnapshot load();
        void save(SessionSnapshot snapshot);
        @Override void close();
    }
}
