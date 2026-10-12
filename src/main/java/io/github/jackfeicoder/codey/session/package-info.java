/**
 * 消息模型、内存会话与本地快照；工具执行状态后续实现。
 * Session 保存成功文本，SessionService 协调保存，JsonSessionStore 提供版本检查与文件锁。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.session;
