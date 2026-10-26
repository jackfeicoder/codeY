/**
 * 后续统一收集、选择和压缩每轮模型输入。
 * 负责上下文预算与请求视图，会话完整历史由 session 保存。
 * <p>已先接入 config 包的 ModelCapabilities/Loader 作为模型容量资料；
 * 本包尚未实现 Token 估算、预算检查或自动压缩。开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.context;
