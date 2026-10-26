/**
 * 统一组装基础指令、完整成功历史、本次任务消息及可用工具，并估算输入 Token。
 * 请求视图与 Session 原始历史分离，运行时指令及统计不写入会话。
 * <p>模型容量来自 config 的 ModelCapabilities；当前估算仅用于显示，
 * 尚未实施容量拦截、输出预留、历史裁剪或摘要压缩。</p>
 */
package io.github.jackfeicoder.codey.context;
