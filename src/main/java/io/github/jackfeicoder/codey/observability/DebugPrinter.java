package io.github.jackfeicoder.codey.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.ToolCall;
import io.github.jackfeicoder.codey.tool.ToolResult;

import java.io.PrintWriter;
import java.util.Objects;

/** 简洁运行进度：只显示模型轮次、工具名称、相关文件和失败类别，不展示上下文或正文。 */
public final class DebugPrinter {
    /** 展示语义由 CLI 选择颜色，不让 AgentLoop 依赖终端库。 */
    public enum Kind { REQUEST, TOOL, ERROR, WARNING, CONTEXT }
    /** 默认静默，库调用和旧构造方式不强制打印；Main 显式启用控制台输出。 */
    public static final DebugPrinter NONE = new DebugPrinter();
    /** 宿主负责输出编码与样式；null 仅用于静默实例。 */
    private final java.util.function.BiConsumer<Kind, String> sink;
    /** 只提取 path；文件定位工具额外显示名称模式，不打印整个参数对象。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 创建内部静默实例，不分配输出流。 */
    private DebugPrinter() { sink = null; }

    /** 接收调用方持有的输出，不创建日志文件或关闭输出流。 */
    public DebugPrinter(PrintWriter output) {
        Objects.requireNonNull(output, "output");
        sink = (kind, value) -> { output.println(value); output.flush(); };
    }

    /** 宿主可注入展示接收器，默认文本格式与历史内容保持独立。 */
    public DebugPrinter(java.util.function.BiConsumer<Kind, String> sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** 在请求前显示当前任务的模型调用序号，下一任务从 1 开始。 */
    public void round(int step) { line(Kind.REQUEST, "[第 " + step + " 轮] 请求模型"); }
    /** 摘要进度独立于业务轮次，不输出历史、摘要正文或模型错误原文。 */
    public void compaction(String text) { line(Kind.CONTEXT, "[上下文压缩] " + text); }

    /** 预警不代表停止或压缩；数字为原始估算，不输出提示词和文件正文。 */
    public void contextWarning(long used, long budget, long remaining) {
        line(Kind.WARNING, "[上下文预警] 估算输入 " + used + " / 输入预算 " + budget + " tok，剩余约 " + remaining
                + " tok；本次请求继续，仍受硬输入预算限制。");
    }

    /** 只在实际进入执行器前显示工具名和文件，不展示模型尚未执行的调用组。 */
    public void tool(int step, ToolCall call) {
        if (sink == null) return;
        String path = location(call);
        line(Kind.TOOL, "  [第 " + step + " 轮] 调用 " + shortText(call.name())
                + (path == null ? "" : " · " + shortText(path)));
    }

    /** 不追加工具正文；命令只显示退出/超时摘要，其他失败只显示类别。 */
    public void toolFailed(int step, ToolResult result) {
        if (result.status() == ToolResult.Status.ERROR) {
            line(Kind.ERROR, "  [第 " + step + " 轮] " + shortText(result.name()) + " 失败 · " + result.errorCode());
        } else if ("execute_command".equals(result.name())) {
            try {
                var body = MAPPER.readTree(result.output());
                boolean timedOut = body.path("timedOut").asBoolean();
                boolean ok = !timedOut && body.path("exitCode").isInt() && body.path("exitCode").asInt() == 0;
                line(ok ? Kind.TOOL : Kind.ERROR, "  [第 " + step + " 轮] execute_command · "
                        + (timedOut ? "超时" : "退出码 " + body.path("exitCode").asInt())
                        + " · " + body.path("durationMs").asLong() + " ms"
                        + (body.path("stdoutTruncated").asBoolean() || body.path("stderrTruncated").asBoolean() ? " · 输出截断" : "")
                        + (body.path("outputIncomplete").asBoolean() || body.path("cleanupIncomplete").asBoolean() ? " · 输出/清理未完整完成" : ""));
            } catch (java.io.IOException ignored) { /* 有效结果仍由执行器回填，不打印原始输出。 */ }
        }
    }

    /** 同步刷新以便等待模型时看到进度，不关闭输出。 */
    private void line(Kind kind, String value) {
        if (sink != null) sink.accept(kind, value);
    }

    /** 非 JSON、非字符串路径和过大参数均不回显；业务校验仍由执行器负责。 */
    private static String location(ToolCall call) {
        String arguments = call.arguments();
        if (arguments.length() > 64 * 1024) return null;
        try {
            var tree = MAPPER.readTree(arguments);
            if ("execute_command".equals(call.name()) && tree != null && tree.path("command").isTextual()) {
                return tree.path("command").asText() + " · "
                        + (tree.path("workingDirectory").isTextual() ? tree.path("workingDirectory").asText() : ".");
            }
            if (("mcp_summon".equals(call.name()) || "mcp_release".equals(call.name()))
                    && tree != null && tree.path("server").isTextual()) return tree.path("server").asText();
            var path = tree == null ? null : tree.get("path");
            String location = path != null && path.isTextual() && !path.asText().isBlank() ? path.asText() : null;
            var pattern = tree == null ? null : tree.get("pattern");
            if ("search_file".equals(call.name()) && pattern != null && pattern.isTextual() && !pattern.asText().isBlank()) {
                return location == null ? pattern.asText() : location + " · " + pattern.asText();
            }
            return location;
        } catch (java.io.IOException invalid) { return null; }
    }

    /** 防止长文件名或控制字符把一条摘要扩成多行，按码点保留完整 Unicode 字符。 */
    private static String shortText(String value) {
        StringBuilder text = new StringBuilder();
        value.codePoints().limit(240).forEach(point -> text.appendCodePoint(
                Character.isISOControl(point) || point == 0x2028 || point == 0x2029 ? ' ' : point));
        if (value.codePointCount(0, value.length()) > 240) text.append("…");
        return text.toString();
    }
}
