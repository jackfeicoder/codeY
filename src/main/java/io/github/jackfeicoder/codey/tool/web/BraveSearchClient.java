package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.config.WebSearchSettings.Provider;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 可选备用适配；只在服务列表明确配置 brave 且提供其独立密钥时启用。 */
public final class BraveSearchClient implements WebSearchClient {
    private final Provider provider;
    private final SearchHttp http=new SearchHttp();
    public BraveSearchClient(Provider provider){this.provider=provider;}
    public String name(){return provider.name();}
    public WebSearchResponse search(WebSearchRequest request,Duration timeout)throws InterruptedException {
        String query=request.query();
        if(!request.allowedDomains().isEmpty())query+=" ("+request.allowedDomains().stream().map(d->"site:"+d).collect(java.util.stream.Collectors.joining(" OR "))+")";
        String uri=provider.endpoint()+"?q="+URLEncoder.encode(query,StandardCharsets.UTF_8)+"&count="+request.maxResults();
        String freshness=switch(request.timeRange()){case "day"->"pd";case "week"->"pw";case "month"->"pm";case "year"->"py";default->null;};
        if(freshness!=null)uri+="&freshness="+freshness;
        var response=http.send(HttpRequest.newBuilder(URI.create(uri)).timeout(timeout).header("X-Subscription-Token",provider.apiKey())
                .header("Accept","application/json").GET().build(),timeout);
        return SearchResults.parse(response.path("web").get("results"),"description",request);
    }
}
