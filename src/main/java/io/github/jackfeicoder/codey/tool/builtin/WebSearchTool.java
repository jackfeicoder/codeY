package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.web.*;
import io.github.jackfeicoder.codey.policy.ApprovalRequest;
import java.util.*;

/** 公开网页检索；定义随 tools 发布，查询经独立联网审批，不要求工作区。 */
public final class WebSearchTool implements Tool {
    private final WebSearchService service;
    private static final Set<String> FIELDS=Set.of("query","maxResults","allowedDomains","timeRange");
    private static final java.util.regex.Pattern SENSITIVE=java.util.regex.Pattern.compile("(?i)(bearer\\s+\\S+|(?:api[_-]?key|token|password)\\s*[:=]\\s*\\S+)");
    public WebSearchTool(WebSearchService service){this.service=service;}
    public ToolAccess access(){return ToolAccess.READ_ONLY;}
    public ToolDefinition definition(){
        var schema=new ObjectMapper().createObjectNode().put("type","object").put("additionalProperties",false);
        schema.putArray("required").add("query");var p=schema.putObject("properties");
        p.putObject("query").put("type","string").put("minLength",1).put("maxLength",512).put("description","明确关键词，包含技术版本/错误信息；勿包含凭证或私有代码。");
        p.putObject("maxResults").put("type","integer").put("minimum",1).put("maximum",10).put("default",5);
        var domains=p.putObject("allowedDomains").put("type","array").put("maxItems",10).put("uniqueItems",true);
        domains.putObject("items").put("type","string").put("maxLength",253);
        domains.put("description","仅返回这些域名及其子域，如 docs.oracle.com，不含协议/路径。");
        p.putObject("timeRange").put("type","string").put("default","any").putArray("enum").add("any").add("day").add("week").add("month").add("year");
        return new ToolDefinition("web_search","搜索公开互联网资料，返回标题、URL和相关片段。用于用户要求联网、最新信息、官方文档及错误线索。"
                +"片段不是完整网页，不可声称已读全文；回答须链接真实结果URL，不猜测发布时间。查询会发送给搜索服务，故障时可能发送给已配置备用服务。不要发送凭证或私有项目正文。",schema);
    }
    public void validateArguments(JsonNode arguments,ToolContext context){options(arguments);}
    public PreparedToolOperation prepare(JsonNode arguments,ToolContext context){
        var options=options(arguments);boolean sensitive=SENSITIVE.matcher(options.query()).find();
        String preview="服务顺序："+service.providerNames()+"\n将发送搜索词："+(sensitive?"[疑似凭证，已隐藏，请检查查询]":options.query())
                +"\n条数："+options.maxResults()+"；域名："+options.allowedDomains()+"；时间："+options.timeRange()+"。公开搜索范围可在当前会话复用，不授予文件/命令权限。";
        var approval=new ApprovalRequest("web_search",null,null,"web_search",null,null,0,0,sensitive,false,preview,false,
                io.github.jackfeicoder.codey.policy.ApprovalScope.key(options.toString()))
                .withImpact(new io.github.jackfeicoder.codey.policy.OperationImpact(
                        io.github.jackfeicoder.codey.policy.OperationImpact.Kind.PUBLIC_NETWORK,
                        "已配置搜索服务 "+service.providerNames()+" 的公开查询（含配置的备用服务）",
                        io.github.jackfeicoder.codey.policy.ApprovalScope.key("search",service.approvalScope()),false));
        return new PreparedToolOperation(approval,()->service.search(options,context.maxOutputChars()));
    }
    public String execute(JsonNode arguments,ToolContext context)throws InterruptedException{return prepare(arguments,context).execute(context);}
    private static WebSearchRequest options(JsonNode node){
        if(node==null || !node.isObject())throw invalid();
        node.fieldNames().forEachRemaining(f->{if(!FIELDS.contains(f))throw invalid();});
        if(!node.path("query").isTextual())throw invalid();String query=node.path("query").asText().strip();
        if(query.isEmpty() || query.codePointCount(0,query.length())>512 || query.codePoints().anyMatch(Character::isISOControl))throw invalid();
        int max=5;
        if(node.has("maxResults")){if(!node.get("maxResults").isIntegralNumber() || !node.get("maxResults").canConvertToInt())throw invalid();max=node.get("maxResults").asInt();}
        if(max<1 || max>10)throw invalid();
        var domains=new LinkedHashSet<String>();
        if(node.has("allowedDomains")){
            var array=node.get("allowedDomains");if(!array.isArray() || array.size()>10)throw invalid();
            for(var domain:array){
                if(!domain.isTextual())throw invalid();String d=domain.asText().toLowerCase(Locale.ROOT);
                if(d.length()>253 || !d.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?") || !domains.add(d))throw invalid();
            }
        }
        String time="any";
        if(node.has("timeRange")){if(!node.get("timeRange").isTextual())throw invalid();time=node.get("timeRange").asText();}
        if(!Set.of("any","day","week","month","year").contains(time))throw invalid();
        return new WebSearchRequest(query,max,List.copyOf(domains),time);
    }
    private static ToolException invalid(){return new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS,"Invalid web_search parameters: query required; maxResults 1..10; plain allowedDomains; timeRange any/day/week/month/year.");}
}
