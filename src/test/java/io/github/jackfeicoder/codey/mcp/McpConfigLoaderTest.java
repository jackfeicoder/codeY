package io.github.jackfeicoder.codey.mcp;
import io.github.jackfeicoder.codey.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class McpConfigLoaderTest {
    @TempDir Path root;
    void write(String text)throws Exception{Files.writeString(root.resolve("mcp.json"),text);}
    @Test void missingFileIsEmpty() {assertEquals(List.of(),McpConfigLoader.load(root,Map.of()));}
    @Test void expandsDotEnvWithEnvironmentOverrideAndPreservesFullEndpoint()throws Exception {
        Files.writeString(root.resolve(".env"),"MCP_SECRET=file-value\n");
        write("{\"mcpServers\":{\"github\":{\"description\":\"Search issues\",\"transport\":\"streamable-http\",\"url\":\"https://example.com/custom/mcp?region=cn\",\"headers\":{\"Authorization\":\"Bearer ${MCP_SECRET}\"}}}}");
        var c=McpConfigLoader.load(root,Map.of("MCP_SECRET","env-value")).get(0);
        assertEquals("Bearer env-value",c.headers().get("Authorization"));assertTrue(c.url().endsWith("?region=cn"));assertFalse(c.toString().contains("env-value"));
    }
    @Test void configDirectoryIsIndependentOfWorkspace()throws Exception {
        write("{\"mcpServers\":{}}");assertTrue(McpConfigLoader.load(root.resolve("other"),Map.of("CODEY_CONFIG_DIR",root.toString())).isEmpty());
    }
    @Test void rejectsMissingVariablesDuplicateKeysBadTransportAndUnknownFields()throws Exception {
        for(String extra:List.of("\"headers\":{\"Authorization\":\"${MISSING}\"}","\"enabled\":true,\"enabled\":false","\"unexpected\":1","\"toolTimeoutSeconds\":0")) {
            write("{\"mcpServers\":{\"server\":{\"description\":\"x\",\"transport\":\"streamable-http\",\"url\":\"https://example.com/mcp\","+extra+"}}}");
            var error=assertThrows(ConfigException.class,()->McpConfigLoader.load(root,Map.of()));assertFalse(error.getMessage().contains("MISSING"));
        }
        write("{\"mcpServers\":{\"server\":{\"description\":\"x\",\"transport\":\"sse\",\"url\":\"https://example.com/mcp\"}}}");assertThrows(ConfigException.class,()->McpConfigLoader.load(root,Map.of()));
    }
    @Test void disabledTemplateDoesNotRequireCredentialsAndStdioUsesArgv()throws Exception {
        write("{\"mcpServers\":{\"server\":{\"description\":\"x\",\"enabled\":false,\"transport\":\"stdio\",\"command\":\"java\",\"args\":[\"-jar\",\"server.jar\"],\"env\":{\"TOKEN\":\"${MISSING}\"}}}}");
        var c=McpConfigLoader.load(root,Map.of()).get(0);assertFalse(c.enabled());assertEquals(List.of("-jar","server.jar"),c.args());
    }
}
