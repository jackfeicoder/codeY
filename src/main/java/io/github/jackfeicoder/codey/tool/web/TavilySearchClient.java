package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.config.WebSearchSettings.Provider;
import java.net.http.HttpRequest;
import java.time.Duration;

/** Tavily 搜索适配，不启用额外模型回答、网页全文或自动参数升级。 */
public final class TavilySearchClient implements WebSearchClient {
    private final Provider provider;
    private final SearchHttp http=new SearchHttp();
    public TavilySearchClient(Provider provider){this.provider=provider;}
    public String name(){return provider.name();}
    public WebSearchResponse search(WebSearchRequest request,Duration timeout)throws InterruptedException {
        var body=SearchHttp.JSON.createObjectNode().put("query",request.query()).put("max_results",request.maxResults())
                .put("search_depth","basic").put("include_answer",false).put("include_raw_content",false).put("auto_parameters",false);
        if(!request.allowedDomains().isEmpty())body.set("include_domains",SearchHttp.JSON.valueToTree(request.allowedDomains()));
        if(!request.timeRange().equals("any"))body.put("time_range",request.timeRange());
        var response=http.send(HttpRequest.newBuilder(provider.endpoint()).timeout(timeout)
                .header("Authorization","Bearer "+provider.apiKey()).header("Content-Type","application/json")
                .header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),timeout);
        return SearchResults.parse(response.get("results"),"content",request);
    }
}
