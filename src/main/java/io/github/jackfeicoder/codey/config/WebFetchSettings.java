package io.github.jackfeicoder.codey.config;

import java.nio.file.Path;
import java.util.*;

/** 网页读取无需搜索密钥；资源上限由宿主控制，不由工具参数覆盖。 */
public record WebFetchSettings(boolean enabled,int timeoutSeconds,int maxBytes,int maxCalls) {
    public WebFetchSettings {
        if(timeoutSeconds<1 || timeoutSeconds>60 || maxBytes<1024 || maxBytes>2*1024*1024 || maxCalls<1 || maxCalls>30)
            throw new ConfigException("Invalid web fetch limits.");
    }
    public static WebFetchSettings load(Path startup,Map<String,String> environment) {
        var values=new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup,environment).resolve(".env")));values.putAll(environment);
        try {
            String enabled=values.getOrDefault("CODEY_WEB_FETCH_ENABLED","true").strip();
            if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException();
            return new WebFetchSettings(Boolean.parseBoolean(enabled),Integer.parseInt(values.getOrDefault("CODEY_WEB_FETCH_TIMEOUT_SECONDS","20")),
                    Integer.parseInt(values.getOrDefault("CODEY_WEB_FETCH_MAX_BYTES","2097152")),Integer.parseInt(values.getOrDefault("CODEY_WEB_FETCH_MAX_CALLS","10")));
        }catch(RuntimeException invalid){throw new ConfigException("Invalid CODEY_WEB_FETCH_* settings.");}
    }
}
