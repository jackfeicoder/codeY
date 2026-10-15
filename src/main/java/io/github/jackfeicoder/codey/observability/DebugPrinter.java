package io.github.jackfeicoder.codey.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.tool.ToolCall;
import io.github.jackfeicoder.codey.tool.ToolResult;

import java.io.PrintWriter;
import java.util.Objects;

/** 简洁运行进度：只显示模型轮次、工具名称、相关文件和失败类别，不展示上下文或正文。 */
public final class DebugPrinter {
    /** 默认静默，库调用和旧构造方式不强制打印；Main 显式启用控制台输出。 */
    public static final DebugPrinter NONE = new DebugPrinter();
    /** 输出由 Main 按终端编码创建；null 仅用于静默实例。 */
    private final PrintWriter output;
    /** 只提取 path；文件定位工具额外显示名称模式，不打印整个参数对象。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 创建内部静默实例，不分配输出流。 */
    private DebugPrinter() { output = null; }

    /** 接收调用方持有的输出，不创建日志文件或关闭输出流。 */
    public DebugPrinter(PrintWriter output) { this.output = Objects.requireNonNull(output, "output"); }

    /** 在请求前显示当前任务的模型调用序号，下一任务从 1 开始。 */
    public void round(int step) { line("[第 " + step + " 轮] 请求模型"); }

    /** 只在实际进入执行器前显示工具名和文件，不展示模型尚未执行的调用组。 */
    public void tool(int step, ToolCall call) {
        if (output == null) return;
        String path = location(call);
        line("  [第 " + step + " 轮] 调用 " + shortText(call.name())
                + (path == null ? "" : " · " + shortText(path)));
    }

    /** 成功不追加正文，失败只显示类别；完整结果仍回填给模型并按原逻辑保存。 */
    public void toolFailed(int step, ToolResult result) {
        if (result.status() == ToolResult.Status.ERROR) {
            line("  [第 " + step + " 轮] " + shortText(result.name()) + " 失败 · " + result.errorCode());
        }
    }

    /** 同步刷新以便等待模型时看到进度，不关闭输出。 */
    private void line(String value) {
        if (output == null) return;
        output.println(value);
        output.flush();
    }

    /** 非 JSON、非字符串路径和过大参数均不回显；业务校验仍由执行器负责。 */
    private static String location(ToolCall call) {
        String arguments = call.arguments();
        if (arguments.length() > 64 * 1024) return null;
        try {
            var tree = MAPPER.readTree(arguments);
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
