/**
 * 消息模型、内存会话与本地快照；执行中任务的恢复后续实现。
 * Session 保存完整文本/工具轮次，MessageHistory 校验配对；存储提供版本检查与文件锁。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.session;
