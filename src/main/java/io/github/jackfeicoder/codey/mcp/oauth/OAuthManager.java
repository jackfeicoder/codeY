package io.github.jackfeicoder.codey.mcp.oauth;

import io.github.jackfeicoder.codey.mcp.*;
import io.github.jackfeicoder.codey.tool.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** MCP 自动认证协调器：宿主线程授权，发送前刷新，手动凭证优先，业务调用失败不重放。 */
public final class OAuthManager implements AutoCloseable {
    /** 系统加密凭证库；独立于可共享的服务器配置。 */
    private final OAuthTokenStore store;
    /** 仅宿主线程触发浏览器/终端交互。 */
    private final OAuthInteraction interaction;
    /** 可注入时钟，避免用真实等待测试过期。 */
    private final Clock clock;
    /** 浏览器回调预算，不计入 MCP 启动超时。 */
    private final Duration loginTimeout;
    /** 本任务已准备的动态请求头供应器。 */
    private final Map<String,Supplier<Map<String,String>>> prepared=new HashMap<>();
    /** 任务结束清除，避免模型失败重试反复弹出授权页面。 */
    private final Map<String,ToolException> failedThisTask=new HashMap<>();
    public OAuthManager(OAuthTokenStore store,OAuthInteraction interaction){this(store,interaction,Clock.systemUTC(),Duration.ofMinutes(3));}
    public OAuthManager(OAuthTokenStore store,OAuthInteraction interaction,Clock clock,Duration loginTimeout) {
        this.store=store;this.interaction=interaction;this.clock=clock;this.loginTimeout=loginTimeout;
    }
    /** 工厂准备阶段不受 MCP 启动超时影响，也不在 SDK 回调线程等待用户输入。 */
    public McpConnection.Factory connections() {
        return new McpConnection.Factory() {
            public void prepare(McpServerConfig config){
                String id=key(config);if(failedThisTask.containsKey(id))throw failedThisTask.get(id);
                try{prepared.put(id,prepareHeaders(config));}catch(ToolException failed){failedThisTask.put(id,failed);throw failed;}
            }
            public void endTask(){prepared.clear();failedThisTask.clear();}
            public boolean resetAuthentication(McpServerConfig config){
                if(!Set.of("auto","oauth").contains(config.auth().mode()))return false;
                String id=key(config);store.delete(id);prepared.remove(id);failedThisTask.remove(id);return true;
            }
            public McpConnection open(McpServerConfig config){
                var headers=prepared.get(key(config));if(headers==null)throw OAuthHttp.failed("MCP 认证准备尚未完成。");
                return SdkMcpConnection.open(config,headers);
            }
        };
    }
    /** 可单独测试认证模块，不启动真实 MCP 会话。 */
    public Supplier<Map<String,String>> prepareHeaders(McpServerConfig config) {
        String mode=config.auth().mode();
        if(config.transport().equals("stdio")||mode.equals("manual")||mode.equals("none"))return config::headers;
        if(config.headers().keySet().stream().anyMatch(k->k.equalsIgnoreCase("Authorization")))
            throw OAuthHttp.failed("OAuth 自动模式与手动 Authorization 头冲突，请选择一种凭证来源。");
        URI resource=OAuthHttp.checked(config.url(),true);boolean local=OAuthHttp.local(resource);
        var http=new OAuthHttp(local);String key=key(config);
        OAuthTokenStore.Credentials cached=store.load(key);
        if(cached!=null) {
            validateCached(config,cached,local);
            var session=new Session(config,key,http,cached);
            try{session.current();return session::headers;}
            catch(ToolException failed){if(failed.code()!=ToolResult.ErrorCode.AUTH_REQUIRED)throw failed;}
        }
        var metadata=new OAuthDiscovery(http,local).discover(resource,mode.equals("oauth"));
        if(metadata==null)return config::headers;
        if(!interaction.available())throw OAuthHttp.required("此 MCP 需要 OAuth 授权，请在交互终端继续任务；没有发送工具操作。");
        var credentials=login(config,http,metadata,cached);
        store.save(key,credentials);
        return new Session(config,key,http,credentials,true)::headers;
    }
    private OAuthTokenStore.Credentials login(McpServerConfig config,OAuthHttp http,OAuthDiscovery.Metadata metadata,OAuthTokenStore.Credentials cached) {
        String state=random(),verifier=random();String challenge=hashUrl(verifier);
        try(var callback=new OAuthCallback(state,config.auth().callbackPort(),metadata.issuer())) {
            String clientId=config.auth().clientId();
            if(clientId==null&&cached!=null&&cached.issuer().equals(metadata.issuer()))clientId=cached.clientId();
            if(clientId==null&&metadata.registration()!=null) {
                var request=OAuthHttp.JSON.createObjectNode().put("client_name","CodeY").put("token_endpoint_auth_method","none");
                request.putArray("redirect_uris").add(callback.uri().toString());request.putArray("grant_types").add("authorization_code").add("refresh_token");
                request.putArray("response_types").add("code");
                var reply=http.post(metadata.registration(),request);
                if(reply.status()/100!=2||reply.json()==null)throw OAuthHttp.failed("OAuth 动态客户端注册失败；可配置预注册 clientId。");
                clientId=OAuthHttp.text(reply.json(),"client_id");
                String method=OAuthHttp.text(reply.json(),"token_endpoint_auth_method");
                if(method!=null&&!method.equals("none"))throw OAuthHttp.failed("服务注册返回了不支持的机密客户端认证方式。");
            }
            if(clientId==null)clientId=interaction.clientId(URI.create(metadata.issuer()),callback.uri());
            if(clientId==null||clientId.isBlank()||clientId.length()>2048||clientId.chars().anyMatch(Character::isISOControl))throw OAuthHttp.required("OAuth 需要有效的预注册公共 client_id，授权尚未完成。");
            var params=new LinkedHashMap<String,String>();
            params.put("response_type","code");params.put("client_id",clientId);params.put("redirect_uri",callback.uri().toString());
            params.put("state",state);params.put("code_challenge",challenge);params.put("code_challenge_method","S256");params.put("resource",metadata.resource());
            String scope=String.join(" ",config.auth().scopes());if(!scope.isEmpty())params.put("scope",scope);
            interaction.openBrowser(authorizationUrl(metadata.authorization(),params),scope);
            String code=callback.await(loginTimeout);
            var form=new LinkedHashMap<String,String>();form.put("grant_type","authorization_code");form.put("client_id",clientId);
            form.put("code",code);form.put("code_verifier",verifier);form.put("redirect_uri",callback.uri().toString());form.put("resource",metadata.resource());
            return tokens(http.form(metadata.token(),form),metadata,clientId,null,scope);
        }
    }
    private OAuthTokenStore.Credentials tokens(OAuthHttp.Reply reply,OAuthDiscovery.Metadata metadata,String clientId,String oldRefresh,String oldScope) {
        if(reply.status()/100!=2) {
            if("invalid_grant".equals(OAuthHttp.text(reply.json(),"error")))throw OAuthHttp.required("OAuth 授权或刷新令牌已失效，需要重新授权。");
            throw OAuthHttp.failed("OAuth 令牌请求被拒绝，请检查客户端、权限和服务配置。");
        }
        String token=OAuthHttp.text(reply.json(),"access_token"),type=OAuthHttp.text(reply.json(),"token_type");
        if(type==null||!type.equalsIgnoreCase("Bearer")||token==null||token.isBlank())throw OAuthHttp.failed("OAuth 令牌响应缺少有效 Bearer access_token。");
        long now=clock.instant().getEpochSecond();Long expiry=null;
        if(reply.json().has("expires_in")) {
            var value=reply.json().get("expires_in");if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<1||value.longValue()>31536000)throw OAuthHttp.failed("OAuth expires_in 无效。");
            expiry=now+value.longValue();
        }
        String refresh=OAuthHttp.text(reply.json(),"refresh_token");if(refresh==null)refresh=oldRefresh;
        String scope=OAuthHttp.text(reply.json(),"scope");if(scope==null)scope=oldScope;
        try{return new OAuthTokenStore.Credentials(metadata.resource(),metadata.issuer(),clientId,metadata.authorization().toString(),metadata.token().toString(),token,refresh,now,expiry,scope);}
        catch(IllegalArgumentException invalid){throw OAuthHttp.failed("OAuth 凭证格式无效。");}
    }
    private final class Session {
        private final McpServerConfig config;private final String key;private final OAuthHttp http;
        private OAuthTokenStore.Credentials credentials;
        private boolean allowUnknownLifetime;
        Session(McpServerConfig config,String key,OAuthHttp http,OAuthTokenStore.Credentials credentials){this(config,key,http,credentials,false);}
        Session(McpServerConfig config,String key,OAuthHttp http,OAuthTokenStore.Credentials credentials,boolean allowUnknownLifetime){
            this.config=config;this.key=key;this.http=http;this.credentials=credentials;this.allowUnknownLifetime=allowUnknownLifetime;
        }
        synchronized Map<String,String> headers() {
            current();var headers=new LinkedHashMap<>(config.headers());headers.put("Authorization","Bearer "+credentials.accessToken());return Map.copyOf(headers);
        }
        synchronized void current() {
            long now=clock.instant().getEpochSecond();
            // 服务未提供寿命时仅在当前连接使用；下次召唤重新刷新/授权，不假造持久有效期。
            if(credentials.fresh(now)||(credentials.expiresAt()==null&&allowUnknownLifetime))return;
            credentials=store.update(key,latest->{
                var old=latest==null?credentials:latest;validateCached(config,old,OAuthHttp.local(URI.create(config.url())));
                if(old.fresh(clock.instant().getEpochSecond()))return old;
                if(old.refreshToken()==null||old.refreshToken().isBlank())throw OAuthHttp.required("OAuth 令牌已过期，需要浏览器重新授权。");
                var form=new LinkedHashMap<String,String>();form.put("grant_type","refresh_token");form.put("refresh_token",old.refreshToken());
                form.put("client_id",old.clientId());form.put("resource",old.resource());
                var metadata=new OAuthDiscovery.Metadata(old.resource(),old.issuer(),URI.create(old.authorizationEndpoint()),URI.create(old.tokenEndpoint()),null);
                return tokens(http.form(metadata.token(),form),metadata,old.clientId(),old.refreshToken(),old.scope());
            });
            allowUnknownLifetime=credentials.expiresAt()==null;
        }
    }
    private static void validateCached(McpServerConfig config,OAuthTokenStore.Credentials credentials,boolean local) {
        if(!credentials.resource().equals(config.url())||(config.auth().clientId()!=null&&!config.auth().clientId().equals(credentials.clientId())))throw OAuthHttp.failed("OAuth 缓存与服务身份不匹配。");
        OAuthHttp.checked(credentials.issuer(),local);OAuthHttp.checked(credentials.authorizationEndpoint(),local);OAuthHttp.checked(credentials.tokenEndpoint(),local);
    }
    private static URI authorizationUrl(URI endpoint,Map<String,String> params) {
        String existing=endpoint.getRawQuery();
        if(existing!=null)for(String name:OAuthCallback.query(existing).keySet())if(params.containsKey(name))throw OAuthHttp.failed("OAuth 授权地址包含重复保留参数。");
        return URI.create(endpoint.toString()+(existing==null?"?":"&")+OAuthHttp.encode(params));
    }
    static String key(McpServerConfig config) {
        return HexFormat.of().formatHex(sha((config.name()+"\n"+config.url()+"\n"+config.auth().toString()+"\n"+new TreeMap<>(config.headers())).getBytes(StandardCharsets.UTF_8)));
    }
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String hashUrl(String text){return Base64.getUrlEncoder().withoutPadding().encodeToString(sha(text.getBytes(StandardCharsets.US_ASCII)));}
    private static byte[] sha(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    @Override public void close(){prepared.clear();failedThisTask.clear();}
}
