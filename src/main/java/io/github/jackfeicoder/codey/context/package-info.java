/**
 * 统一组装基础指令、完整成功历史、本次任务消息及可用工具，并估算输入 Token。
 * 请求视图与 Session 原始历史分离，运行时指令及统计不写入会话。
 * <p>模型容量和覆盖值来自 config 的 ModelCapabilities；ContextBudgetPolicy
 * 扣除输出预留和安全余量；HistoryCompactor 在发送前生成有界摘要，保留近期完整任务，
 * 再次估算后仍超限则阻止。完整原始记录始终留在 Session 和磁盘快照。</p>
 */
package io.github.jackfeicoder.codey.context;
