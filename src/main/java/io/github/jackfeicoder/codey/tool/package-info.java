/**
 * 工具定义、注册、执行上下文、参数校验契约和结构化结果。
 * ToolCall 表达调用，ToolExecutor 调度已注册实现，ToolResult 可转为关联的 tool 消息。
 * Main 已注册四个只读工具、write_file/edit_file 和 execute_command；执行器检查模式，再准备、审批并执行确定的操作。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.tool;
