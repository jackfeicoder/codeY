/**
 * 模型调用接口、请求响应对象与协议适配。
 * ChatCompletionsClient 支持非流式 HTTP/JSON 与 SSE，ChatStreamDecoder 有界聚合正文和工具参数。
 * 流式分片只用于展示，完整 LlmResponse 经过统一校验后交给 Agent，出错不自动重试。
 * <p>实现状态与开发顺序见 docs/architecture.md。</p>
 */
package io.github.jackfeicoder.codey.llm;
