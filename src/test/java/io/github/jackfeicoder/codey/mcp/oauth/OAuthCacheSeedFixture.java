package io.github.jackfeicoder.codey.mcp.oauth;

import io.github.jackfeicoder.codey.mcp.McpConfigLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/** 仅供隔离生产 JAR 冒烟：种入本地模拟服务的 DPAPI 凭证，不连接浏览器/真实服务。 */
public final class OAuthCacheSeedFixture {
    public static void main(String[] args) {
        Path config=Path.of(args[0]),data=Path.of(args[1]);String origin=args[2];
        var server=McpConfigLoader.load(config,Map.of()).get(0);long now=Instant.now().getEpochSecond();
        boolean expired=args.length>3&&args[3].equals("expired");
        var credentials=new OAuthTokenStore.Credentials(server.url(),origin+"/issuer","fixture-public",origin+"/authorize",origin+"/token",
                "fixture-oauth-access","fixture-oauth-refresh",expired?now-120:now,expired?now-1:now+3600,"docs:read");
        new OAuthTokenStore(data).save(OAuthManager.key(server),credentials);
        System.out.println("Fixture OAuth credentials encrypted.");
    }
}
