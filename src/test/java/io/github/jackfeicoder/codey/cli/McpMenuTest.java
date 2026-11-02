package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.mcp.*;
import io.github.jackfeicoder.codey.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 菜单只管理用户意图；查看不启动服务，启停持久化且不误删注册信息。 */
class McpMenuTest {
    @TempDir Path root;
    private final StringWriter output=new StringWriter();
    private final AtomicInteger opened=new AtomicInteger(),closed=new AtomicInteger();
    private final ToolRegistry registry=new ToolRegistry();
    private McpConfigStore store(){return new McpConfigStore(root,Map.of());}
    private McpConnectionManager manager(List<McpServerConfig> configs) {
        return new McpConnectionManager(registry,configs,c->{opened.incrementAndGet();return new McpConnection(){
            public List<RemoteTool> tools(){return List.of();}
            public String call(String name,com.fasterxml.jackson.databind.JsonNode args){throw new AssertionError();}
            public void close(){closed.incrementAndGet();}
        };});
    }
    private McpServerConfig seed() {
        var entry=new ObjectMapper().createObjectNode().put("description","查询资料").put("transport","streamable-http").put("url","https://example.com/mcp?token=hidden-query");
        entry.putObject("headers").put("Authorization","Bearer fixture-private-token");
        return store().register("fixture",entry);
    }
    private void run(String script,McpConnectionManager manager)throws Exception {
        var writer=new PrintWriter(output);
        new McpMenu(new ConsoleInput(new BufferedReader(new StringReader(script)),writer),writer,manager,store()).run();
    }
    @Test void viewingMenuAndDetailsNeverConnectsOrRevealsCredentials()throws Exception {
        try(var manager=manager(List.of(seed()))) {
            run("bad-choice\n1\n\n\n",manager);
            assertEquals(0,opened.get());assertTrue(output.toString().contains("查询资料"));assertTrue(output.toString().contains("DORMANT"));
            assertFalse(output.toString().contains("hidden-query"));assertFalse(output.toString().contains("fixture-private-token"));
        }
    }
    @Test void disablingActiveServerClosesConnectionAndPersistsAcrossRestart()throws Exception {
        try(var manager=manager(List.of(seed()))) {
            manager.summon("fixture");run("1\ne\n\n\n",manager);
            assertEquals(1,closed.get());assertEquals("DISABLED",manager.state("fixture"));assertFalse(manager.catalog().contains("fixture"));
            assertEquals(1,McpConfigLoader.load(root,Map.of()).size());assertFalse(McpConfigLoader.load(root,Map.of()).get(0).enabled());
        }
        try(var reloaded=manager(McpConfigLoader.load(root,Map.of()))) {
            assertEquals("DISABLED",reloaded.state("fixture"));run("1\ne\n\n\n",reloaded);
            assertEquals("DORMANT",reloaded.state("fixture"));assertTrue(McpConfigLoader.load(root,Map.of()).get(0).enabled());assertEquals(1,opened.get());
        }
    }
    @Test void unresolvedDisabledCredentialDoesNotEnableOrCorruptFile()throws Exception {
        var entry=new ObjectMapper().createObjectNode().put("description","fixture").put("transport","stdio").put("command","fixture").put("enabled",false);
        entry.putObject("env").put("TOKEN","${MISSING}");var c=store().register("fixture",entry);byte[] before=Files.readAllBytes(store().file());
        try(var manager=manager(List.of(c))) {
            run("1\ne\n\n\n",manager);assertEquals("DISABLED",manager.state("fixture"));assertEquals(0,opened.get());
            assertArrayEquals(before,Files.readAllBytes(store().file()));assertTrue(output.toString().contains("启停未保存"));
        }
    }
    @Test void menuRegisterAddsPersistentDormantEntryAndEofReturnsCleanly()throws Exception {
        try(var manager=manager(List.of())) {
            run("r\n1\nremote\n查询资料\nhttps://example.com/mcp\n1\ny\n\n",manager);
            assertEquals(List.of("remote"),manager.names());assertEquals("DORMANT",manager.state("remote"));assertEquals(0,opened.get());
            assertTrue(registry.find("mcp_summon").orElseThrow().definition().description().contains("查询资料"));
            run("1\n",manager);assertEquals(0,opened.get());
        }
    }
}
