package io.github.jackfeicoder.codey.cli;

/** 将终端 Ctrl+C 转为当前操作的线程中断；信号线程只发取消请求，不执行存储或输出。 */
final class OperationCancellation {
    /** 同一 CLI 串行执行一个操作，信号线程可并发请求取消。 */
    private Scope active;

    synchronized Scope begin() {
        if(active!=null)throw new IllegalStateException("A console operation is already active.");
        active=new Scope(Thread.currentThread());return active;
    }

    /** 空闲时忽略进程退出语义；编辑阶段由 JLine 自己处理清空当前输入。 */
    synchronized void cancel() {
        if(active!=null && !active.cancelled) {
            active.cancelled=true;
            active.owner.interrupt();
        }
        // 连按 Ctrl+C 不重新打断持久化/进程回收等收尾操作。
    }

    /** 生命周期覆盖请求、工具、会话提交及附属处理；结束后释放信号与线程的关联。 */
    final class Scope implements AutoCloseable {
        private final Thread owner;
        private volatile boolean cancelled;
        private boolean closed;
        private Scope(Thread owner){this.owner=owner;}
        boolean cancelled(){return cancelled;}

        @Override public void close() {
            synchronized(OperationCancellation.this) {
                if(closed)return;
                if(Thread.currentThread()!=owner)throw new IllegalStateException("Console operation must close on its owner thread.");
                closed=true;
                if(active==this)active=null;
                // 只消费本作用域接收的用户取消；普通外部线程中断继续向上传递。
                if(cancelled)Thread.interrupted();
            }
        }
    }
}
