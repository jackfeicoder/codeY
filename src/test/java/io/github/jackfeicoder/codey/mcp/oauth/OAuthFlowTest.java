package io.github.jackfeicoder.codey.mcp.oauth;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.github.jackfeicoder.codey.mcp.*;
import io.github.jackfeicoder.codey.tool.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 本地 OAuth/MCP 服务器验证发现、PKCE、回调、刷新/轮换和真实 SDK；不打开实际浏览器。 */
class OAuthFlowTest {
    @TempDir Path root;
    private static final ObjectMapper JSON=new ObjectMapper();
    static final class Time extends Clock {
        long now=1700000000;
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochSecond(now);}
    }
    private final OAuthTokenStore.Protector protector=new OAuthTokenStore.Protector(){
        final byte[] key=new byte[32];{new SecureRandom().nextBytes(key);}
        public byte[] encrypt(byte[] clear){try{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);var c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(1,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,iv));byte[] body=c.doFinal(clear);byte[] out=Arrays.copyOf(iv,iv.length+body.length);System.arraycopy(body,0,out,12,body.length);return out;}catch(Exception e){throw new RuntimeException(e);}}
        public byte[] decrypt(byte[] encrypted){try{var c=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");c.init(2,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOf(encrypted,12)));return c.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length));}catch(Exception e){throw new RuntimeException(e);}}
    };
    private OAuthTokenStore store(){return new OAuthTokenStore(root,protector);}
    private OAuthManager manager(Fixture f){return new OAuthManager(store(),f,f.time,Duration.ofMillis(500));}
    private class Fixture implements AutoCloseable,OAuthInteraction {
        final HttpServer server;final Time time=new Time();final List<String> errors=new java.util.concurrent.CopyOnWriteArrayList<>();
        int probes,metadata,dcr,tokens,refresh,browsers,business;String challenge;boolean anonymous,denied,noCallback,badFirst,issuerCallback,expiredRefresh,noExpiry,refreshOmitsToken,noDcr,unavailable;
        boolean rejectBusiness,publicProbe;String invalid;String lastRefresh;URI authUri;String authThread;final String base;
        Fixture()throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();
            server.createContext("/",this::handle);server.start();
        }
        McpServerConfig config(){return new McpServerConfig("fixture","OAuth fixture","streamable-http",base+"/mcp",null,List.of(),Map.of(),Map.of(),null,true,Set.of(),Set.of(),2,2,null,new McpAuthSettings("auto",null,List.of("docs:read"),0));}
        public boolean available(){return !unavailable;}
        public String clientId(URI issuer,URI callback){assertEquals(base+"/issuer",issuer.toString());return "pre-registered";}
        public void openBrowser(URI uri,String scope) {
            browsers++;authUri=uri;authThread=Thread.currentThread().getName();
            var query=OAuthCallback.query(uri.getRawQuery());challenge=query.get("code_challenge");
            assertEquals("S256",query.get("code_challenge_method"));assertEquals(base+"/mcp",query.get("resource"));assertEquals("docs:read",scope);
            if(noCallback)return;
            String callback=query.get("redirect_uri"),state=query.get("state");
            if(badFirst)assertEquals(400,callback(callback+"?state=wrong&code=bad"));
            if(issuerCallback)assertEquals(400,callback(callback+"?state="+state+"&code=bad&iss="+OAuthHttp.escape("http://wrong-issuer")));
            assertEquals(200,callback(callback+"?state="+state+(denied?"&error=access_denied":"&code=fixture-code")));
        }
        private int callback(String url) {
            try{return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode();}
            catch(Exception e){throw new AssertionError(e);}
        }
        private void handle(HttpExchange e)throws java.io.IOException {
            try {
                String path=e.getRequestURI().getPath(),method=e.getRequestMethod();
                if(!path.equals("/mcp"))assertNull(e.getRequestHeaders().getFirst("Authorization"),"credential must not go to discovery/token endpoints");
                if(path.equals("/mcp")&&method.equals("GET")) {
                    probes++;
                    if(anonymous||publicProbe){reply(e,200,JSON.createObjectNode());return;}
                    if(!"no-discovery".equals(invalid))e.getResponseHeaders().set("WWW-Authenticate","Bearer resource_metadata=\""+base+"/prm\"");
                    reply(e,401,JSON.createObjectNode());return;
                }
                if(path.equals("/prm")||(publicProbe&&path.startsWith("/.well-known/oauth-protected-resource"))) {
                    metadata++;
                    if("redirect".equals(invalid)){e.getResponseHeaders().set("Location","https://example.invalid/metadata");reply(e,302,JSON.createObjectNode());return;}
                    var n=JSON.createObjectNode().put("resource","resource".equals(invalid)?base+"/wrong":base+"/mcp");n.putArray("authorization_servers").add(base+"/issuer");reply(e,200,n);return;
                }
                if(path.startsWith("/.well-known/oauth-authorization-server")) {
                    var n=JSON.createObjectNode().put("issuer","issuer".equals(invalid)?base+"/wrong":base+"/issuer")
                            .put("authorization_endpoint",base+"/authorize").put("token_endpoint",base+"/token");
                    if(!noDcr)n.put("registration_endpoint",base+"/register");
                    n.putArray("code_challenge_methods_supported").add("pkce".equals(invalid)?"plain":"S256");
                    n.putArray("token_endpoint_auth_methods_supported").add("none");reply(e,200,n);return;
                }
                if(path.equals("/register")) {
                    dcr++;var n=JSON.readTree(e.getRequestBody());assertEquals("none",n.path("token_endpoint_auth_method").asText());
                    assertTrue(n.path("redirect_uris").get(0).asText().startsWith("http://127.0.0.1:"));reply(e,201,JSON.createObjectNode().put("client_id","fixture-public").put("token_endpoint_auth_method","none"));return;
                }
                if(path.equals("/token")) {
                    var form=OAuthCallback.query(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    assertEquals(base+"/mcp",form.get("resource"));
                    if(form.get("grant_type").equals("authorization_code")) {
                        assertEquals("fixture-code",form.get("code"));
                        String expected=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                        assertEquals(challenge,expected);
                    } else {
                        refresh++;lastRefresh=form.get("refresh_token");
                        if(expiredRefresh){reply(e,400,JSON.createObjectNode().put("error","invalid_grant").put("error_description","fixture-private-secret"));return;}
                    }
                    tokens++;var n=JSON.createObjectNode().put("token_type","Bearer").put("access_token","access-"+tokens);
                    if(!noExpiry)n.put("expires_in",60);
                    if(!refreshOmitsToken||refresh==0)n.put("refresh_token","refresh-"+tokens);
                    reply(e,200,n);return;
                }
                if(path.equals("/mcp")&&method.equals("POST")) {
                    assertEquals("Bearer access-"+tokens,e.getRequestHeaders().getFirst("Authorization"));
                    var request=JSON.readTree(e.getRequestBody());if(!request.has("id")){e.sendResponseHeaders(202,-1);return;}
                    var response=JSON.createObjectNode().put("jsonrpc","2.0");response.set("id",request.get("id"));var n=response.putObject("result");
                    switch(request.path("method").asText()) {
                        case "initialize" -> {n.put("protocolVersion",request.path("params").path("protocolVersion").asText());n.putObject("capabilities").putObject("tools");n.putObject("serverInfo").put("name","fixture").put("version","1");}
                        case "tools/list" -> n.putArray("tools").addObject().put("name","ping").put("description","Ping fixture").putObject("inputSchema").put("type","object");
                        case "tools/call" -> {business++;if(rejectBusiness){reply(e,401,JSON.createObjectNode());return;}n.putArray("content").addObject().put("type","text").put("text","fixture result");n.put("isError",false);}
                        default -> throw new IllegalArgumentException();
                    }
                    reply(e,200,response);return;
                }
                reply(e,404,JSON.createObjectNode());
            } catch(Throwable error){errors.add(error.toString());reply(e,500,JSON.createObjectNode());}
            finally {e.close();}
        }
        private void reply(HttpExchange e,int status,JsonNode node)throws java.io.IOException {
            byte[] bytes=JSON.writeValueAsBytes(node);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
        }
        public void close(){server.stop(0);assertTrue(errors.isEmpty(),errors.toString());}
    }
    @Test void firstLoginDiscoversUsesPkceAndPersistsEncryptedCredentials()throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            var headers=manager.prepareHeaders(f.config());assertEquals("Bearer access-1",headers.get().get("Authorization"));
            assertEquals(1,f.browsers);assertEquals(1,f.dcr);assertEquals(1,f.tokens);
            var credential=store().load(OAuthManager.key(f.config()));assertEquals("refresh-1",credential.refreshToken());
            try(var files=Files.list(root.resolve("mcp/oauth"))) {var file=files.filter(p->p.toString().endsWith(".bin")).findFirst().orElseThrow();assertFalse(new String(Files.readAllBytes(file),StandardCharsets.ISO_8859_1).contains("access-1"));}
        }
    }
    @Test void restartReusesTokenAndRefreshRotatesOnlyOnceAcrossSessions()throws Exception {
        try(var f=new Fixture()) {
            try(var first=manager(f)){first.prepareHeaders(f.config());}
            f.unavailable=true;
            try(var second=manager(f);var third=manager(f)) {
                var a=second.prepareHeaders(f.config());var b=third.prepareHeaders(f.config());assertEquals(1,f.browsers);assertEquals(1,f.probes);
                f.time.now+=61;assertEquals("Bearer access-2",a.get().get("Authorization"));assertEquals("Bearer access-2",b.get().get("Authorization"));
                assertEquals(1,f.refresh);assertEquals("refresh-1",f.lastRefresh);assertEquals("refresh-2",store().load(OAuthManager.key(f.config())).refreshToken());
            }
        }
    }
    @Test void rejectedRefreshNeedsInteractiveAuthorizationAndDoesNotLeakErrorBody()throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            var headers=manager.prepareHeaders(f.config());f.time.now+=61;f.expiredRefresh=true;
            var failed=assertThrows(ToolException.class,headers::get);assertEquals(ToolResult.ErrorCode.AUTH_REQUIRED,failed.code());assertFalse(failed.getMessage().contains("fixture-private-secret"));
            manager.prepareHeaders(f.config());assertEquals(2,f.browsers);assertEquals(1,f.dcr);
        }
    }
    @Test void incorrectStateAndIssuerAreRejectedBeforeValidCallback()throws Exception {
        try(var f=new Fixture();var manager=manager(f)){f.badFirst=true;f.issuerCallback=true;manager.prepareHeaders(f.config());assertEquals(1,f.tokens);}
    }
    @Test void browserDenialDoesNotExchangeOrPersistTokens()throws Exception {
        try(var f=new Fixture();var manager=manager(f)){f.denied=true;assertEquals(ToolResult.ErrorCode.AUTH_REQUIRED,assertThrows(ToolException.class,()->manager.prepareHeaders(f.config())).code());assertEquals(0,f.tokens);assertNull(store().load(OAuthManager.key(f.config())));}
    }
    @Test void callbackTimeoutClosesListenerAndKeepsCacheAbsent()throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            f.noCallback=true;assertThrows(ToolException.class,()->manager.prepareHeaders(f.config()));assertNull(store().load(OAuthManager.key(f.config())));
            URI callback=URI.create(OAuthCallback.query(f.authUri.getRawQuery()).get("redirect_uri"));
            assertThrows(Exception.class,()->HttpClient.newHttpClient().send(HttpRequest.newBuilder(callback).GET().build(),HttpResponse.BodyHandlers.discarding()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"resource","issuer","pkce","redirect","no-discovery"})
    void rejectsInvalidDiscoveryWithoutOpeningBrowser(String invalid)throws Exception {
        try(var f=new Fixture();var manager=manager(f)){f.invalid=invalid;assertThrows(ToolException.class,()->manager.prepareHeaders(f.config()));assertEquals(0,f.browsers);assertEquals(0,f.tokens);}
    }
    @Test void publicGetProbeStillFindsOAuthMetadataForProtectedPost() throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            f.publicProbe=true;assertEquals("Bearer access-1",manager.prepareHeaders(f.config()).get().get("Authorization"));
            assertEquals(1,f.browsers);assertEquals(1,f.tokens);
        }
    }
    @Test void manualBearerBypassesDiscoveryAndAnonymousServerNeedsNoCredentials()throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            var c=f.config();var manual=new McpServerConfig(c.name(),c.description(),c.transport(),c.url(),null,List.of(),Map.of(),Map.of("Authorization","Bearer configured"),null,true,Set.of(),Set.of(),2,2,null);
            assertEquals("Bearer configured",manager.prepareHeaders(manual).get().get("Authorization"));assertEquals(0,f.probes);
            f.anonymous=true;assertTrue(manager.prepareHeaders(c).get().isEmpty());assertEquals(0,f.browsers);assertEquals(0,f.tokens);
        }
    }
    @Test void preRegisteredClientIsPromptedWhenDcrIsAbsent()throws Exception {
        try(var f=new Fixture();var manager=manager(f)){f.noDcr=true;manager.prepareHeaders(f.config());assertEquals(0,f.dcr);assertEquals("pre-registered",store().load(OAuthManager.key(f.config())).clientId());}
    }
    @Test void headlessModeDoesNotStartBrowserAndRequiresAuthorization()throws Exception {
        try(var f=new Fixture();var manager=new OAuthManager(store(),OAuthInteraction.unavailable(),f.time,Duration.ofMillis(200))) {
            assertEquals(ToolResult.ErrorCode.AUTH_REQUIRED,assertThrows(ToolException.class,()->manager.prepareHeaders(f.config())).code());assertEquals(0,f.browsers);assertEquals(0,f.dcr);
        }
    }
    @Test void omittedRefreshTokenPreservesPreviousAndUnknownExpiryIsConnectionOnly()throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            f.noExpiry=true;var headers=manager.prepareHeaders(f.config());f.time.now+=100;headers.get();assertEquals(0,f.refresh);
            f.refreshOmitsToken=true;manager.prepareHeaders(f.config());assertEquals(1,f.refresh);assertEquals("refresh-1",store().load(OAuthManager.key(f.config())).refreshToken());
        }
    }
    @Test void realMcpSdkGetsFreshBearerBeforeCallWithoutBusinessReplay()throws Exception {
        try(var f=new Fixture();var auth=manager(f);var manager=new McpConnectionManager(new ToolRegistry(),List.of(f.config()),auth.connections())) {
            manager.summon("fixture");assertEquals(Thread.currentThread().getName(),f.authThread);f.time.now+=61;
            assertEquals("fixture result",manager.call("fixture","ping",JSON.createObjectNode()));assertEquals(1,f.refresh);assertEquals(1,f.business);
            manager.releaseAll();assertFalse(manager.active("fixture"));
        }
    }
    @Test void taskDoesNotRepeatedlyOpenRejectedLoginAndCanRetryNextTask()throws Exception {
        try(var f=new Fixture();var auth=manager(f)) {
            f.denied=true;var factory=auth.connections();assertThrows(ToolException.class,()->factory.prepare(f.config()));assertThrows(ToolException.class,()->factory.prepare(f.config()));assertEquals(1,f.browsers);
            factory.endTask();f.denied=false;factory.prepare(f.config());assertEquals(2,f.browsers);
        }
    }
    @Test void credentialsAreBoundToResourceAndClientConfiguration()throws Exception {
        try(var f=new Fixture();var manager=manager(f)) {
            manager.prepareHeaders(f.config());var original=store().load(OAuthManager.key(f.config()));
            store().save(OAuthManager.key(f.config()),new OAuthTokenStore.Credentials(f.base+"/other",original.issuer(),original.clientId(),original.authorizationEndpoint(),original.tokenEndpoint(),original.accessToken(),original.refreshToken(),original.obtainedAt(),original.expiresAt(),original.scope()));
            assertThrows(ToolException.class,()->manager.prepareHeaders(f.config()));assertEquals(1,f.browsers);
        }
    }
    @Test void unauthorizedBusinessCallIsNotReplayedAndUserCanResetLocalCredentials() throws Exception {
        try(var f=new Fixture();var auth=manager(f);var manager=new McpConnectionManager(new ToolRegistry(),List.of(f.config()),auth.connections())) {
            manager.summon("fixture");f.rejectBusiness=true;
            assertThrows(ToolException.class,()->manager.call("fixture","ping",JSON.createObjectNode()));assertEquals(1,f.business);
            assertTrue(manager.resetAuthentication("fixture"));assertNull(store().load(OAuthManager.key(f.config())));assertEquals(1,manager.names().size());
            f.rejectBusiness=false;manager.summon("fixture");assertEquals(2,f.browsers);
        }
    }
    @Test void nonHttpsAndRemoteLoopbackDiscoveryAreRejected() {
        assertThrows(ToolException.class,()->OAuthHttp.checked("http://example.com/token",true));
        assertThrows(ToolException.class,()->OAuthHttp.checked("http://127.0.0.1/token",false));
        assertThrows(ToolException.class,()->OAuthHttp.checked("https://user:pass@example.com/token",false));
    }
}
