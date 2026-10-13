/**
 * Agent 执行循环、预算、取消和完成判定。
 * AgentLoop 依赖 LlmClient 与 ToolExecutor，负责模型调用和工具结果回填。
 * AgentResult 区分正常回答与预算退出；会话提交由调用方负责。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.agent;
