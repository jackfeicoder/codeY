package io.github.jackfeicoder.codey.mcp.oauth;

import com.sun.jna.Platform;
import io.github.jackfeicoder.codey.tool.ToolException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** 在实际 Windows 上验证 DPAPI 密文存储；不读取用户已有凭证。 */
class OAuthTokenStoreTest {
    @TempDir Path root;
    private final String key="a".repeat(64);
    private OAuthTokenStore.Credentials credential(){return new OAuthTokenStore.Credentials("https://example.com/mcp","https://auth.example.com","client","https://auth.example.com/authorize","https://auth.example.com/token","fixture-access-secret","fixture-refresh-secret",100L,1000L,"docs:read");}
    @Test void dpapiRoundTripRestartAndDeletionKeepPlaintextOutOfFiles()throws Exception {
        if(!Platform.isWindows()) {assertThrows(ToolException.class,()->new OAuthTokenStore(root).save(key,credential()));return;}
        var store=new OAuthTokenStore(root);assertEquals(credential(),store.save(key,credential()));
        byte[] bytes=Files.readAllBytes(root.resolve("mcp/oauth/"+key+".bin"));
        String raw=new String(bytes,StandardCharsets.ISO_8859_1);assertFalse(raw.contains("fixture-access-secret"));assertFalse(raw.contains("fixture-refresh-secret"));
        assertEquals(credential(),new OAuthTokenStore(root).load(key));store.delete(key);assertNull(store.load(key));
    }
    @Test void corruptedCiphertextIsNotSilentlyOverwritten()throws Exception {
        Path file=root.resolve("mcp/oauth/"+key+".bin");Files.createDirectories(file.getParent());byte[] original={1,2,3};Files.write(file,original);
        var store=new OAuthTokenStore(root);assertThrows(ToolException.class,()->store.load(key));assertThrows(ToolException.class,()->store.save(key,credential()));assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void unknownPlatformAndInvalidCacheIdentityNeverFallBackToPlaintext() {
        var store=new OAuthTokenStore(root);assertThrows(ToolException.class,()->store.save("../outside",credential()));
        assertFalse(Files.exists(root.resolve("mcp/oauth")));
    }
}
