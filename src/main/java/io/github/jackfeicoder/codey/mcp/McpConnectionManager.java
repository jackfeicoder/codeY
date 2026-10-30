package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jackfeicoder.codey.context.TokenEstimator;
import io.github.jackfeicoder.codey.tool.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 单线程宿主的 MCP 生命周期：休眠 → 连接/发现 → 整批注册 → 释放；失败不自动重放。 */
public final class McpConnectionManager implements AutoCloseable {
    private final ToolRegistry registry;
    private final McpConnection.Factory factory;
    private final Map<String,McpServerConfig> configs=new LinkedHashMap<>();
    private final Map<String,Active> active=new LinkedHashMap<>();
    private final Map<String,Long> estimates=new HashMap<>();
    private final Map<String,String> states=new HashMap<>();
    private final Set<String> disabled=new HashSet<>();
    private final Runnable taskCleanup=this::releaseAll;
    private boolean closed;
    private final List<Tool> controls=new ArrayList<>();
    private record Active(McpConnection connection,List<McpToolAdapter> tools) { }
    public McpConnectionManager(ToolRegistry registry,List<McpServerConfig> configs) { this(registry,configs,SdkMcpConnection::open); }
    /** 创建管理器只建立目录，factory 到召唤才执行。 */
    public McpConnectionManager(ToolRegistry registry,List<McpServerConfig> servers,McpConnection.Factory factory) {
        this.registry=registry; this.factory=factory;
        for(var config:servers) {
            if(configs.putIfAbsent(config.name(),config)!=null) throw new IllegalArgumentException("Duplicate MCP server alias.");
            if(!config.enabled()) disabled.add(config.name());
        }
        if(!configs.isEmpty()) { refreshControls(); registry.onTaskFinished(taskCleanup); }
    }
    public McpServerConfig config(String name) {
        var config=configs.get(name);
        if(config==null) throw failure(ToolResult.ErrorCode.INVALID_ARGUMENTS,"Unknown MCP server alias."); return config;
    }
    public boolean active(String name) { return active.containsKey(name); }
    public List<String> names() { return List.copyOf(configs.keySet()); }
    public String catalog() {
        var out=new StringBuilder();
        for(var c:configs.values()) {
            if(disabled.contains(c.name())) continue;
            Long tokens=estimates.getOrDefault(c.name(),c.estimatedTokens());
            out.append(c.name()).append("：").append(c.description()).append("；工具定义≈")
                    .append(tokens==null?"未知":tokens+" tok").append("；").append(state(c.name())).append('\n');
        }
        return out.length()==0?"无启用的 MCP 服务。":out.toString();
    }
    public String state(String name) { return disabled.contains(name)?"DISABLED":active(name)?"ACTIVE":states.getOrDefault(name,"DORMANT"); }
    /** 在独立守护线程连接；超时/中断后即使晚到连接成功，也只关闭而不注册。 */
    private McpConnection connect(McpServerConfig config) {
        var abandoned=new AtomicBoolean(); var slot=new java.util.concurrent.atomic.AtomicReference<McpConnection>();
        var pool=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"codey-mcp-connect");t.setDaemon(true);return t;});
        var future=pool.submit(()->{
            McpConnection connection=factory.open(config); slot.set(connection);
            if(abandoned.get()) { if(slot.compareAndSet(connection,null)) closeQuietly(connection); throw new IllegalStateException(); }
            return connection;
        });
        boolean transferred=false;
        try { var connection=future.get(config.startupTimeoutSeconds(),TimeUnit.SECONDS); transferred=true; return connection; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw failure(ToolResult.ErrorCode.INTERRUPTED,"MCP activation interrupted."); }
        catch(Exception e) { throw failure(ToolResult.ErrorCode.EXECUTION_FAILED,"MCP activation failed or timed out; check configuration, authentication and protocol support."); }
        finally {
            abandoned.set(true);
            if(!transferred) {
                future.cancel(true); var connection=slot.getAndSet(null); if(connection!=null) closeQuietly(connection);
            }
            pool.shutdownNow();
        }
    }
    public String summon(String name) {
        if(closed) throw failure(ToolResult.ErrorCode.EXECUTION_FAILED,"MCP manager is closed.");
        var config=config(name);
        if(disabled.contains(name)) throw failure(ToolResult.ErrorCode.PERMISSION_DENIED,"MCP server is disabled; ask the user to enable it.");
        if(active(name)) return "MCP "+name+" already active; tools are available in the next request.";
        states.put(name,"CONNECTING"); McpConnection connection=null;
        try {
            connection=connect(config);
            var batch=new ArrayList<McpToolAdapter>(); var rawNames=new HashSet<String>(); long tokens=0;
            var remoteTools=connection.tools();
            if(remoteTools==null || remoteTools.size()>256) throw new IllegalArgumentException();
            for(var remote:remoteTools) {
                if(remote.name()==null||remote.name().isBlank()||remote.name().length()>256||remote.name().chars().anyMatch(Character::isISOControl)
                        ||!rawNames.add(remote.name())||remote.schema()==null||remote.schema().toString().length()>65536
                        ||(remote.description()!=null&&remote.description().length()>8192)) throw new IllegalArgumentException();
                // 禁止 schema 通过远程 $ref 触发额外网络访问；本地片段引用可用。
                checkRefs(remote.schema());
                if(!config.permits(remote.name())) continue;
                var adapter=new McpToolAdapter(this,name,publicName(name,remote.name()),remote);
                tokens+=new TokenEstimator().toolTokens(adapter.definition());
                if(tokens>32000) throw new IllegalArgumentException();
                batch.add(adapter);
            }
            registry.registerAll(batch);
            active.put(name,new Active(connection,List.copyOf(batch))); estimates.put(name,tokens); states.remove(name); refreshControls();
            return "MCP "+name+" activated; definitions approximately "+tokens+" tok. Use these tools in the NEXT request: "
                    +batch.stream().map(t->t.definition().name()).toList();
        } catch(RuntimeException failed) {
            if(connection!=null) closeQuietly(connection); states.put(name,"FAILED");
            if(failed instanceof ToolException t) throw t;
            throw failure(ToolResult.ErrorCode.EXECUTION_FAILED,"MCP discovery rejected: invalid/duplicate tools, unsupported schema or definition budget exceeded.");
        }
    }
    private static void checkRefs(JsonNode node) {
        if(node.isObject()) {
            for(String key:List.of("$ref","$dynamicRef","$recursiveRef"))
                if(node.has(key)&&(!node.get(key).isTextual()||!node.get(key).textValue().startsWith("#"))) throw new IllegalArgumentException();
            if(node.has("$id")) throw new IllegalArgumentException();
        }
        if(node.isContainerNode()) for(JsonNode child:node) checkRefs(child);
    }
    /** 公开名最长 64；发生清洗或截短时附带身份哈希，映射始终保存原名。 */
    static String publicName(String server,String raw) {
        String source="mcp__"+server+"__"+raw;
        if(source.matches("[A-Za-z0-9_-]{1,64}")) return source;
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((server+"\u0000"+raw).getBytes(StandardCharsets.UTF_8))).substring(0,12);
            String clean=source.replaceAll("[^A-Za-z0-9_-]","_");
            return clean.substring(0,Math.min(51,clean.length()))+"_"+hash;
        } catch(Exception e) { throw new IllegalStateException(); }
    }
    public String call(String server,String rawName,JsonNode args) {
        var current=active.get(server);
        if(current==null) throw failure(ToolResult.ErrorCode.UNKNOWN_TOOL,"MCP server is dormant.");
        return current.connection.call(rawName,args);
    }
    /** 工具执行同步串行；释放只发生在调用结束后，不关闭别的服务器。 */
    public String release(String name) {
        config(name); var current=active.remove(name);
        if(current!=null) { current.tools.forEach(registry::unregister); closeQuietly(current.connection); }
        states.remove(name); refreshControls(); return "MCP "+name+" released; existing history is retained.";
    }
    public void enabled(String name,boolean enabled) {
        config(name);
        if(enabled) {
            // 含未展开变量的禁用模板需要先修改文件并重启，避免把占位凭证发送出去。
            if(!config(name).enabled()) throw failure(ToolResult.ErrorCode.INVALID_ARGUMENTS,"Set enabled=true in mcp.json and restart to resolve credentials.");
            disabled.remove(name);
        } else { release(name); disabled.add(name); }
        refreshControls();
    }
    public String tools(String name) {
        config(name); var current=active.get(name);
        return current==null?"MCP "+name+" is "+state(name)+"; tools are not loaded.":current.tools.stream().map(t->t.definition().name()).toList().toString();
    }
    private void refreshControls() {
        controls.forEach(registry::unregister); controls.clear();
        controls.add(new McpControlTool(this,true)); controls.add(new McpControlTool(this,false)); registry.registerAll(controls);
    }
    public void releaseAll() { for(String name:List.copyOf(active.keySet())) release(name); }
    private static void closeQuietly(McpConnection connection) {
        boolean interrupted=Thread.interrupted();
        try { connection.close(); } catch(RuntimeException ignored) { } finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
    @Override public void close() {
        if(closed) return;
        releaseAll(); controls.forEach(registry::unregister); controls.clear();
        registry.removeTaskCleanup(taskCleanup); closed=true;
    }
    private static ToolException failure(ToolResult.ErrorCode code,String text) { return new ToolException(code,text); }
}
