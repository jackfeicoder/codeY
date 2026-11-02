package io.github.jackfeicoder.codey.mcp.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/** RFC9728 资源发现与 RFC8414/OIDC 发行者发现，严格绑定 resource/issuer，不把 401 当作 OAuth 充分条件。 */
final class OAuthDiscovery {
    record Metadata(String resource,String issuer,URI authorization,URI token,URI registration) { }
    private static final Pattern RESOURCE=Pattern.compile("(?:^|[,\\s])resource_metadata\\s*=\\s*(?:\"([^\"]+)\"|([^,\\s]+))",Pattern.CASE_INSENSITIVE);
    private final OAuthHttp http;
    private final boolean local;
    OAuthDiscovery(OAuthHttp http,boolean local){this.http=http;this.local=local;}
    Metadata discover(URI resource,boolean required) {
        var probe=http.probe(resource);
        if(probe.status()/100!=2&&probe.status()!=401&&probe.status()!=405&&probe.status()!=404&&!required)throw OAuthHttp.failed("MCP 认证探测返回非预期 HTTP 状态。");
        JsonNode prm=null;
        for(String header:probe.headers().allValues("WWW-Authenticate")) {
            var bearer=Pattern.compile("(?:^|,)\\s*Bearer(?:\\s+|$)",Pattern.CASE_INSENSITIVE).matcher(header);
            if(!bearer.find())continue;
            var matcher=RESOURCE.matcher(header.substring(bearer.end()));
            if(matcher.find()) {
                URI uri=OAuthHttp.checked(matcher.group(1)!=null?matcher.group(1):matcher.group(2),local);
                var response=http.get(uri);if(response.status()!=200)throw OAuthHttp.failed("OAuth 资源元数据获取失败。");prm=response.json();break;
            }
        }
        if(prm==null) {
            for(URI uri:wellKnown(resource,"oauth-protected-resource")) {
                var response=http.get(uri);if(response.status()==200){prm=response.json();break;}
                if(response.status()!=404&&response.status()!=405)throw OAuthHttp.failed("OAuth 资源元数据发现失败。");
            }
        }
        if(prm==null) {
            if(required||probe.status()==401)throw OAuthHttp.required("服务要求认证但未提供可用 OAuth 发现信息；请在注册配置中提供 Token/客户端信息。");
            return null;
        }
        if(!resource.toString().equals(OAuthHttp.text(prm,"resource")))throw OAuthHttp.failed("OAuth resource 与当前 MCP 地址不匹配。");
        JsonNode issuers=prm.get("authorization_servers");
        if(issuers==null||!issuers.isArray()||issuers.isEmpty()||!issuers.get(0).isTextual())throw OAuthHttp.failed("OAuth 发行者信息缺失。");
        URI issuer=OAuthHttp.checked(issuers.get(0).textValue(),local);if(issuer.getQuery()!=null)throw OAuthHttp.failed("OAuth issuer 包含无效查询参数。");
        JsonNode metadata=null;
        var locations=new ArrayList<>(wellKnown(issuer,"oauth-authorization-server"));
        locations.add(URI.create(issuer.toString().replaceAll("/$","")+"/.well-known/openid-configuration"));
        for(URI uri:locations) {
            var response=http.get(uri);if(response.status()==200){metadata=response.json();break;}
            if(response.status()!=404&&response.status()!=405)throw OAuthHttp.failed("OAuth 授权服务器发现失败。");
        }
        if(metadata==null||!issuer.toString().equals(OAuthHttp.text(metadata,"issuer")))throw OAuthHttp.failed("OAuth issuer 校验失败。");
        if(!contains(metadata.get("code_challenge_methods_supported"),"S256"))throw OAuthHttp.failed("OAuth 服务未声明支持 PKCE S256。");
        if(metadata.has("response_types_supported")&&!contains(metadata.get("response_types_supported"),"code"))throw OAuthHttp.failed("OAuth 服务不支持授权码流程。");
        if(metadata.has("token_endpoint_auth_methods_supported")&&!contains(metadata.get("token_endpoint_auth_methods_supported"),"none"))throw OAuthHttp.failed("当前 OAuth 只支持公共客户端（token endpoint auth=none）。");
        URI authorize=OAuthHttp.checked(OAuthHttp.text(metadata,"authorization_endpoint"),local),token=OAuthHttp.checked(OAuthHttp.text(metadata,"token_endpoint"),local);
        String registration=OAuthHttp.text(metadata,"registration_endpoint");
        return new Metadata(resource.toString(),issuer.toString(),authorize,token,registration==null?null:OAuthHttp.checked(registration,local));
    }
    private static boolean contains(JsonNode array,String value){if(array==null||!array.isArray())return false;for(var n:array)if(n.isTextual()&&n.textValue().equals(value))return true;return false;}
    private static List<URI> wellKnown(URI uri,String type) {
        String origin=uri.getScheme()+"://"+uri.getRawAuthority(),path=uri.getRawPath();var result=new ArrayList<URI>();
        if(path!=null&&!path.isEmpty()&&!path.equals("/"))result.add(URI.create(origin+"/.well-known/"+type+path));
        result.add(URI.create(origin+"/.well-known/"+type));return result;
    }
}
