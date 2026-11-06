package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.config.WebSearchSettings;
import io.github.jackfeicoder.codey.tool.*;
import java.time.*;
import java.util.*;

/** 有序故障转移；每个服务最多尝试一次，空结果/认证错误/取消不会触发切换。 */
public final class WebSearchService {
    private final WebSearchSettings settings;
    private final List<WebSearchClient> clients;
    private int calls;
    public WebSearchService(WebSearchSettings settings){this(settings,settings.providers().stream().map(p->switch(p.name()) {
        case "tavily" -> (WebSearchClient)new TavilySearchClient(p);
        case "brave" -> new BraveSearchClient(p);
        case "serpapi" -> new SerpApiSearchClient(p);
        default -> throw new IllegalArgumentException("Unsupported search provider.");
    }).toList());}
    /** 显式注入适配器，便于本地模拟和后续增加搜索引擎。 */
    public WebSearchService(WebSearchSettings settings,List<WebSearchClient> clients){this.settings=settings;this.clients=List.copyOf(clients);}
    public void resetTask(){calls=0;}
    public String providerNames(){return String.join(" → ",clients.stream().map(WebSearchClient::name).toList());}
    public String search(WebSearchRequest request,int maxChars)throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        String searchId=UUID.randomUUID().toString();
        var output=SearchHttp.JSON.createObjectNode().put("query",request.query()).put("searchId",searchId).put("searchedAt",Instant.now().toString())
                .put("contentType","search_snippets_not_full_pages").put("truncated",false);
        output.putArray("results");var attempts=output.putArray("attempts");
        output.put("provider","");
        if(output.toString().codePointCount(0,output.toString().length())+512>maxChars)throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,"Search metadata exceeds tool output limit.");
        if(calls>=settings.maxCalls())throw new ToolException(ToolResult.ErrorCode.WEB_SEARCH_BUDGET_EXCEEDED,"Search budget exhausted for this task attempt; use existing results.");
        calls++;
        long deadline=System.nanoTime()+Duration.ofSeconds(settings.totalTimeoutSeconds()).toNanos();
        for(var client:clients){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            long remaining=deadline-System.nanoTime();if(remaining<=0)break;
            try {
                var result=client.search(request,Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(settings.timeoutSeconds()).toNanos())));
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                output.put("provider",client.name());attempts.addObject().put("provider",client.name()).put("status","success");
                var array=output.putArray("results");int index=0;
                for(var item:result.results()) {
                    if(settings.providers().stream().anyMatch(p->item.url().contains(p.apiKey())))continue;
                    var node=array.addObject().put("id",searchId+"_result_"+(++index)).put("title",item.title()).put("url",item.url()).put("snippet",item.snippet());
                    if(item.publishedAt()==null)node.putNull("publishedAt");else node.put("publishedAt",item.publishedAt());
                }
                String encoded=redact(output.toString());
                while(encoded.codePointCount(0,encoded.length())>maxChars && !array.isEmpty()){
                    array.remove(array.size()-1);output.put("truncated",true);encoded=redact(output.toString());
                }
                if(encoded.codePointCount(0,encoded.length())>maxChars)throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,"Search output metadata exceeds tool output limit.");
                return encoded;
            }catch(WebSearchFailure failed){
                var attempt=attempts.addObject().put("provider",client.name()).put("status",failed.kind().name());
                if(failed.httpStatus()>0)attempt.put("httpStatus",failed.httpStatus());
                if(!failed.fallbackAllowed())throw new ToolException(ToolResult.ErrorCode.WEB_SEARCH_FAILED,
                        "Search provider "+client.name()+" failed: "+failed.getMessage()+". No fallback for authentication, rejected requests or invalid responses.");
            }
        }
        throw new ToolException(ToolResult.ErrorCode.WEB_SEARCH_FAILED,"Search providers unavailable or total deadline exceeded. Attempts: "+attempts);
    }
    /** 即使服务商错误地在结果里回显密钥，也不让已配置的凭证进入日志/上下文。 */
    private String redact(String value){
        try {
            var node=SearchHttp.JSON.readTree(value);redactNode(node);return node.toString();
        }catch(Exception invalid){throw new ToolException(ToolResult.ErrorCode.WEB_SEARCH_FAILED,"Failed to encode search result.");}
    }
    private void redactNode(com.fasterxml.jackson.databind.JsonNode node){
        if(node.isObject()) {
            var object=(com.fasterxml.jackson.databind.node.ObjectNode)node;
            var names=new ArrayList<String>();node.fieldNames().forEachRemaining(names::add);
            for(String name:names){var child=node.get(name);if(child.isTextual())object.put(name,redactText(child.asText()));else redactNode(child);}
        }else if(node.isArray())for(var child:node)redactNode(child);
    }
    private String redactText(String value){for(var p:settings.providers())value=value.replace(p.apiKey(),"[REDACTED]");return value;}
}
