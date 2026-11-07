package io.github.jackfeicoder.codey.tool.web;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.WebFetchTool;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.agent.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/** 本地HTTP隔离验证；生产地址限制没有本地放行配置，测试通过包内策略注入。 */
@Timeout(15)
class WebFetchModuleTest {
    @TempDir Path directory;
    HttpServer server;ExecutorService workers;final AtomicInteger requests=new AtomicInteger();
    final AtomicReference<String> authorization=new AtomicReference<>(),cookie=new AtomicReference<>();
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(workers);server.start();
    }
    @AfterEach void stop(){server.stop(0);workers.shutdownNow();Thread.interrupted();}
    String url(String path){return "http://a.test:"+server.getAddress().getPort()+path;}
    WebAddressPolicy fixtures(){return new WebAddressPolicy(host->new InetAddress[]{InetAddress.getByName("127.0.0.1")},true);}
    WebFetchSettings settings(){return new WebFetchSettings(true,2,2*1024*1024,10);}
    WebFetchService service(){return new WebFetchService(settings(),fixtures());}
    void endpoint(String path,int status,String type,String body,int delay){
        server.createContext(path,exchange->{requests.incrementAndGet();authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            try{if(delay>0)Thread.sleep(delay);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);}catch(Exception ignored){}finally{exchange.close();}});
    }
    void redirect(String path,String location){server.createContext(path,e->{requests.incrementAndGet();e.getResponseHeaders().set("Location",location);e.sendResponseHeaders(302,-1);e.close();});}
    ToolContext context(PermissionMode mode,WorkMode work,ApprovalHandler handler){var binding=WorkspaceBinding.none();return new ToolContext(binding,16000,work,new PermissionService(mode,binding,handler));}
    ToolResult execute(WebFetchService s,String args,ToolContext c){return new ToolExecutor(new ToolRegistry().register(new WebFetchTool(s))).execute(new ToolCall("fetch","web_fetch",args),c);}

    @Test void extractsMainBodyAndPreservesCodeLinksAndUnicode()throws Exception {
        endpoint("/page",200,"text/html; charset=UTF-8","<html><title>Java 文档</title><nav>IGNORE_NAV</nav><main><h1>标题</h1><p>正文<a href='/next'>来源</a></p><pre><code>one\n  two</code></pre><script>IGNORE_SCRIPT</script></main></html>",0);
        var result=json.readTree(service().fetch(new WebFetchRequest(url("/page"),1,120),16000));
        assertEquals("Java 文档",result.path("title").asText());String content=result.path("content").asText();
        assertTrue(content.contains("# 标题"));assertTrue(content.contains("```") && content.contains("  two"));assertTrue(content.contains(url("/next")));
        assertFalse(content.contains("IGNORE_NAV"));assertFalse(content.contains("IGNORE_SCRIPT"));assertNull(authorization.get());assertNull(cookie.get());
        assertFalse(result.path("hasMore").asBoolean());assertEquals(64,result.path("contentHash").asText().length());
    }
    @Test void paginationUsesSameSnapshotAndDoesNotDownloadAgain()throws Exception {
        endpoint("/lines",200,"text/plain","one\ntwo\nthree\nfour",0);var service=service();
        var first=json.readTree(service.fetch(new WebFetchRequest(url("/lines"),1,2),16000));
        var next=json.readTree(service.fetch(new WebFetchRequest(url("/lines"),3,2),16000));
        assertTrue(first.path("hasMore").asBoolean());assertEquals(3,first.path("nextLine").asInt());assertFalse(first.path("truncated").asBoolean());
        assertEquals(first.path("contentHash"),next.path("contentHash"));assertEquals(first.path("fetchedAt"),next.path("fetchedAt"));assertTrue(next.path("cacheHit").asBoolean());assertEquals(1,requests.get());
        service.resetTask();service.fetch(new WebFetchRequest(url("/lines"),1,2),16000);assertEquals(2,requests.get());
    }
    @Test void longLinesWrapAndOutputBudgetHasProgressWithoutBreakingJson()throws Exception {
        endpoint("/long",200,"text/plain","汉".repeat(10000),0);var service=service();
        String body=service.fetch(new WebFetchRequest(url("/long"),1,120),3000);var page=json.readTree(body);
        assertTrue(page.path("truncated").asBoolean());assertTrue(page.path("nextLine").asInt()>1);assertTrue(body.codePointCount(0,body.length())<=3000);
        assertDoesNotThrow(()->json.readTree(service.fetch(new WebFetchRequest(url("/long"),page.path("nextLine").asInt(),120),3000)));
    }
    @Test void sameHostRedirectFollowsAndCrossHostRedirectDoesNot()throws Exception {
        redirect("/same","/target");endpoint("/target",200,"text/plain","target",0);
        var service=service();assertEquals(url("/target"),json.readTree(service.fetch(new WebFetchRequest(url("/same"),1,120),16000)).path("finalUrl").asText());
        redirect("/cross","http://b.test:"+server.getAddress().getPort()+"/target");
        var cross=json.readTree(service.fetch(new WebFetchRequest(url("/cross"),1,120),16000));
        assertTrue(cross.path("redirectUrl").asText().contains("b.test"));assertEquals("",cross.path("content").asText());assertEquals(3,requests.get());
    }
    @Test void redirectLoopFailsWithoutUnlimitedRequests() {
        redirect("/loop","/loop");assertThrows(ToolException.class,()->service().fetch(new WebFetchRequest(url("/loop"),1,120),16000));assertEquals(1,requests.get());
    }
    @ParameterizedTest @ValueSource(strings={"file:///C:/test","http://localhost/x","https://user:pass@example.com/x","https://127.0.0.1/x","https://example.com:8443/x","https://example.com/x?token=secret","ftp://example.com/x","https://[::1]/"})
    void productionRejectsPrivateOrAuthenticatedUrls(String url){assertThrows(ToolException.class,()->new WebAddressPolicy().normalize(url));assertEquals(0,requests.get());}
    @Test void httpUpgradePreservesEscapedPathAndDropsOnlyAuthorityPort() {
        assertEquals("https://example.com/a%2Fb:80?x=1",new WebAddressPolicy().normalize("http://example.com:80/a%2Fb:80?x=1#section").toString());
    }
    @ParameterizedTest @ValueSource(strings={"127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","198.18.0.1","224.0.0.1","0.0.0.0","::1","fc00::1","fe80::1","2001:db8::1","64:ff9b::7f00:1"})
    void privateDnsResultsAreBlocked(String ip)throws Exception {assertFalse(WebAddressPolicy.publicAddress(InetAddress.getByName(ip)));}
    @Test void mixedDnsAnswersFailClosedAtActualConnectionBeforeHttp()throws Exception {
        endpoint("/page",200,"text/plain","never",0);
        var policy=new WebAddressPolicy(host->new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")},false);
        var failure=assertThrows(ToolException.class,()->new FetchHttpClient(settings(),policy).fetch(URI.create("https://public.example/page")));
        assertEquals(ToolResult.ErrorCode.WEB_FETCH_BLOCKED,failure.code());assertEquals(0,requests.get());
    }
    @Test void planAllowsApprovedPublicReadsAndDenialDoesNotDownload() {
        endpoint("/page",200,"text/plain","page",0);String args="{\"url\":\""+url("/page")+"\"}";
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,execute(service(),args,context(PermissionMode.ASK,WorkMode.PLAN,r->ApprovalHandler.Answer.DENY)).errorCode());assertEquals(0,requests.get());
        assertEquals(ToolResult.Status.SUCCESS,execute(service(),args,context(PermissionMode.AUTO,WorkMode.PLAN,ApprovalHandler.unavailable())).status());
        assertEquals(ToolResult.Status.SUCCESS,execute(service(),args,context(PermissionMode.ASK,WorkMode.REACT,r->ApprovalHandler.Answer.APPROVE)).status());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"url\":\"https://example.com\",\"headers\":{}}","{\"url\":\"https://example.com\",\"maxLines\":301}","{\"url\":\"https://example.com\",\"startLine\":0}"})
    void invalidToolParametersDoNotDownload(String args){assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,execute(service(),args,context(PermissionMode.FULL,WorkMode.REACT,ApprovalHandler.unavailable())).errorCode());assertEquals(0,requests.get());}
    @Test void unsupportedPdfAndHttpErrorsAreExplicit() {
        endpoint("/pdf",200,"application/pdf","not a supported document",0);endpoint("/forbidden",403,"text/plain","PRIVATE_BODY",0);
        assertEquals(ToolResult.ErrorCode.WEB_FETCH_UNSUPPORTED,assertThrows(ToolException.class,()->service().fetch(new WebFetchRequest(url("/pdf"),1,120),16000)).code());
        var failed=assertThrows(ToolException.class,()->service().fetch(new WebFetchRequest(url("/forbidden"),1,120),16000));assertTrue(failed.getMessage().contains("403"));assertFalse(failed.getMessage().contains("PRIVATE_BODY"));
    }
    @Test void compressedBombIsStoppedAtDecodedLimit()throws Exception {
        var bytes=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(bytes)){gzip.write("x".repeat(5000).getBytes(StandardCharsets.UTF_8));}
        server.createContext("/gzip",e->{requests.incrementAndGet();e.getResponseHeaders().set("Content-Type","text/plain");e.getResponseHeaders().set("Content-Encoding","gzip");byte[] body=bytes.toByteArray();e.sendResponseHeaders(200,body.length);try{e.getResponseBody().write(body);}finally{e.close();}});
        var s=new WebFetchService(new WebFetchSettings(true,2,1024,10),fixtures());
        assertEquals(ToolResult.ErrorCode.WEB_FETCH_TOO_LARGE,assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/gzip"),1,120),16000)).code());
    }
    @Test void rawOversizeChunkedBodyIsBounded() {
        server.createContext("/large",e->{e.getResponseHeaders().set("Content-Type","text/plain");e.sendResponseHeaders(200,0);try{e.getResponseBody().write(new byte[4000]);}catch(IOException ignored){}finally{e.close();}});
        var s=new WebFetchService(new WebFetchSettings(true,2,1024,10),fixtures());
        assertEquals(ToolResult.ErrorCode.WEB_FETCH_TOO_LARGE,assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/large"),1,120),16000)).code());
    }
    @Test void slowBodyTotalTimeoutIsBoundedAndCancellationPreservesFlag()throws Exception {
        endpoint("/slow",200,"text/plain","late",4000);var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
        var service=service();Thread caller=new Thread(()->{try{service.fetch(new WebFetchRequest(url("/slow"),1,120),16000);}catch(Throwable e){failure.set(e);interrupted.set(Thread.currentThread().isInterrupted());}});
        caller.start();try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(requests.get()==0 && System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(1,requests.get());caller.interrupt();caller.join(2000);assertFalse(caller.isAlive());assertInstanceOf(InterruptedException.class,failure.get());assertTrue(interrupted.get());
        }finally{caller.interrupt();caller.join(2000);}
    }
    @Test void timeoutAndDownloadBudgetAreReportedAndTaskResetAllowsNewDownload() {
        endpoint("/slow",200,"text/plain","late",3000);
        var s=new WebFetchService(new WebFetchSettings(true,1,1024,1),fixtures());
        assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/slow"),1,120),16000));
        assertEquals(ToolResult.ErrorCode.WEB_FETCH_BUDGET_EXCEEDED,assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/other"),1,120),16000)).code());
        s.resetTask();endpoint("/ok",200,"text/plain","ok",0);assertDoesNotThrow(()->s.fetch(new WebFetchRequest(url("/ok"),1,120),16000));
    }
    @Test void markdownEncodingAndEmptyHtmlAreHandled()throws Exception {
        var extractor=new WebContentExtractor();
        assertTrue(extractor.extract("# 文档\n代码".getBytes(StandardCharsets.UTF_8),"text/markdown","https://example.com").lines().get(0).contains("文档"));
        assertThrows(ToolException.class,()->extractor.extract("<script>only()</script>".getBytes(StandardCharsets.UTF_8),"text/html","https://example.com"));
        assertEquals(ToolResult.ErrorCode.INVALID_ENCODING,assertThrows(ToolException.class,()->extractor.extract(new byte[]{(byte)0xff},"text/plain; charset=UTF-8","https://example.com")).code());
    }
    @Test void configurationAndModelDrivenFetchNeedNoWorkspace()throws Exception {
        assertTrue(WebFetchSettings.load(directory,Map.of()).enabled());assertFalse(WebFetchSettings.load(directory,Map.of("CODEY_WEB_FETCH_ENABLED","false")).enabled());
        assertThrows(ConfigException.class,()->WebFetchSettings.load(directory,Map.of("CODEY_WEB_FETCH_MAX_BYTES","99999999")));
        endpoint("/page",200,"text/plain","Verified source text",0);var s=service();var registry=new ToolRegistry().register(new WebFetchTool(s));registry.onTaskFinished(s::resetTask);
        var count=new AtomicInteger();LlmClient model=req->{if(count.incrementAndGet()==1)return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("page","web_fetch","{\"url\":\""+url("/page")+"\"}"))),"tool_calls",null);
            assertTrue(req.messages().get(req.messages().size()-1).content().contains("Verified source text"));return new LlmResponse(Message.assistant("Answer with source"),"stop",null);};
        assertTrue(new AgentLoop(model,registry,3).run(new Session("base"),"Read page",context(PermissionMode.AUTO,WorkMode.PLAN,ApprovalHandler.unavailable())).completed());
    }
    @Test void bodyDeadlineIncludesSlowChunksNotJustHeaders() {
        server.createContext("/chunks",e->{requests.incrementAndGet();e.getResponseHeaders().set("Content-Type","text/plain");e.sendResponseHeaders(200,0);
            try{e.getResponseBody().write("first".getBytes(StandardCharsets.UTF_8));e.getResponseBody().flush();Thread.sleep(3000);e.getResponseBody().write("late".getBytes(StandardCharsets.UTF_8));}
            catch(Exception cancelled){}finally{e.close();}});
        var s=new WebFetchService(new WebFetchSettings(true,1,1024,2),fixtures());
        assertEquals(ToolResult.ErrorCode.WEB_FETCH_TIMEOUT,assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/chunks"),1,120),16000)).code());
    }
    @Test void cacheIsBoundedAndInvalidLineDoesNotRedownloadKnownSnapshot()throws Exception {
        endpoint("/text",200,"text/plain","one\ntwo",0);var s=service();s.fetch(new WebFetchRequest(url("/text"),1,1),16000);
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/text"),999,1),16000)).code());assertEquals(1,requests.get());
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,assertThrows(ToolException.class,()->s.fetch(new WebFetchRequest(url("/new"),1,1),100)).code());assertEquals(1,requests.get());
    }
}
