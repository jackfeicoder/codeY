package io.github.jackfeicoder.codey.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** 搜索独立于 LLM 配置；有序服务列表表示主服务和备用服务，不向模型暴露凭证。 */
public record WebSearchSettings(boolean enabled,List<Provider> providers,int timeoutSeconds,int totalTimeoutSeconds,int maxCalls) {
    public record Provider(String name,URI endpoint,String apiKey) {
        public Provider {
            if(!Set.of("tavily","brave","serpapi").contains(name) || endpoint==null || apiKey==null || apiKey.isBlank()
                    || apiKey.codePoints().anyMatch(Character::isISOControl))throw new ConfigException("Invalid web search provider or API key.");
            String host=endpoint.getHost();
            boolean local=Set.of("localhost","127.0.0.1","[::1]").contains(host==null?"":host);
            if(host==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                    || !("https".equals(endpoint.getScheme()) || local && "http".equals(endpoint.getScheme())))
                throw new ConfigException("Web search endpoint requires HTTPS without credentials/query/fragment; loopback HTTP is allowed for tests.");
        }
        @Override public String toString(){return name+" [credentials hidden]";}
    }
    public WebSearchSettings {
        providers=List.copyOf(providers);
        if(enabled && providers.isEmpty() || providers.size()>3 || providers.stream().map(Provider::name).distinct().count()!=providers.size()
                || timeoutSeconds<1 || timeoutSeconds>60 || totalTimeoutSeconds<1 || totalTimeoutSeconds>120 || maxCalls<1 || maxCalls>30)
            throw new ConfigException("Invalid web search settings or duplicate providers.");
    }
    public static WebSearchSettings load(Path startup,Map<String,String> environment) {
        var values=new HashMap<>(DotEnvReader.read(ConfigLoader.configurationDirectory(startup,environment).resolve(".env")));
        values.putAll(environment);
        try {
            String enabled=values.getOrDefault("CODEY_WEB_SEARCH_ENABLED","false").strip();
            if(!Set.of("true","false").contains(enabled))throw new IllegalArgumentException();
            var providers=new ArrayList<Provider>();
            if(enabled.equals("true"))for(String item:values.getOrDefault("CODEY_WEB_SEARCH_PROVIDERS","tavily").split(",",-1)) {
                String name=item.strip().toLowerCase(Locale.ROOT);
                String prefix="CODEY_"+name.toUpperCase(Locale.ROOT);
                String defaultUrl=switch(name){case "tavily"->"https://api.tavily.com/search";case "brave"->"https://api.search.brave.com/res/v1/web/search";case "serpapi"->"https://serpapi.com/search.json";default->throw new IllegalArgumentException();};
                // CODEY_ 专用键优先，同时兼容各服务官方常用环境变量名称。
                providers.add(new Provider(name,URI.create(values.getOrDefault(prefix+"_SEARCH_URL",defaultUrl)),
                        values.getOrDefault(prefix+"_API_KEY",values.get(name.toUpperCase(Locale.ROOT)+"_API_KEY"))));
            }
            return new WebSearchSettings(Boolean.parseBoolean(enabled),providers,
                    Integer.parseInt(values.getOrDefault("CODEY_WEB_SEARCH_TIMEOUT_SECONDS","20")),
                    Integer.parseInt(values.getOrDefault("CODEY_WEB_SEARCH_TOTAL_TIMEOUT_SECONDS","35")),
                    Integer.parseInt(values.getOrDefault("CODEY_WEB_SEARCH_MAX_CALLS","5")));
        }catch(RuntimeException invalid){throw new ConfigException("Invalid web search configuration; check enabled flag, provider order, endpoints, keys and limits.");}
    }
}
