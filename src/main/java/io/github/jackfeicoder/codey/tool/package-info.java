/**
 * 工具定义、注册、执行上下文、参数校验契约和结构化结果。
 * ToolCall 表达调用，ToolExecutor 调度已注册实现，ToolResult 可转为关联的 tool 消息。
 * Main 已注册 list_files 和 read_file，AgentLoop 通过 LLM tools 字段发送定义并调度执行；完整策略后续接入。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.tool;
