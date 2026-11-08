package io.github.jackfeicoder.codey.tool;

/** 宿主调度能力，不是模型可指定参数；只读标签不自动表示线程安全。 */
public enum ToolExecutionMode { SERIAL, PARALLEL_READ }
