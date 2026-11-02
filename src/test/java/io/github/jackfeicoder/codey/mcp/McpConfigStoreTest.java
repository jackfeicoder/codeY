package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class McpConfigStoreTest {
    @TempDir Path root;
    private ObjectNode entry(){return new ObjectMapper().createObjectNode().put("description","fixture").put("transport","stdio").put("command","fixture");}
    @Test void serialRegistrationsMergeAndDuplicateNeverOverwrites()throws Exception {
        var store=new McpConfigStore(root,Map.of());store.register("one",entry());store.register("two",entry());
        byte[] before=Files.readAllBytes(store.file());assertThrows(ConfigException.class,()->store.register("one",entry().put("command","different")));
        assertArrayEquals(before,Files.readAllBytes(store.file()));assertEquals(2,McpConfigLoader.load(root,Map.of()).size());
        try(var list=Files.list(root)){assertFalse(list.anyMatch(p->p.getFileName().toString().startsWith(".mcp-register-")));}
    }
    @Test void invalidCandidateAndMalformedExistingConfigPreserveOriginalBytes()throws Exception {
        var store=new McpConfigStore(root,Map.of());Files.writeString(store.file(),"broken fixture original");
        assertThrows(ConfigException.class,()->store.register("one",entry()));assertEquals("broken fixture original",Files.readString(store.file()));
        Files.delete(store.file());assertThrows(ConfigException.class,()->store.register("one",entry().put("transport","invalid")));assertFalse(Files.exists(store.file()));
    }
    @Test void occupiedLockDoesNotCreateOrOverwriteConfiguration()throws Exception {
        var store=new McpConfigStore(root,Map.of());
        try(var channel=FileChannel.open(root.resolve("mcp.json.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var held=channel.lock()) {
            assertThrows(ConfigException.class,()->store.register("one",entry()));assertFalse(Files.exists(store.file()));
        }
    }
    @Test void configDirectoryEnvironmentOverrideIsUsedForWritingAndResolution()throws Exception {
        Path directory=root.resolve("config");Files.createDirectory(directory);Files.writeString(directory.resolve(".env"),"TOKEN=fixture\n");
        var env=Map.of("CODEY_CONFIG_DIR",directory.toString());var store=new McpConfigStore(root.resolve("workspace"),env);
        var entry=entry();entry.putObject("env").put("SERVER_TOKEN","${TOKEN}");store.register("local",entry);
        assertEquals(directory.resolve("mcp.json"),store.file());assertEquals("fixture",McpConfigLoader.load(root,env).get(0).env().get("SERVER_TOKEN"));
        assertFalse(Files.exists(root.resolve("workspace/mcp.json")));
    }
}
