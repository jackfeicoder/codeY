package io.github.jackfeicoder.codey.tool.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;

/** 统一结构和域名后验校验；搜索片段是外部资料，不升级为系统指令。 */
final class SearchResults {
    static WebSearchResponse parse(JsonNode items,String contentField,WebSearchRequest request) {
        if(items==null || !items.isArray())throw new WebSearchFailure(WebSearchFailure.Kind.INVALID_RESPONSE);
        var results=new ArrayList<WebSearchResponse.Result>();var seen=new HashSet<String>();
        int checked=0;
        for(var item:items){
            if(++checked>100 || results.size()>=request.maxResults())break;
            if(!item.path("url").isTextual() || !item.path("title").isTextual())throw new WebSearchFailure(WebSearchFailure.Kind.INVALID_RESPONSE);
            String url=item.path("url").asText();
            try {
                URI uri=URI.create(url);String host=uri.getHost();
                if(host==null || uri.getUserInfo()!=null || !Set.of("https","http").contains(uri.getScheme()) || url.length()>2048)continue;
                String lower=host.toLowerCase(Locale.ROOT);
                if(!request.allowedDomains().isEmpty() && request.allowedDomains().stream().noneMatch(d->lower.equals(d)||lower.endsWith("."+d)))continue;
                if(!seen.add(url))continue;
            }catch(IllegalArgumentException invalid){continue;}
            var date=item.get("published_date");
            results.add(new WebSearchResponse.Result(shorten(item.path("title").asText(),300),url,
                    shorten(item.path(contentField).asText(""),1200),date!=null && date.isTextual()?shorten(date.asText(),64):null));
        }
        return new WebSearchResponse(results);
    }
    static String shorten(String value,int limit){return value.codePointCount(0,value.length())<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit))+"…";}
}
