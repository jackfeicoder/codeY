package io.github.jackfeicoder.codey.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jackfeicoder.codey.tool.*;
import io.modelcontextprotocol.spec.McpError;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** MCP 故障的有界结构化诊断。不保存参数、URL、异常原文、stderr 原文或响应正文。 */
public final class McpDiagnostics {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final McpServerConfig config;
    private final AtomicBoolean exited = new AtomicBoolean();
    private final AtomicBoolean invalidProtocol = new AtomicBoolean();
    private final AtomicBoolean startFailed = new AtomicBoolean();
    private volatile List<String> startupTypes = List.of();
    private final AtomicLong stderrLines = new AtomicLong();
    private volatile String stderrSignal = "NONE";

    McpDiagnostics(McpServerConfig config) { this.config = config; }
    void exited() { exited.set(true); }
    void invalidProtocol() { invalidProtocol.set(true); }
    void startFailed(Throwable error) { startupTypes=causes(error).stream().map(t->t.getClass().getSimpleName()).distinct().toList(); startFailed.set(true); }
    /** stderr 只保留计数和白名单信号；即使服务器打印邮件或密钥，也不会进入诊断。 */
    void stderr(String line) { stderrLines.incrementAndGet(); String signal = signal(line); if (!signal.equals("UNCLASSIFIED")) stderrSignal = signal; }
    void beginCall() { stderrSignal = "NONE"; }

    /** SDK 可能在读循环记录并吞掉解析异常；在 JSON 边界保留类型信号，不保存报文。 */
    io.modelcontextprotocol.json.McpJsonMapper mapper() {
        var delegate = new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(JSON);
        return (io.modelcontextprotocol.json.McpJsonMapper) java.lang.reflect.Proxy.newProxyInstance(
                McpDiagnostics.class.getClassLoader(), new Class<?>[]{io.modelcontextprotocol.json.McpJsonMapper.class}, (proxy, method, args) -> {
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException failed) {
                        if (method.getName().equals("readValue")) invalidProtocol();
                        throw failed.getCause();
                    }
                });
    }

    ToolException failure(String stage, String tool, long started, Throwable error) {
        var causes = causes(error);
        ToolResult.ErrorCode code;
        if (startFailed.get()) code = ToolResult.ErrorCode.MCP_START_FAILED;
        else if (exited.get()) code = ToolResult.ErrorCode.MCP_PROCESS_EXITED;
        else if (invalidProtocol.get() || causes.stream().anyMatch(t -> t instanceof JsonProcessingException)) code = ToolResult.ErrorCode.MCP_PROTOCOL_ERROR;
        else if (causes.stream().anyMatch(t -> t instanceof McpError m && m.getJsonRpcError() != null)) code = ToolResult.ErrorCode.MCP_REMOTE_ERROR;
        else if (causes.stream().anyMatch(t -> t instanceof TimeoutException || t instanceof java.net.http.HttpTimeoutException))
            code = stage.equals("call") ? ToolResult.ErrorCode.MCP_CALL_TIMEOUT : ToolResult.ErrorCode.MCP_STARTUP_TIMEOUT;
        else if (causes.stream().anyMatch(t -> t instanceof IOException)) code = ToolResult.ErrorCode.MCP_TRANSPORT_ERROR;
        else code = stage.equals("discover") ? ToolResult.ErrorCode.MCP_DISCOVERY_FAILED : ToolResult.ErrorCode.MCP_TRANSPORT_ERROR;
        // 中断不误报为超时；保留宿主的取消语义。
        if (Thread.currentThread().isInterrupted() || causes.stream().anyMatch(t -> t instanceof InterruptedException)) code = ToolResult.ErrorCode.INTERRUPTED;
        Integer rpcCode = causes.stream().filter(t -> t instanceof McpError m && m.getJsonRpcError() != null)
                .map(t -> ((McpError)t).getJsonRpcError().code()).findFirst().orElse(null);
        var types=new ArrayList<>(startupTypes); causes.stream().map(t->t.getClass().getSimpleName()).filter(t->!types.contains(t)).forEach(types::add);
        return diagnostic(code, stage, tool, started, types, rpcCode, "NONE");
    }

    ToolException reported(String stage, String tool, long started, ToolException error) {
        return diagnostic(error.code(), stage, tool, started, List.of(), null, signal(error.getMessage()));
    }
    /** 本地预算/外层线程超时没有 SDK 异常链，仍生成一致的诊断结构。 */
    ToolException explicit(ToolResult.ErrorCode code, String stage, String tool, long started) {
        return diagnostic(code, stage, tool, started, List.of(), null, "NONE");
    }
    private ToolException diagnostic(ToolResult.ErrorCode code, String stage, String tool, long started,
                                     List<String> types, Integer rpcCode, String remoteSignal) {
        var out = JSON.createObjectNode().put("mcpDiagnostic", true).put("server", config.name())
                .put("transport", config.transport()).put("stage", stage).put("category", code.name())
                .put("elapsedMillis", TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started)))
                .put("timeoutSeconds", stage.equals("call") ? config.toolTimeoutSeconds() : config.startupTimeoutSeconds())
                .put("requestTimeoutSeconds", config.toolTimeoutSeconds())
                .put("summary", summary(code)).put("stderrLines", stderrLines.get()).put("stderrSignal", stderrSignal)
                .put("remoteSignal", remoteSignal).put("processExited", exited.get());
        if (tool != null) out.put("tool", tool);
        var array = out.putArray("exceptionTypes"); types.stream().limit(8).forEach(array::add);
        if (rpcCode != null) out.put("jsonRpcCode", rpcCode);
        if (stage.equals("call")) out.put("executionOutcome", "unknown; do not automatically replay writes");
        return new ToolException(code, out.toString());
    }
    private static List<Throwable> causes(Throwable error) {
        var result = new ArrayList<Throwable>(); var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for (Throwable t = error; t != null && result.size() < 12 && seen.add(t); t = t.getCause()) result.add(t);
        return result;
    }
    /** 这是服务器报告的线索，不代表宿主已证实邮箱/数据库等内部故障原因。 */
    static String signal(String text) {
        String value = text == null ? "" : text.substring(0, Math.min(text.length(), 8192)).toLowerCase(Locale.ROOT);
        for (String known : List.of("CONNECTION_REFUSED", "DNS_ERROR", "TIMEOUT_REPORTED", "AUTHENTICATION_REPORTED", "CERTIFICATE_REPORTED"))
            if (value.endsWith(known.toLowerCase(Locale.ROOT))) return known;
        if (value.contains("econnrefused")) return "CONNECTION_REFUSED";
        if (value.contains("enotfound") || value.contains("eai_again")) return "DNS_ERROR";
        if (value.contains("etimedout") || value.contains("timed out") || value.contains("timeout")) return "TIMEOUT_REPORTED";
        if (value.contains("authentication failed") || value.contains("invalid credentials") || value.contains("authenticate failed")) return "AUTHENTICATION_REPORTED";
        if (value.contains("certificate") || value.contains("cert_has_expired")) return "CERTIFICATE_REPORTED";
        return "UNCLASSIFIED";
    }
    /** 控制台和模型共享稳定说明；不把异常消息猜测成具体业务原因。 */
    public static String summary(ToolResult.ErrorCode code) {
        return switch (code) {
            case MCP_START_FAILED -> "本地 MCP 进程启动失败";
            case MCP_STARTUP_TIMEOUT -> "MCP 初始化或工具发现超时";
            case MCP_CALL_TIMEOUT -> "等待 MCP 工具响应超时，内部执行阶段未知";
            case MCP_PROCESS_EXITED -> "MCP 子进程已退出";
            case MCP_PROTOCOL_ERROR -> "MCP 协议或响应 JSON 解析异常";
            case MCP_REMOTE_ERROR -> "MCP 服务端报告错误（原文未回显）";
            case MCP_INVALID_RESPONSE -> "MCP 返回空结果或无效结果";
            case MCP_DISCOVERY_FAILED -> "MCP 工具发现或定义校验失败";
            case INTERRUPTED -> "MCP 操作被中断";
            default -> "MCP 通信异常，具体业务原因尚未确定";
        };
    }
}
