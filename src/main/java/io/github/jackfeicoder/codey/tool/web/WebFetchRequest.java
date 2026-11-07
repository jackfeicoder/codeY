package io.github.jackfeicoder.codey.tool.web;
/** 行号针对清洗后、固定换行的正文快照；HTTP参数与认证不由模型指定。 */
public record WebFetchRequest(String url,int startLine,int maxLines) { }
