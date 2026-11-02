package io.github.jackfeicoder.codey.mcp.oauth;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 一次性的回环回调服务器；state 校验、固定路径、超时/取消后关闭，不回显 code。 */
final class OAuthCallback implements AutoCloseable {
    private final HttpServer server;
    private final CompletableFuture<String> code=new CompletableFuture<>();
    private final String state;
    OAuthCallback(String state,int port,String issuer) {
        this.state=state;
        try {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
            server.createContext("/oauth/callback",exchange->{
                int status=400;String message="Invalid authorization callback.";
                try {
                    if(!exchange.getRequestMethod().equals("GET")||!exchange.getRequestURI().getPath().equals("/oauth/callback")
                            ||!("127.0.0.1:"+server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host")))throw new IllegalArgumentException();
                    Map<String,String> params=query(exchange.getRequestURI().getRawQuery());
                    String received=params.get("state");
                    if(received==null||!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),received.getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
                    if(params.containsKey("iss")&&!issuer.equals(params.get("iss")))throw new IllegalArgumentException();
                    if(params.containsKey("error")) {code.completeExceptionally(OAuthHttp.required("用户取消或拒绝了 OAuth 授权。"));status=200;message="Authorization cancelled. Return to CodeY.";}
                    else {
                        String value=params.get("code");if(value==null||value.isBlank()||value.length()>8192||params.containsKey("access_token"))throw new IllegalArgumentException();
                        if(!code.complete(value))throw new IllegalArgumentException();status=200;message="Authorization received. Return to CodeY.";
                    }
                }catch(RuntimeException ignored){ }
                byte[] bytes=message.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");exchange.sendResponseHeaders(status,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}finally{exchange.close();}
            });server.start();
        }catch(Exception e){throw OAuthHttp.failed("OAuth 本地回调端口启动失败，请检查端口配置。");}
    }
    URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/oauth/callback");}
    String await(Duration timeout) {
        try{return code.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw OAuthHttp.required("OAuth 授权已中断。");}
        catch(ExecutionException e){throw OAuthHttp.required("OAuth 授权已取消。");}
        catch(TimeoutException e){throw OAuthHttp.required("OAuth 授权等待超时，可在下次任务中重试。");}
    }
    static Map<String,String> query(String raw) {
        if(raw==null||raw.length()>16384)throw new IllegalArgumentException();var map=new HashMap<String,String>();
        for(String part:raw.split("&")) {
            String[] pair=part.split("=",2);String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);
            String value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
            if(map.putIfAbsent(key,value)!=null)throw new IllegalArgumentException();
        }return map;
    }
    @Override public void close(){server.stop(0);code.cancel(true);}
}
