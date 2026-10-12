/**
 * Agent 执行循环、预算、取消和完成判定。
 * 后续依赖 LlmClient 与 ToolExecutor，负责模型调用和工具结果回填。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.agent;
