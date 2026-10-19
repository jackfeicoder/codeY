package io.github.jackfeicoder.codey.tool;

/** 由可信 Java 实现声明，不从模型参数读取；未分类工具不默认为只读。 */
public enum ToolAccess { READ_ONLY, FILE_MUTATION, UNKNOWN }
