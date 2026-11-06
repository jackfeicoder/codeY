package io.github.jackfeicoder.codey.task;

/** 任务记录读写失败：停止继续执行，保留现有日志以供恢复。 */
public final class TaskException extends RuntimeException {
    public TaskException(String message) { super(message); }
}
