/**
 * 统一组装基础指令、完整成功历史、本次任务消息及可用工具，并估算输入 Token。
 * 请求视图与 Session 原始历史分离，运行时指令及统计不写入会话。
 * <p>模型容量和覆盖值来自 config 的 ModelCapabilities；ContextBudgetPolicy
 * 扣除输出预留和安全余量，已知容量超限在每次请求前阻止；尚无历史裁剪或摘要压缩。</p>
 */
package io.github.jackfeicoder.codey.context;
