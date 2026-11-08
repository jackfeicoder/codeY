package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.mcp.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实向导输入和临时配置文件，验证分支、凭证、取消、持久化及立即生效。 */
class McpRegistrationWizardTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private final StringWriter out=new StringWriter();
    private final ToolRegistry registry=new ToolRegistry();
    private final AtomicInteger opens=new AtomicInteger(),closes=new AtomicInteger();
    private McpConnectionManager manager() {
        return new McpConnectionManager(registry,List.of(),config->{opens.incrementAndGet();return new McpConnection(){
            public List<RemoteTool> tools(){return List.of();}
            public String call(String name,JsonNode args){throw new AssertionError();}
            public void close(){closes.incrementAndGet();}
        };});
    }
    private void wizard(String commands,McpConnectionManager manager,Map<String,String> env)throws Exception {
        var writer=new PrintWriter(out);var input=new ConsoleInput(new BufferedReader(new StringReader(commands)),writer);
        new McpRegistrationWizard(input,writer,new McpConfigStore(root,env),manager).run();
    }
    private JsonNode entry(String name)throws Exception{return json.readTree(root.resolve("mcp.json").toFile()).path("mcpServers").get(name);}
    @Test void registerHttpFromEmptyCatalogImmediatelyExposesSummonWithoutConnecting()throws Exception {
        try(var manager=manager()) {
            assertTrue(registry.definitions().isEmpty());
            wizard("1\ngithub\n搜索仓库和Issue\nhttps://example.com/custom/mcp\n1\nn\n\n\n",manager,Map.of());
            assertEquals(0,opens.get());assertEquals("DORMANT",manager.state("github"));assertEquals(2,registry.definitions().size());
            assertTrue(registry.find("mcp_summon").orElseThrow().definition().description().contains("搜索仓库和Issue"));
            var config=McpConfigLoader.load(root,Map.of()).get(0);assertEquals("streamable-http",config.transport());assertTrue(config.enabled());
            assertTrue(out.toString().contains("无需重启"));
            // 从空目录新增后也必须注册任务清理钩子。
            manager.summon("github");assertEquals(1,opens.get());
            new AgentLoop(request->new LlmResponse(Message.assistant("done"),"stop",null),registry)
                    .run(new Session("base"),"hello",new ToolContext(WorkspaceBinding.none()));
            assertFalse(manager.active("github"));assertEquals(1,closes.get());
        }
    }
    @Test void bearerSecretIsSavedButNeverEchoedAndReloads()throws Exception {
        try(var manager=manager()) {
            wizard("http\nservice\n查询资料\nhttps://example.com/mcp\n2\n2\nfixture-private-token\nn\n\ny\n",manager,Map.of());
            assertEquals("Bearer fixture-private-token",entry("service").path("headers").path("Authorization").asText());
            assertEquals("Bearer fixture-private-token",McpConfigLoader.load(root,Map.of()).get(0).headers().get("Authorization"));
            assertFalse(out.toString().contains("fixture-private-token"));assertFalse(manager.catalog().contains("fixture-private-token"));
            assertTrue(out.toString().contains("不加密"));assertEquals(0,opens.get());
        }
    }
    @Test void customHeaderStoresVariableReferenceAndExistingServiceIsPreserved()throws Exception {
        Files.writeString(root.resolve(".env"),"FIXTURE_TOKEN=file-secret\n");
        String existing="{\"mcpServers\":{\"old\":{\"description\":\"old service\",\"transport\":\"stdio\",\"command\":\"fixture\",\"enabled\":false,\"env\":{\"KEY\":\"${UNSET}\"}}}}";
        Files.writeString(root.resolve("mcp.json"),existing);JsonNode previous=json.readTree(existing).path("mcpServers").path("old");
        try(var manager=manager()) {
            wizard("1\nremote\n资料查询\nhttps://example.com/mcp\n3\nX-API-Key\n1\nFIXTURE_TOKEN\n\nn\n\ny\n",manager,Map.of("FIXTURE_TOKEN","env-secret"));
            assertEquals("${FIXTURE_TOKEN}",entry("remote").path("headers").path("X-API-Key").asText());
            assertEquals(previous,entry("old"));assertEquals("env-secret",manager.config("remote").headers().get("X-API-Key"));
            assertEquals("FIXTURE_TOKEN=file-secret\n",Files.readString(root.resolve(".env")));assertFalse(out.toString().contains("env-secret"));
        }
    }
    @Test void stdioPreservesArgvSpacesCwdAndExplicitEnvironment()throws Exception {
        Path cwd=Files.createDirectory(root.resolve("server work"));
        try(var manager=manager()) {
            wizard("2\nlocal_tools\n查询本机资料\nE:/Program Files/Java/bin/java.exe\n-jar\nE:/tools with spaces/server.jar\n\nserver work\nSERVER_TOKEN\n2\nfixture-secret\n\nn\n\ny\n",manager,Map.of());
            var c=manager.config("local_tools");assertEquals("stdio",c.transport());assertEquals("E:/Program Files/Java/bin/java.exe",c.command());
            assertEquals(List.of("-jar","E:/tools with spaces/server.jar"),c.args());assertEquals(cwd.toString(),c.cwd());
            assertEquals("fixture-secret",c.env().get("SERVER_TOKEN"));assertFalse(out.toString().contains("fixture-secret"));assertEquals(0,opens.get());
        }
    }
    @Test void cancelAndEofAtMultipleStagesAndDeclineSaveLeaveNoFile()throws Exception {
        for(String script:List.of("/cancel\n","1\n/cancel\n","1\nremote\ndesc\nhttps://example.com/mcp\n2\n2\n/cancel\n",
                "1\nremote\ndesc\nhttps://example.com/mcp\n1\nn\n","1\nremote\n")) {
            try(var manager=manager()) {wizard(script,manager,Map.of());assertTrue(manager.names().isEmpty());assertFalse(Files.exists(root.resolve("mcp.json")));}
        }
    }
    @Test void invalidChoicesNameAndUrlAreRepromptedWithoutSavingBadValues()throws Exception {
        try(var manager=manager()) {
            wizard("wrong\n1\n1invalid\nvalid\n描述\nnot-a-url\nhttps://example.com/mcp\n1\nn\n\ny\n",manager,Map.of());
            assertEquals(List.of("valid"),manager.names());assertTrue(out.toString().contains("重新填写"));assertEquals("https://example.com/mcp",entry("valid").path("url").asText());
        }
    }
    @Test void missingCredentialVariableCanBeCorrectedWithoutLosingPreviousFields()throws Exception {
        try(var manager=manager()) {
            wizard("1\nremote\n描述\nhttps://example.com/mcp\n2\n1\nMISSING\nFOUND\nn\n\ny\n",manager,Map.of("FOUND","fixture"));
            assertTrue(out.toString().contains("变量未设置"));assertEquals("${FOUND}",entry("remote").path("headers").path("Authorization").asText().substring(7));
        }
    }
    @Test void realSessionCommandWorksInBrowserWithoutModel()throws Exception {
        try(var manager=manager()) {
            var writer=new PrintWriter(out);var errors=new StringWriter();var requests=new AtomicInteger();
            // 浏览页只注册，不需选择模型、创建会话或访问服务。
            var console=new ConsoleInput(new BufferedReader(new StringReader("/mcp register\n1\nremote\n查询项目Issue\nhttps://example.com/mcp\n1\nn\n\ny\n/mcp\n/exit\n")),writer);
            assertEquals(0,new SessionLauncher(root,new SessionSettings(root.resolve("data"),null,false),new JsonSessionStore(root.resolve("data")),
                    profile->{requests.incrementAndGet();throw new AssertionError("browser must not load model");},()->"base",console,writer,new PrintWriter(errors),
                    new SessionLauncher.Options(null,false,null,null,null),registry,DebugPrinter.NONE,List::of,20,new PermissionSettings(PermissionMode.ASK,false))
                    .withMcp(manager,new McpConfigStore(root,Map.of())).run());
            assertEquals(0,requests.get());assertEquals(0,opens.get());assertEquals(List.of("remote"),manager.names());assertEquals("",errors.toString());
        }
    }
    @Test void activeSessionImmediatelyUsesNewCatalogWithoutLeakingRegistrationIntoHistory() throws Exception {
        try(var manager=manager()) {
            var writer=new PrintWriter(out); var errors=new StringWriter(); var calls=new AtomicInteger();
            var console=new ConsoleInput(new BufferedReader(new StringReader("/mcp register\n1\nremote\n查询Issue目录\nhttps://example.com/mcp\n2\n2\nfixture-private-token\nn\n\ny\nhello\n/exit\n")),writer);
            var data=root.resolve("data");var sessions=new JsonSessionStore(data);
            assertEquals(0,new SessionLauncher(root,new SessionSettings(data,null,false),sessions,
                    profile->new SessionLauncher.ModelChoice("fixture model",request->{
                        calls.incrementAndGet();
                        assertTrue(request.tools().stream().anyMatch(t->t.name().equals("mcp_summon")&&t.description().contains("查询Issue目录")));
                        assertTrue(request.tools().stream().noneMatch(t->t.name().startsWith("mcp__")));
                        assertFalse(request.toString().contains("fixture-private-token"));
                        return new LlmResponse(Message.assistant("done"),"stop",null);
                    },request->{throw new AssertionError();}),()->"base",console,writer,new PrintWriter(errors),
                    new SessionLauncher.Options(null,true,"fixture",null,null),registry,DebugPrinter.NONE,List::of,20,new PermissionSettings(PermissionMode.ASK,false))
                    .withMcp(manager,new McpConfigStore(root,Map.of())).run());
            assertEquals(1,calls.get());assertEquals(0,opens.get());assertEquals("",errors.toString());
            var saved=sessions.list().sessions().get(0);assertEquals(2,saved.messages().size());
            assertTrue(saved.messages().stream().noneMatch(m->m.content()!=null&&(m.content().contains("fixture-private-token")||m.content().contains("/mcp register"))));
        }
    }

    @Test void explicitMcpTrustIsPersistedWithoutStartingService() throws Exception {
        try (var manager=manager()) {
            wizard("1\nremote\nquery\nhttps://example.com/mcp\n1\ny\nlist_accounts, get_headers\ny\n",manager,Map.of());
            assertTrue(manager.config("remote").permissions().autoApproveConnect());
            assertEquals(Set.of("list_accounts","get_headers"),McpConfigLoader.load(root,Map.of()).get(0).permissions().readOnlyTools());
            assertEquals(0,opens.get());
        }
    }
    @Test void httpDefaultsToAutomaticOAuthWithoutAddingLoginCommands() throws Exception {
        try(var manager=manager()) {
            wizard("1\nremote\n查询资料\nhttps://example.com/mcp\n\nn\n\ny\n",manager,Map.of());
            assertEquals("auto",manager.config("remote").auth().mode());assertEquals(0,opens.get());
            assertTrue(CommandCatalog.commands().stream().noneMatch(c->c.name().equals("/oauth")||c.name().equals("/login")));
        }
    }
    @Test void preRegisteredOAuthClientAndCallbackPortCanBeConfigured() throws Exception {
        try(var manager=manager()) {
            wizard("1\nremote\n查询资料\nhttps://example.com/mcp\n4\npublic-client\n8900\nn\n\ny\n",manager,Map.of());
            assertEquals("oauth",manager.config("remote").auth().mode());assertEquals("public-client",manager.config("remote").auth().clientId());assertEquals(8900,manager.config("remote").auth().callbackPort());
        }
    }

}
