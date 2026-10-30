package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.policy.ApprovalRequest;

/** 模型可见的召唤/释放入口；管理工具常驻，外部工具只活跃在当前任务。 */
public final class McpControlTool implements Tool {
    private final McpConnectionManager manager;
    private final boolean summon;
    McpControlTool(McpConnectionManager manager,boolean summon) { this.manager=manager; this.summon=summon; }
    @Override public ToolAccess access() { return summon?ToolAccess.UNKNOWN:ToolAccess.READ_ONLY; }
    @Override public ToolDefinition definition() {
        var schema=JsonNodeFactory.instance.objectNode().put("type","object").put("additionalProperties",false);
        schema.putObject("properties").putObject("server").put("type","string"); schema.putArray("required").add("server");
        return new ToolDefinition(summon?"mcp_summon":"mcp_release",summon
                ?"按需激活一个 MCP 服务，连接并加载该服务允许的工具。新工具从下一轮请求起可调用，使用前仍需审批。目录（估算非价格）：\n"+manager.catalog()
                :"释放当前任务不再需要的 MCP 服务，卸载工具并关闭连接；已有历史结果继续保留。参数 server 为配置别名。",schema);
    }
    @Override public void validateArguments(JsonNode args,ToolContext context) {
        if(!args.isObject()||args.size()!=1||!args.path("server").isTextual()) throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS,"Expected {server: configured alias}.");
        manager.config(args.get("server").textValue());
    }
    @Override public PreparedToolOperation prepare(JsonNode args,ToolContext context) {
        String name=args.path("server").asText();
        if(!summon || manager.active(name)) return new PreparedToolOperation(null,()->summon?manager.summon(name):manager.release(name));
        var config=manager.config(name);
        var request=new ApprovalRequest("mcp_summon",null,null,"mcp_connect",null,null,0,0,false,true,
                "激活 MCP 服务："+name+"（"+config.transport()+"）。"+connectionPreview(config)+"；可能启动本地程序或发送认证信息，工具执行另行审批。",false,"server:"+name);
        return new PreparedToolOperation(request,()->manager.summon(name));
    }
    private static String connectionPreview(McpServerConfig config) {
        if (config.transport().equals("streamable-http")) {
            var uri=java.net.URI.create(config.url());
            return "端点："+uri.getScheme()+"://"+uri.getRawAuthority()+uri.getRawPath()+"（查询参数及请求头隐藏）";
        }
        String command=config.command()+" "+config.args();
        for(String value:config.env().values()) if(!value.isEmpty()) command=command.replace(value,"[hidden]");
        return "程序/参数："+command;
    }
    @Override public String execute(JsonNode args,ToolContext context) throws InterruptedException {
        validateArguments(args,context); return prepare(args,context).execute(context);
    }
}
