package io.github.jackfeicoder.codey.tool.web;
/** 模型只接收所选正文段、来源及分页信息，不接收原始响应头。 */
public record WebFetchResponse(String requestedUrl,String finalUrl,String redirectUrl,String title,String contentType,
        String fetchedAt,String contentHash,int startLine,int endLine,int totalLines,String content,
        boolean hasMore,Integer nextLine,boolean truncated,boolean cacheHit,String notice) { }
