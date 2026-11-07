package io.github.jackfeicoder.codey.tool.web;

import io.github.jackfeicoder.codey.config.WebFetchSettings;
import io.github.jackfeicoder.codey.tool.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/** 公共网页下载：连接使用已校验DNS结果；总超时、字节限制和取消覆盖响应体。 */
public final class FetchHttpClient {
    public record Download(URI finalUrl,URI redirectUrl,String contentType,byte[] bytes) { }
    private final WebAddressPolicy policy;
    private final WebFetchSettings settings;
    /** 有界工作线程，防止不可中断的系统DNS查询导致无限创建线程。 */
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"codey-web-fetch");t.setDaemon(true);return t;});
    public FetchHttpClient(WebFetchSettings settings,WebAddressPolicy policy){this.settings=settings;this.policy=policy;}
    public Download fetch(URI uri)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.timeoutSeconds());
        var active=new java.util.concurrent.atomic.AtomicReference<HttpGet>();
        Future<Download> future;
        try {future=workers.submit(()->download(uri,deadline,active));}
        catch(RejectedExecutionException busy){throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Fetch workers busy; retry later.");}
        try{return future.get(settings.timeoutSeconds(),TimeUnit.SECONDS);}
        catch(InterruptedException cancelled){cancel(active,future);Thread.currentThread().interrupt();throw cancelled;}
        catch(TimeoutException timeout){cancel(active,future);throw error(ToolResult.ErrorCode.WEB_FETCH_TIMEOUT,"Web fetch exceeded total deadline.");}
        catch(ExecutionException failed){
            if(failed.getCause() instanceof ToolException expected)throw expected;
            if(failed.getCause() instanceof java.net.SocketTimeoutException)throw error(ToolResult.ErrorCode.WEB_FETCH_TIMEOUT,"Web fetch connection or response timed out.");
            throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Web fetch failed due to a network/TLS/response error.");
        }
    }
    private static void cancel(java.util.concurrent.atomic.AtomicReference<HttpGet> active,Future<?> future){var request=active.get();if(request!=null)request.cancel();future.cancel(true);}
    private Download download(URI initial,long deadline,java.util.concurrent.atomic.AtomicReference<HttpGet> active)throws Exception {
        DnsResolver dns=new DnsResolver(){
            public InetAddress[] resolve(String host)throws UnknownHostException{return policy.resolve(host);}
            public String resolveCanonicalHostname(String host){return host;}
        };
        var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns).build();
        try(var client=HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableAuthCaching().disableContentCompression().build()) {
            URI current=initial;var visited=new HashSet<URI>();
            for(int hops=0;hops<=3;hops++) {
                check(deadline);if(!visited.add(current))throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Redirect loop detected.");
                long ms=Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                var get=new HttpGet(current);active.set(get);
                get.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(Math.min(ms,10000)))
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(ms)).setResponseTimeout(Timeout.ofMilliseconds(ms)).build());
                get.setHeader("Accept","text/html,text/markdown,text/plain;q=0.9");get.setHeader("Accept-Encoding","identity");
                get.setHeader("User-Agent","CodeY-WebFetch/0.1");check(deadline);
                var response=client.executeOpen(null,get,null);
                try {
                    check(deadline);int status=response.getCode();
                    if(Set.of(301,302,303,307,308).contains(status)) {
                        var header=response.getFirstHeader("Location");if(header==null)throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Redirect missing Location.");
                        URI next=policy.normalize(current.resolve(header.getValue()).toString());
                        if(!current.getHost().equalsIgnoreCase(next.getHost()))return new Download(current,next,"",new byte[0]);
                        current=next;continue;
                    }
                    if(status<200 || status>=300)throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Web fetch HTTP "+status+"; no automatic retry or authentication forwarding.");
                    var entity=response.getEntity();if(entity==null)throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Page has no response body.");
                    String type=entity.getContentType();if(type==null)throw error(ToolResult.ErrorCode.WEB_FETCH_UNSUPPORTED,"Page did not declare a supported content type.");
                    if(!WebContentExtractor.supports(type))throw error(ToolResult.ErrorCode.WEB_FETCH_UNSUPPORTED,"Only HTML, Markdown and plain text are supported; no browser/PDF execution.");
                    if(entity.getContentLength()>settings.maxBytes())throw tooLarge();
                    try(var raw=new LimitedInput(entity.getContent(),settings.maxBytes())) {
                        String encoding=entity.getContentEncoding();
                        InputStream decoded=raw;
                        if(encoding!=null && !encoding.equalsIgnoreCase("identity")) {
                            if(!encoding.equalsIgnoreCase("gzip"))throw error(ToolResult.ErrorCode.WEB_FETCH_UNSUPPORTED,"Unsupported response compression.");
                            decoded=new GZIPInputStream(raw);
                        }
                        try(var bounded=new LimitedInput(decoded,settings.maxBytes())) {
                            var bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                            while((count=bounded.read(buffer))!=-1){check(deadline);bytes.write(buffer,0,count);}
                            return new Download(current,null,type,bytes.toByteArray());
                        }
                    }
                }finally{get.cancel();try{response.close();}catch(IOException ignored){/* 取消后关闭失败不覆盖原始错误。 */}}
            }
            throw error(ToolResult.ErrorCode.WEB_FETCH_FAILED,"Too many same-host redirects.");
        }finally{active.set(null);}
    }
    private static void check(long deadline)throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(System.nanoTime()>=deadline)throw error(ToolResult.ErrorCode.WEB_FETCH_TIMEOUT,"Web fetch exceeded total deadline.");
    }
    /** 编码前后都受硬上限控制，解压炸弹和分块响应不能绕过Content-Length检查。 */
    private static final class LimitedInput extends FilterInputStream {
        private int remaining;
        LimitedInput(InputStream input,int limit){super(input);remaining=limit;}
        @Override public int read()throws IOException{int value=super.read();if(value>=0 && --remaining<0)throw tooLarge();return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int count=in.read(b,off,Math.min(len,remaining+1));if(count>0 && (remaining-=count)<0)throw tooLarge();return count;}
        // 响应体统一在request.cancel后关闭，避免流关闭时为了复用连接无限排空超大正文。
        @Override public void close()throws IOException{if(in instanceof GZIPInputStream)in.close();}
    }
    static ToolException tooLarge(){return error(ToolResult.ErrorCode.WEB_FETCH_TOO_LARGE,"Page exceeds compressed, decoded or extracted content limit.");}
    static ToolException error(ToolResult.ErrorCode code,String text){return new ToolException(code,text);}
}
