package io.github.jackfeicoder.codey.tool.web;

import java.util.List;

/** 服务适配层统一结果；发布时间未知时为 null，不用检索时间冒充。 */
public record WebSearchResponse(List<Result> results) {
    public WebSearchResponse {results=List.copyOf(results);}
    public record Result(String title,String url,String snippet,String publishedAt) { }
}
