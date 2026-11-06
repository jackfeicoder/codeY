package io.github.jackfeicoder.codey.tool.web;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.WebSearchTool;
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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实本地HTTP接口验证参数映射、故障切换、取消和审批；不使用真实搜索账号。 */
@Timeout(15)
class WebSearchModuleTest {
    @TempDir Path directory;
    HttpServer server;ExecutorService workers;
    final ObjectMapper json=new ObjectMapper();
    final AtomicInteger tavilyCalls=new AtomicInteger(),braveCalls=new AtomicInteger();
    final AtomicReference<String> capturedBody=new AtomicReference<>(),capturedKey=new AtomicReference<>(),capturedQuery=new AtomicReference<>();
    static final String TAVILY="{\"results\":[{\"title\":\"Java docs\",\"url\":\"https://docs.example.com/java\",\"content\":\"A snippet\"}]}";
    static final String BRAVE="{\"web\":{\"results\":[{\"title\":\"Backup docs\",\"url\":\"https://docs.example.com/backup\",\"description\":\"Backup snippet\"}]}}";
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        workers=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});server.setExecutor(workers);server.start();
    }
    @AfterEach void stop(){server.stop(0);workers.shutdownNow();Thread.interrupted();}
    WebSearchSettings.Provider provider(String name){return new WebSearchSettings.Provider(name,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"+name),"fixture-"+name+"-key");}
    WebSearchSettings settings(){return new WebSearchSettings(true,List.of(provider("tavily"),provider("brave")),1,3,5);}
    WebSearchRequest request(){return new WebSearchRequest("Java docs",5,List.of("example.com"),"week");}
    void endpoint(String name,int status,String body,int delay) {
        server.createContext("/"+name,exchange->{
            (name.equals("tavily")?tavilyCalls:braveCalls).incrementAndGet();
            capturedKey.set(exchange.getRequestHeaders().getFirst(name.equals("tavily")?"Authorization":"X-Subscription-Token"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));capturedQuery.set(exchange.getRequestURI().getRawQuery());
            try {
                if(delay>0)Thread.sleep(delay);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception disconnected){}finally{exchange.close();}
        });
    }
    ToolContext context(PermissionMode mode,WorkMode work,ApprovalHandler handler) {
        var binding=WorkspaceBinding.none();return new ToolContext(binding,16000,work,new PermissionService(mode,binding,handler));
    }
    ToolResult execute(WebSearchService service,String arguments,ToolContext context){return new ToolExecutor(new ToolRegistry().register(new WebSearchTool(service))).execute(new ToolCall("search","web_search",arguments),context);}

    @Test void tavilyMappingReturnsSourcesWithoutSwitchingOrLeakingKey()throws Exception {
        endpoint("tavily",200,TAVILY,0);endpoint("brave",200,BRAVE,0);
        var body=json.readTree(new WebSearchService(settings()).search(request(),16000));
        assertEquals("tavily",body.path("provider").asText());assertEquals(1,body.path("results").size());
        assertTrue(body.path("results").get(0).path("publishedAt").isNull());assertEquals(0,braveCalls.get());
        var sent=json.readTree(capturedBody.get());assertEquals("week",sent.path("time_range").asText());
        assertEquals("example.com",sent.path("include_domains").get(0).asText());assertFalse(sent.path("include_raw_content").asBoolean());
        assertEquals("Bearer fixture-tavily-key",capturedKey.get());assertFalse(body.toString().contains("fixture-tavily-key"));
    }
    @ParameterizedTest @ValueSource(ints={408,429,500,503})
    void transientFailureSwitchesToConfiguredBraveOnce(int status)throws Exception {
        endpoint("tavily",status,"{\"error\":\"PRIVATE_ERROR\"}",0);endpoint("brave",200,BRAVE,0);
        var body=json.readTree(new WebSearchService(settings()).search(request(),16000));
        assertEquals("brave",body.path("provider").asText());assertEquals(2,body.path("attempts").size());
        assertEquals(1,tavilyCalls.get());assertEquals(1,braveCalls.get());
        assertEquals("fixture-brave-key",capturedKey.get());assertTrue(capturedQuery.get().contains("freshness=pw"));
        assertFalse(body.toString().contains("PRIVATE_ERROR"));
    }
    @Test void primaryTimeoutSwitchesToBackupWithinTotalDeadline()throws Exception {
        endpoint("tavily",200,TAVILY,1800);endpoint("brave",200,BRAVE,0);
        var body=json.readTree(new WebSearchService(settings()).search(request(),16000));
        assertEquals("brave",body.path("provider").asText());assertEquals("TIMEOUT",body.path("attempts").get(0).path("status").asText());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403})
    void rejectedRequestDoesNotFallbackOrExposeServerBody(int status) {
        endpoint("tavily",status,"{\"error\":\"PRIVATE_ERROR\"}",0);endpoint("brave",200,BRAVE,0);
        var failed=assertThrows(ToolException.class,()->new WebSearchService(settings()).search(request(),16000));
        assertEquals(0,braveCalls.get());assertFalse(failed.getMessage().contains("PRIVATE_ERROR"));
    }
    @Test void emptyResultsAreSuccessWithoutBackup()throws Exception {
        endpoint("tavily",200,"{\"results\":[]}",0);endpoint("brave",200,BRAVE,0);
        assertEquals(0,json.readTree(new WebSearchService(settings()).search(request(),16000)).path("results").size());assertEquals(0,braveCalls.get());
    }
    @Test void malformedResponseDoesNotSilentlySwitch() {
        endpoint("tavily",200,"{bad}",0);endpoint("brave",200,BRAVE,0);
        assertThrows(ToolException.class,()->new WebSearchService(settings()).search(request(),16000));assertEquals(0,braveCalls.get());
    }
    @Test void resultDomainFilterRejectsSuffixTricksAndDeduplicates()throws Exception {
        endpoint("tavily",200,"{\"results\":[{\"title\":\"bad\",\"url\":\"https://evil-example.com/x\"},{\"title\":\"ok\",\"url\":\"https://example.com/x\"},{\"title\":\"duplicate\",\"url\":\"https://example.com/x\"}]}",0);
        var result=new TavilySearchClient(provider("tavily")).search(request(),Duration.ofSeconds(2));assertEquals(1,result.results().size());assertEquals("ok",result.results().get(0).title());
    }
    @Test void cancellingWaitingHttpDoesNotCallBackup()throws Exception {
        endpoint("tavily",200,TAVILY,4000);endpoint("brave",200,BRAVE,0);
        var failure=new AtomicReference<Throwable>();var interrupted=new AtomicBoolean();
        Thread thread=new Thread(()->{try{new WebSearchService(new WebSearchSettings(true,settings().providers(),10,15,5)).search(request(),16000);}catch(Throwable e){failure.set(e);interrupted.set(Thread.currentThread().isInterrupted());}});
        thread.start();try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(tavilyCalls.get()==0 && System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(1,tavilyCalls.get());thread.interrupt();thread.join(2000);
            assertFalse(thread.isAlive());assertInstanceOf(InterruptedException.class,failure.get());assertTrue(interrupted.get());assertEquals(0,braveCalls.get());
        }finally{thread.interrupt();thread.join(2000);}
    }
    @Test void planAllowsApprovedSearchAndAutoAllowsOrdinaryQuery() {
        endpoint("tavily",200,TAVILY,0);
        assertEquals(ToolResult.Status.SUCCESS,execute(new WebSearchService(settings()),"{\"query\":\"Java\"}",context(PermissionMode.AUTO,WorkMode.PLAN,ApprovalHandler.unavailable())).status());
        assertEquals(ToolResult.Status.SUCCESS,execute(new WebSearchService(settings()),"{\"query\":\"Java\"}",context(PermissionMode.ASK,WorkMode.PLAN,r->ApprovalHandler.Answer.APPROVE)).status());
    }
    @Test void denialAndSensitiveAutoQueryNeverReachAnyProvider() {
        endpoint("tavily",200,TAVILY,0);endpoint("brave",200,BRAVE,0);
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED,execute(new WebSearchService(settings()),"{\"query\":\"Java\"}",context(PermissionMode.ASK,WorkMode.REACT,r->ApprovalHandler.Answer.DENY)).errorCode());
        assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED,execute(new WebSearchService(settings()),"{\"query\":\"token=private\"}",context(PermissionMode.AUTO,WorkMode.REACT,ApprovalHandler.unavailable())).errorCode());
        assertEquals(0,tavilyCalls.get());assertEquals(0,braveCalls.get());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"query\":\" \"}","{\"query\":\"Java\",\"maxResults\":11}","{\"query\":\"Java\",\"timeRange\":\"tomorrow\"}","{\"query\":\"Java\",\"allowedDomains\":[\"https://example.com\"]}","{\"query\":\"Java\",\"provider\":\"evil\"}"})
    void invalidArgumentsFailBeforeAnyRequest(String args){
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS,execute(new WebSearchService(settings()),args,context(PermissionMode.FULL,WorkMode.REACT,ApprovalHandler.unavailable())).errorCode());
        assertEquals(0,tavilyCalls.get());
    }
    @Test void budgetCountsLogicalCallsAndTaskFinishResetsIt()throws Exception {
        endpoint("tavily",200,TAVILY,0);var s=new WebSearchSettings(true,List.of(provider("tavily")),1,2,1);var service=new WebSearchService(s);
        var registry=new ToolRegistry().register(new WebSearchTool(service));registry.onTaskFinished(service::resetTask);
        service.search(request(),16000);assertEquals(ToolResult.ErrorCode.WEB_SEARCH_BUDGET_EXCEEDED,assertThrows(ToolException.class,()->service.search(request(),16000)).code());
        registry.finishTask();service.search(request(),16000);assertEquals(2,tavilyCalls.get());
    }
    @Test void resultOutputStaysValidJsonUnderSmallBudgetAndRedactsEchoedKey()throws Exception {
        endpoint("tavily",200,TAVILY.replace("A snippet","fixture-tavily-key "+"长".repeat(1500)),0);
        String output=new WebSearchService(settings()).search(request(),800);
        assertTrue(output.codePointCount(0,output.length())<=800);assertFalse(output.contains("fixture-tavily-key"));assertTrue(json.readTree(output).path("truncated").asBoolean());
    }
    @Test void disabledConfigurationDoesNotRequireKeysAndEnabledOrderIsValidated()throws Exception {
        assertFalse(WebSearchSettings.load(directory,Map.of()).enabled());
        var config=Map.of("CODEY_WEB_SEARCH_ENABLED","true","CODEY_WEB_SEARCH_PROVIDERS","tavily,brave","CODEY_TAVILY_API_KEY","fixture-tavily-key","CODEY_BRAVE_API_KEY","fixture-brave-key");
        assertEquals(List.of("tavily","brave"),WebSearchSettings.load(directory,config).providers().stream().map(WebSearchSettings.Provider::name).toList());
        assertThrows(ConfigException.class,()->WebSearchSettings.load(directory,Map.of("CODEY_WEB_SEARCH_ENABLED","true")));
        assertThrows(ConfigException.class,()->new WebSearchSettings.Provider("tavily",URI.create("http://public.example.com/search"),"fixture-key"));
        assertThrows(ConfigException.class,()->new WebSearchSettings.Provider("tavily",URI.create("https://example.com/search?key=x"),"fixture-key"));
        assertFalse(provider("tavily").toString().contains("fixture-tavily-key"));
    }
    @Test void agentUsesStructuredSearchAndReturnsFinalAnswer() {
        endpoint("tavily",200,TAVILY,0);var service=new WebSearchService(settings());var registry=new ToolRegistry().register(new WebSearchTool(service));
        registry.onTaskFinished(service::resetTask);var count=new AtomicInteger();
        LlmClient model=req->{
            assertTrue(req.tools().stream().anyMatch(t->t.name().equals("web_search")));
            if(count.incrementAndGet()==1)return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("search","web_search","{\"query\":\"Java docs\"}"))),"tool_calls",null);
            assertTrue(req.messages().get(req.messages().size()-1).content().contains("https://docs.example.com/java"));
            return new LlmResponse(Message.assistant("See official source"),"stop",null);
        };
        var result=new AgentLoop(model,registry,3).run(new Session("base"),"Search docs",context(PermissionMode.AUTO,WorkMode.PLAN,ApprovalHandler.unavailable()));
        assertTrue(result.completed());assertEquals(1,result.toolCalls());
    }
    @Test void oversizedProviderResponseIsRejectedWithoutFallback() {
        endpoint("tavily",200,"{\"results\":[],\"large\":\""+"x".repeat(2*1024*1024)+"\"}",0);endpoint("brave",200,BRAVE,0);
        var failed=assertThrows(ToolException.class,()->new WebSearchService(settings()).search(request(),16000));
        assertTrue(failed.getMessage().contains("INVALID_RESPONSE"));assertEquals(0,braveCalls.get());
    }
    @Test void totalTimeoutPreventsStartingBackupAfterBudgetIsConsumed() {
        endpoint("tavily",200,TAVILY,1800);endpoint("brave",200,BRAVE,0);
        assertThrows(ToolException.class,()->new WebSearchService(new WebSearchSettings(true,settings().providers(),5,1,5)).search(request(),16000));
        assertEquals(0,braveCalls.get());
    }
    @Test void noConfiguredBackupDoesNotInventOneAndAllFailuresAreDiagnosed() {
        endpoint("tavily",503,"{}",0);endpoint("brave",429,"{}",0);
        assertThrows(ToolException.class,()->new WebSearchService(new WebSearchSettings(true,List.of(provider("tavily")),1,3,5)).search(request(),16000));
        assertEquals(0,braveCalls.get());
        var failed=assertThrows(ToolException.class,()->new WebSearchService(settings()).search(request(),16000));
        assertTrue(failed.getMessage().contains("tavily"));assertTrue(failed.getMessage().contains("brave"));assertTrue(failed.getMessage().contains("429"));
    }
    @Test void transportFailureUsesSameAbstractInterfaceForFallback()throws Exception {
        var first=new AtomicInteger();var second=new AtomicInteger();
        WebSearchClient primary=new WebSearchClient(){public String name(){return "tavily";}public WebSearchResponse search(WebSearchRequest r,Duration t){first.incrementAndGet();throw new WebSearchFailure(WebSearchFailure.Kind.NETWORK);}};
        WebSearchClient backup=new WebSearchClient(){public String name(){return "brave";}public WebSearchResponse search(WebSearchRequest r,Duration t){second.incrementAndGet();return new WebSearchResponse(List.of());}};
        assertEquals("brave",json.readTree(new WebSearchService(settings(),List.of(primary,backup)).search(request(),16000)).path("provider").asText());
        assertEquals(1,first.get());assertEquals(1,second.get());
    }
    @Test void serpApiMapsGoogleFieldsAndKeepsCredentialUriOutOfResults()throws Exception {
        endpoint("serpapi",200,"{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[{\"title\":\"Java\",\"link\":\"https://docs.example.com/java\",\"snippet\":\"Documentation\",\"date\":\"Nov 1, 2026\"}]}",0);
        var config=new WebSearchSettings(true,List.of(provider("serpapi")),1,3,5);
        String body=new WebSearchService(config).search(request(),16000);var result=json.readTree(body);
        assertEquals("serpapi",result.path("provider").asText());
        assertEquals("Nov 1, 2026",result.path("results").get(0).path("publishedAt").asText());
        assertTrue(capturedQuery.get().contains("engine=google"));assertTrue(capturedQuery.get().contains("api_key=fixture-serpapi-key"));
        assertTrue(capturedQuery.get().contains("tbs=qdr%3Aw"));assertFalse(body.contains("fixture-serpapi-key"));assertFalse(body.contains("search.json?"));
    }
    @Test void serpApiSuccessWithoutOrganicResultsIsEmptyNotFailure()throws Exception {
        endpoint("serpapi",200,"{\"search_metadata\":{\"status\":\"Success\"},\"error\":\"No results returned\"}",0);
        assertTrue(new SerpApiSearchClient(provider("serpapi")).search(request(),Duration.ofSeconds(2)).results().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"Error","Processing"})
    void serpApiBusinessErrorOrPendingStatusIsNotSuccessfulSearch(String status) {
        endpoint("serpapi",200,"{\"search_metadata\":{\"status\":\""+status+"\"},\"error\":\"PRIVATE_ERROR fixture-serpapi-key\"}",0);
        var failed=assertThrows(WebSearchFailure.class,()->new SerpApiSearchClient(provider("serpapi")).search(request(),Duration.ofSeconds(2)));
        assertFalse(failed.getMessage().contains("PRIVATE_ERROR"));assertFalse(failed.getMessage().contains("fixture-serpapi-key"));assertFalse(failed.fallbackAllowed());
    }
    @Test void configuredSerpApiReceivesFallbackFromTavily()throws Exception {
        endpoint("tavily",503,"{}",0);endpoint("serpapi",200,"{\"search_metadata\":{\"status\":\"Success\"},\"organic_results\":[{\"title\":\"Java\",\"link\":\"https://docs.example.com/java\",\"snippet\":\"Source\"}]}",0);
        var config=new WebSearchSettings(true,List.of(provider("tavily"),provider("serpapi")),1,3,5);
        var body=json.readTree(new WebSearchService(config).search(request(),16000));
        assertEquals("serpapi",body.path("provider").asText());assertEquals(2,body.path("attempts").size());assertEquals(1,tavilyCalls.get());
    }
    @Test void officialKeyAliasesWorkAndDedicatedKeyTakesPrecedence() {
        var values=new HashMap<>(Map.of("CODEY_WEB_SEARCH_ENABLED","true","CODEY_WEB_SEARCH_PROVIDERS","tavily,serpapi",
                "TAVILY_API_KEY","fixture-tavily-key","SERPAPI_API_KEY","fixture-serpapi-key"));
        var config=WebSearchSettings.load(directory,values);assertEquals("fixture-serpapi-key",config.providers().get(1).apiKey());
        assertEquals(URI.create("https://serpapi.com/search.json"),config.providers().get(1).endpoint());
        values.put("CODEY_SERPAPI_API_KEY","dedicated-fixture-key");
        assertEquals("dedicated-fixture-key",WebSearchSettings.load(directory,values).providers().get(1).apiKey());
    }
}
