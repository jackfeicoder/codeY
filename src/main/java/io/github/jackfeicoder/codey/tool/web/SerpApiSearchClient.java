package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.config.WebSearchSettings.Provider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** SerpApi Google 搜索适配；协议要求 api_key 查询参数，请求URI不可写入日志或上下文。 */
public final class SerpApiSearchClient implements WebSearchClient {
    private final Provider provider;
    private final SearchHttp http=new SearchHttp();
    public SerpApiSearchClient(Provider provider){this.provider=provider;}
    public String name(){return provider.name();}
    public WebSearchResponse search(WebSearchRequest request,Duration timeout)throws InterruptedException {
        String query=request.query();
        if(!request.allowedDomains().isEmpty())query+=" ("+request.allowedDomains().stream().map(d->"site:"+d)
                .collect(java.util.stream.Collectors.joining(" OR "))+")";
        String uri=provider.endpoint()+"?engine=google&q="+encode(query)+"&num="+request.maxResults()+"&api_key="+encode(provider.apiKey());
        String period=switch(request.timeRange()){case "day"->"d";case "week"->"w";case "month"->"m";case "year"->"y";default->null;};
        if(period!=null)uri+="&tbs="+encode("qdr:"+period);
        var response=http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(timeout).header("Accept","application/json").GET().build(),timeout);
        String status=response.path("search_metadata").path("status").asText("");
        // 同步请求不轮询 Processing；200 状态的业务错误同样不得伪装成功。
        if(status.equals("Error") || response.hasNonNull("error") && !status.equals("Success"))
            throw new WebSearchFailure(WebSearchFailure.Kind.REJECTED);
        if(status.equals("Processing"))throw new WebSearchFailure(WebSearchFailure.Kind.INVALID_RESPONSE);
        var results=response.get("organic_results");
        if(results==null && status.equals("Success"))return new WebSearchResponse(List.of());
        return SearchResults.parse(results,"snippet","link","date",request);
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
