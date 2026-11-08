package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.config.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** 只读取用户配置目录的 mcp.json；默认缺省为空，启动阶段不连接或执行命令。 */
public final class McpConfigLoader {
    private static final ObjectMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private McpConfigLoader() { }
    public static List<McpServerConfig> load(Path startup, Map<String,String> environment) {
        Path dir = ConfigLoader.configurationDirectory(startup, environment);
        Path file = dir.resolve("mcp.json");
        try { return parse(dir, environment, readDocument(file)); }
        catch (Exception invalid) { throw invalidConfig(); }
    }

    /** 保留原始变量引用及其他服务，用于交互注册时增量保存；缺失文件返回空目录。 */
    static com.fasterxml.jackson.databind.node.ObjectNode readDocument(Path file) throws java.io.IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            var document = JSON.createObjectNode(); document.putObject("mcpServers"); return document;
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 262144)
            throw new IllegalArgumentException();
        byte[] bytes;
        try (var in = Files.newInputStream(file)) { bytes = in.readNBytes(262145); }
        if (bytes.length > 262144) throw new IllegalArgumentException();
        JsonNode document = JSON.readTree(bytes);
        if (document == null || !document.isObject()) throw new IllegalArgumentException();
        return (com.fasterxml.jackson.databind.node.ObjectNode) document;
    }

    /** 校验候选配置并解析凭证；注册前复用同一规则，不为校验临时覆盖配置文件。 */
    static List<McpServerConfig> parse(Path dir, Map<String,String> environment, JsonNode document) {
        try {
            keys(document, Set.of("mcpServers"));
            JsonNode servers = document.get("mcpServers");
            if (servers == null || !servers.isObject() || servers.size() > 32) throw new IllegalArgumentException();
            var values = new HashMap<>(DotEnvReader.read(dir.resolve(".env"))); values.putAll(environment);
            var result = new ArrayList<McpServerConfig>();
            var it = servers.fields();
            while (it.hasNext()) {
                var entry = it.next(); String name = entry.getKey(); JsonNode n = entry.getValue();
                if (!name.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) throw new IllegalArgumentException();
                keys(n, Set.of("description","transport","url","command","args","env","headers","cwd","enabled",
                        "enabledTools","disabledTools","startupTimeoutSeconds","toolTimeoutSeconds","estimatedTokens","activation","auth","permissions"));
                String description = text(n,"description",null);
                if (description == null || description.isBlank() || description.length() > 500 || description.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                String transport = text(n,"transport",null);
                if (!Set.of("stdio","streamable-http").contains(transport == null ? "" : transport)
                        || !"lazy".equals(text(n,"activation","lazy"))) throw new IllegalArgumentException();
                boolean enabled = !n.has("enabled") || bool(n,"enabled");
                String url = text(n,"url",null), command = text(n,"command",null), cwd = text(n,"cwd",null);
                List<String> args = strings(n.get("args"));
                Map<String,String> env = mapping(n.get("env")), headers = mapping(n.get("headers"));
                if (transport.equals("streamable-http")) {
                    if (command != null || cwd != null || !args.isEmpty() || !env.isEmpty() || url == null) throw new IllegalArgumentException();
                } else if (command == null || command.isBlank() || url != null || !headers.isEmpty()) throw new IllegalArgumentException();
                // 禁用条目允许保留尚未填好的变量；启用时必须全部解析成功。
                if (enabled) {
                    url = expand(url,values); command = expand(command,values); cwd = expand(cwd,values);
                    args = args.stream().map(s -> expand(s,values)).toList();
                    env = expandMap(env,values); headers = expandMap(headers,values);
                }
                if (transport.equals("streamable-http") && enabled) {
                    URI uri = URI.create(url);
                    if (uri.getHost() == null || !Set.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
                }
                if (cwd != null) cwd = dir.resolve(cwd).toAbsolutePath().normalize().toString();
                McpAuthSettings auth=McpAuthSettings.legacy(transport,headers);
                if(n.has("auth")) {
                    JsonNode a=n.get("auth");keys(a,Set.of("mode","clientId","scopes","callbackPort"));
                    auth=new McpAuthSettings(text(a,"mode","auto"),text(a,"clientId",null),strings(a.get("scopes")),number(a,"callbackPort",0,0,65535));
                    if(transport.equals("stdio")&&!auth.mode().equals("none"))throw new IllegalArgumentException();
                    if(Set.of("auto","oauth").contains(auth.mode())&&headers.keySet().stream().anyMatch(k->k.equalsIgnoreCase("Authorization")))throw new IllegalArgumentException();
                }
                Long estimate = n.has("estimatedTokens") ? number(n,"estimatedTokens",0,0,1000000) * 1L : null;
                McpPermissions permissions = McpPermissions.untrusted();
                if (n.has("permissions")) {
                    JsonNode p = n.get("permissions"); keys(p, Set.of("autoApproveConnect", "readOnlyTools"));
                    var readOnly = new LinkedHashSet<>(strings(p.get("readOnlyTools")));
                    if (readOnly.stream().anyMatch(s -> s.isBlank() || s.length() > 256 || s.chars().anyMatch(Character::isISOControl)))
                        throw new IllegalArgumentException();
                    permissions = new McpPermissions(p.has("autoApproveConnect") && bool(p,"autoApproveConnect"), readOnly);
                }
                result.add(new McpServerConfig(name,description,transport,url,command,args,env,headers,cwd,enabled,
                        new LinkedHashSet<>(strings(n.get("enabledTools"))),new LinkedHashSet<>(strings(n.get("disabledTools"))),
                        number(n,"startupTimeoutSeconds",15,1,120),number(n,"toolTimeoutSeconds",60,1,600),estimate,auth,permissions));
            }
            return List.copyOf(result);
        } catch (Exception invalid) { throw invalidConfig(); }
    }
    private static ConfigException invalidConfig() {
        return new ConfigException("Invalid mcp.json: check fields, transport, limits and environment references (values hidden).");
    }
    private static void keys(JsonNode n, Set<String> allowed) {
        if (n==null || !n.isObject()) throw new IllegalArgumentException();
        n.fieldNames().forEachRemaining(k -> { if (!allowed.contains(k)) throw new IllegalArgumentException(); });
    }
    private static String text(JsonNode n,String key,String fallback) {
        if (!n.has(key)) return fallback;
        if (!n.get(key).isTextual() || n.get(key).textValue().length()>8192) throw new IllegalArgumentException();
        return n.get(key).textValue();
    }
    private static boolean bool(JsonNode n,String k) { if (!n.get(k).isBoolean()) throw new IllegalArgumentException(); return n.get(k).booleanValue(); }
    private static int number(JsonNode n,String k,int fallback,int min,int max) {
        if (!n.has(k)) return fallback;
        if (!n.get(k).isIntegralNumber() || !n.get(k).canConvertToInt()) throw new IllegalArgumentException();
        int value=n.get(k).intValue(); if(value<min||value>max) throw new IllegalArgumentException(); return value;
    }
    private static List<String> strings(JsonNode n) {
        if(n==null) return List.of(); if(!n.isArray()||n.size()>256) throw new IllegalArgumentException();
        var result=new ArrayList<String>();
        for(var v:n) { if(!v.isTextual()||v.textValue().length()>8192) throw new IllegalArgumentException(); result.add(v.textValue()); }
        return result;
    }
    private static Map<String,String> mapping(JsonNode n) {
        if(n==null) return Map.of(); if(!n.isObject()||n.size()>64) throw new IllegalArgumentException();
        var result=new LinkedHashMap<String,String>();
        n.fields().forEachRemaining(e -> { if(!e.getValue().isTextual()) throw new IllegalArgumentException(); result.put(e.getKey(),e.getValue().textValue()); }); return result;
    }
    private static Map<String,String> expandMap(Map<String,String> map,Map<String,String> values) {
        var result=new LinkedHashMap<String,String>(); map.forEach((k,v)->result.put(k,expand(v,values))); return result;
    }
    private static String expand(String s,Map<String,String> values) {
        if(s==null) return null; var matcher=VARIABLE.matcher(s); var out=new StringBuffer();
        while(matcher.find()) { String value=values.get(matcher.group(1)); if(value==null||value.isBlank()) throw new IllegalArgumentException(); matcher.appendReplacement(out,Matcher.quoteReplacement(value)); }
        matcher.appendTail(out); if(out.indexOf("${")>=0) throw new IllegalArgumentException(); return out.toString();
    }
}
