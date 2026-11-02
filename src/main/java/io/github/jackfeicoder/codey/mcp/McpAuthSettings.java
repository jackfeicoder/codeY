package io.github.jackfeicoder.codey.mcp;

import java.util.*;

/** HTTP 认证策略；AUTO 发现 OAuth，手动凭证不被自动覆盖；客户端仅支持公共 PKCE。 */
public record McpAuthSettings(String mode,String clientId,List<String> scopes,int callbackPort) {
    public McpAuthSettings {
        if(!Set.of("auto","oauth","none","manual").contains(mode)||callbackPort<0||callbackPort>65535)
            throw new IllegalArgumentException("Invalid MCP auth settings.");
        if(clientId!=null&&(clientId.isBlank()||clientId.length()>2048||clientId.chars().anyMatch(Character::isISOControl)))
            throw new IllegalArgumentException("Invalid OAuth client ID.");
        scopes=List.copyOf(scopes);
        if(scopes.size()>32||scopes.stream().anyMatch(s->s.isEmpty()||s.length()>256
                ||s.chars().anyMatch(c->c<33||c>126||c==34||c==92)))
            throw new IllegalArgumentException("Invalid OAuth scopes.");
    }
    public static McpAuthSettings legacy(String transport,Map<String,String> headers) {
        return new McpAuthSettings(transport.equals("stdio")?"none":headers.isEmpty()?"auto":"manual",null,List.of(),0);
    }
}
