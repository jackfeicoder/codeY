package io.github.jackfeicoder.codey.memory;
/** 可向终端展示的记忆错误，不包含磁盘正文或底层异常中的敏感数据。 */
public final class MemoryException extends IllegalArgumentException {
    public MemoryException(String message) { super(message); }
}
