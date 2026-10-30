package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.github.jackfeicoder.codey.tool.*;

/** 保留正文/结构化结果；富媒体仅给出类型诊断，不把 base64 灌入历史。 */
public final class McpResultMapper {
    private static final ObjectMapper JSON=new ObjectMapper();
    private McpResultMapper() { }
    public static String map(McpSchema.CallToolResult result) {
        if(result==null) throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED,"MCP returned no result.");
        StringBuilder out=new StringBuilder();
        if(result.content()!=null) for(var block:result.content()) {
            if(block instanceof McpSchema.TextContent text) append(out,text.text());
            else append(out,"[MCP content type: " + block.type() + "; rich content is not loaded in this version.]");
        }
        if(result.structuredContent()!=null) append(out,JSON.valueToTree(result.structuredContent()).toString());
        if(Boolean.TRUE.equals(result.isError())) throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED,
                "MCP tool reported an error. " + out);
        return out.toString();
    }
    private static void append(StringBuilder out,String text) {
        int remaining=32000-out.length(); if(remaining<=0) return;
        if(out.length()>0) out.append('\n');
        if(text!=null) out.append(text,0,Math.min(text.length(),Math.max(0,remaining-1)));
    }
}
