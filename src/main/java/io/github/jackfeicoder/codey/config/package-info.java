/**
 * 读取 .env 和进程环境，管理 Profile 并构建强类型配置。
 * 入口 ConfigLoader，文件解析 DotEnvReader，选择 ModelProfiles，最终得到 LlmConfig。
 * AgentSettings 读取任务轮次和目录默认深度，启动时由 Main 注入 AgentLoop 与 ListFilesTool。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.config;
