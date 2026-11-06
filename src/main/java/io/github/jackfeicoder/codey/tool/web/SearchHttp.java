package io.github.jackfeicoder.codey.tool.web;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** 有界 JSON HTTP 请求；同一 deadline 覆盖响应头和正文，取消后不启动备用请求。 */
final class SearchHttp {
    static final ObjectMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    JsonNode send(HttpRequest request,Duration timeout)throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        var future=client.sendAsync(request,info->new Body());
        try {
            var response=future.get(timeout.toNanos(),TimeUnit.NANOSECONDS);
            int status=response.statusCode();
            if(status<200 || status>=300)throw new WebSearchFailure(switch(status){
                case 401,403->WebSearchFailure.Kind.AUTH;case 408,504->WebSearchFailure.Kind.TIMEOUT;
                case 429->WebSearchFailure.Kind.RATE_LIMIT;default->status>=500?WebSearchFailure.Kind.UNAVAILABLE:WebSearchFailure.Kind.REJECTED;},status);
            try {var node=JSON.readTree(response.body());if(node==null || !node.isObject())throw new IllegalArgumentException();return node;}
            catch(Exception invalid){throw new WebSearchFailure(WebSearchFailure.Kind.INVALID_RESPONSE);}
        }catch(InterruptedException cancelled){future.cancel(true);Thread.currentThread().interrupt();throw cancelled;}
        catch(TimeoutException timeoutError){future.cancel(true);throw new WebSearchFailure(WebSearchFailure.Kind.TIMEOUT);}
        catch(ExecutionException failed){
            Throwable cause=failed.getCause();
            while(cause instanceof CompletionException && cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof WebSearchFailure expected)throw expected;
            throw new WebSearchFailure(cause instanceof HttpTimeoutException?WebSearchFailure.Kind.TIMEOUT:WebSearchFailure.Kind.NETWORK);
        }
    }
    /** 不使用无限制 ofString/ofByteArray；超出 2MiB 立即取消订阅。 */
    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(var buffer:buffers){
                if(bytes.size()+buffer.remaining()>2*1024*1024){subscription.cancel();result.completeExceptionally(new WebSearchFailure(WebSearchFailure.Kind.INVALID_RESPONSE));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable failed){result.completeExceptionally(failed);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
