package io.github.jackfeicoder.codey.mcp.oauth;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.tool.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 有界 OAuth HTTP：无重定向、不携带 MCP 凭证读取发现文档，错误不回显响应正文。 */
final class OAuthHttp {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(8)).build();
    private final boolean loopback;
    OAuthHttp(boolean loopback){this.loopback=loopback;}
    record Reply(int status,HttpHeaders headers,JsonNode json) { }
    static boolean local(URI uri){return Set.of("127.0.0.1","[::1]","::1","localhost").contains(uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT));}
    static URI checked(String text,boolean localAllowed) {
        try {
            URI uri=URI.create(text);
            if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()>65535||uri.getPort()==0
                    ||(!"https".equals(uri.getScheme())&&!("http".equals(uri.getScheme())&&localAllowed&&local(uri)))
                    ||(local(uri)&&!localAllowed))throw new IllegalArgumentException();
            return uri;
        }catch(RuntimeException e){throw failed("OAuth 端点必须使用 HTTPS（本地测试可使用回环 HTTP），且不能包含用户信息或片段。");}
    }
    Reply probe(URI uri) {
        checked(uri.toString(),loopback);
        var future=http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("Accept","application/json, text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        try {
            var response=future.get(8,TimeUnit.SECONDS);
            try(var body=response.body()){return new Reply(response.statusCode(),response.headers(),null);}
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw required("OAuth 已中断。");}
        catch(Exception e){throw failed("OAuth 服务探测失败或超时。");}
        finally {future.cancel(true);}
    }
    Reply get(URI uri){return request(uri,"GET",null,null);}
    Reply form(URI uri,Map<String,String> fields){return request(uri,"POST","application/x-www-form-urlencoded",encode(fields));}
    Reply post(URI uri,JsonNode body){return request(uri,"POST","application/json",body.toString());}
    private Reply request(URI uri,String method,String contentType,String body) {
        checked(uri.toString(),loopback);
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept","application/json");
        if(body==null)builder.GET();else builder.header("Content-Type",contentType).method(method,HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
        var future=http.sendAsync(builder.build(),info->new LimitedBody());
        try {
            var response=future.get(10,TimeUnit.SECONDS);JsonNode json=null;
            if(response.body().length>0) {
                try {json=JSON.readTree(response.body());}catch(Exception invalid){if(response.statusCode()/100==2)throw invalid;}
            }
            return new Reply(response.statusCode(),response.headers(),json);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw required("OAuth 已中断。");}
        catch(Exception e){throw failed("OAuth HTTP 请求失败、超时或响应超限/格式不正确。");}
        finally {future.cancel(true);}
    }
    static String encode(Map<String,String> fields){return fields.entrySet().stream().map(e->escape(e.getKey())+"="+escape(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String escape(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    static String text(JsonNode n,String key){return n!=null&&n.path(key).isTextual()?n.path(key).textValue():null;}
    static ToolException failed(String message){return new ToolException(ToolResult.ErrorCode.AUTH_FAILED,message);}
    static ToolException required(String message){return new ToolException(ToolResult.ErrorCode.AUTH_REQUIRED,message);}
    /** 限制聚合前的响应字节，防止异常响应造成无界内存使用。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private Flow.Subscription subscription;private int bytes;
        LimitedBody(){delegate.getBody().whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);else result.complete(v);});}
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
        public void onNext(List<ByteBuffer> chunks){
            for(var chunk:chunks) {bytes+=chunk.remaining();if(bytes>131072){subscription.cancel();result.completeExceptionally(new IllegalStateException());return;}}
            delegate.onNext(chunks);
        }
        public void onError(Throwable e){delegate.onError(e);}
        public void onComplete(){delegate.onComplete();}
    }
}
