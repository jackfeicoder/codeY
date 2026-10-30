package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.policy.ApprovalRequest;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;

/** 把一个远端工具适配为普通 Tool；公开名称与原始名称分别保存，不靠拆字符串路由。 */
public final class McpToolAdapter implements Tool {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final JsonSchemaValidator VALIDATOR=JsonSchemaValidator.createDefault();
    private final McpConnectionManager owner;
    private final String server,rawName;
    private final ToolDefinition definition;
    McpToolAdapter(McpConnectionManager owner,String server,String publicName,McpConnection.RemoteTool remote) {
        this.owner=owner; this.server=server; this.rawName=remote.name();
        this.definition=new ToolDefinition(publicName,"[MCP "+server+"] "+(remote.description()==null||remote.description().isBlank()?rawName:remote.description()),remote.schema());
    }
    @Override public ToolDefinition definition() { return definition; }
    /** 第三方的 readOnlyHint 不提升本地可信等级；PLAN 首版不开放远端调用。 */
    @Override public ToolAccess access() { return ToolAccess.UNKNOWN; }
    @Override public void validateArguments(JsonNode args,ToolContext context) {
        if(!owner.active(server)) throw new ToolException(ToolResult.ErrorCode.UNKNOWN_TOOL,"MCP server is dormant; summon it first.");
        try {
            if(!VALIDATOR.validate(JSON.convertValue(definition.parameters(),Map.class),JSON.convertValue(args,Map.class)).valid())
                throw new IllegalArgumentException();
        } catch(RuntimeException invalid) { throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS,"MCP arguments do not match the input schema."); }
    }
    @Override public PreparedToolOperation prepare(JsonNode args,ToolContext context) {
        var approval=new ApprovalRequest(definition.name(),null,null,"mcp_call",null,null,0,0,false,true,
                "MCP 服务："+server+"；工具："+rawName+"。参数（敏感键隐藏）："+preview(args)+"；不建立文件授权。",false);
        JsonNode copy=args.deepCopy();
        return new PreparedToolOperation(approval,()->owner.call(server,rawName,copy));
    }
    /** 审批展示有界参数；密钥类字段隐藏，控制字符转为空格。 */
    private static String preview(JsonNode args) {
        JsonNode copy=args.deepCopy(); redact(copy);
        String text=copy.toString().replaceAll("[\\p{Cntrl}]"," ");
        return text.length()>2000?text.substring(0,2000)+"…":text;
    }
    private static void redact(JsonNode node) {
        if(node.isObject()) {
            var fields=new java.util.ArrayList<String>(); node.fieldNames().forEachRemaining(fields::add);
            for(String key:fields) {
                if(key.toLowerCase(java.util.Locale.ROOT).matches(".*(key|token|password|secret|authorization|密钥|密码).*"))
                    ((com.fasterxml.jackson.databind.node.ObjectNode)node).put(key,"[hidden]");
                else redact(node.get(key));
            }
        } else if(node.isArray()) for(var value:node) redact(value);
    }
    @Override public String execute(JsonNode args,ToolContext context) throws InterruptedException {
        validateArguments(args,context); return prepare(args,context).execute(context);
    }
}
