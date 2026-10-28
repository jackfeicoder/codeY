package io.github.jackfeicoder.codey.memory;

import java.util.List;
import java.util.function.UnaryOperator;

/** 每个作用域文件是一个事务单元；更新必须锁定后重新读取，防止多个会话丢失写入。 */
public interface MemoryStore {
    List<MemoryEntry> load(MemoryScope scope, String projectId);
    List<MemoryEntry> update(MemoryScope scope, String projectId, UnaryOperator<List<MemoryEntry>> mutation);
}
