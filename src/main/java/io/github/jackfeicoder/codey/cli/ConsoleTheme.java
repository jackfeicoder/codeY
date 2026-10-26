package io.github.jackfeicoder.codey.cli;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.io.PrintWriter;

/** 终端展示主题：业务只选择消息类型；ANSI 样式不进入模型消息、会话或管道输出。 */
public final class ConsoleTheme {
    /** 用户、回答、等待、工具及告警分别使用稳定的视觉语义。 */
    public enum Tone { USER, ASSISTANT, THINKING, TOOL, SUCCESS, WARNING, ERROR, MUTED, COMMAND }
    /** 无原生终端或设置 NO_COLOR 时使用纯文本；布局与审批行为保持独立。 */
    private final boolean colors;
    /** 布局与颜色分别开关；NO_COLOR 终端仍显示回答分区并转义外部控制字符。 */
    private final boolean interactive;

    public ConsoleTheme(boolean colors) { this(colors, colors); }
    public ConsoleTheme(boolean interactive, boolean colors) {
        this.interactive = interactive;
        this.colors = interactive && colors;
    }

    /** 使用基础 ANSI 调色板，让 Windows Terminal、深色和浅色主题自行映射颜色。 */
    public static AttributedStyle style(Tone tone) {
        return switch (tone) {
            case USER -> AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
            case ASSISTANT, SUCCESS -> AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
            case THINKING, WARNING -> AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
            case TOOL, COMMAND -> AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
            case ERROR -> AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();
            case MUTED -> AttributedStyle.DEFAULT.foreground(AttributedStyle.BRIGHT + AttributedStyle.BLACK);
        };
    }

    /** 仅展示时转义控制序列，保留换行、缩进和完整 Unicode；不截断模型回答。 */
    public static String safeText(String text) {
        StringBuilder result = new StringBuilder();
        text.replace("\r\n", "\n").codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t') result.appendCodePoint(cp);
            else if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || cp == 0x2028 || cp == 0x2029) result.append(String.format("\\u%04x", cp));
            else result.appendCodePoint(cp);
        });
        return result.toString();
    }

    public String text(Tone tone, String value) {
        return colors ? new AttributedString(safeText(value), style(tone)).toAnsi()
                : interactive ? safeText(value) : value;
    }

    /** 普通日志保留原有换行与文本，只有原生终端应用颜色。 */
    public void line(PrintWriter output, Tone tone, String value) {
        output.println(text(tone, value));
        output.flush();
    }

    /** 回答标签用绿色，正文使用终端默认前景色；Markdown 标题/代码区提供轻量高亮。 */
    public void assistant(PrintWriter output, String content) {
        if (!interactive) { output.println("CodeY> " + content); output.flush(); return; }
        output.println();
        output.println(text(Tone.ASSISTANT, "● CodeY"));
        boolean code = false;
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                code = !code;
                output.println(text(Tone.MUTED, line));
            } else if (code) output.println(text(Tone.THINKING, line));
            else if (line.matches("^#{1,6} .*")) output.println(text(Tone.USER, line));
            else output.println(safeText(line));
        }
        output.println();
        output.flush();
    }

    /** 流式回答的固定标签只打印一次；正文由流展示器即时安全输出。 */
    public void beginAssistant(PrintWriter output, boolean label) {
        if (label) {
            if (interactive) { output.println(); output.println(text(Tone.ASSISTANT, "● CodeY")); }
            else output.print("CodeY> ");
        }
        output.flush();
    }
    public void endAssistant(PrintWriter output) {
        output.println();
        if (interactive) output.println();
        output.flush();
    }

    /** ANSI 长度不参与宽度计算；横线少占一列，避免最右列触发终端自动换行。 */
    public String separator(int columns) {
        return text(Tone.MUTED, "─".repeat(Math.max(1, columns - 1)));
    }

    /** 对底栏保留的属性串选择是否着色；NO_COLOR 不影响左右布局。 */
    public AttributedString display(AttributedString value) {
        return colors ? value : new AttributedString(value.toString());
    }

    /** /help 的用法与说明分列，中文按显示宽度对齐；窄屏将说明移至下一行。 */
    public void command(PrintWriter output, String usage, String description, int columns) {
        if (!interactive) { output.println("  " + usage + "  " + description); return; }
        int usageWidth = new AttributedString(safeText(usage)).columnLength();
        if (columns < 80 || usageWidth > 44) {
            output.println("  " + text(Tone.COMMAND, usage));
            output.println("    " + text(Tone.MUTED, description));
        } else output.println("  " + text(Tone.COMMAND, usage) + " ".repeat(46 - usageWidth)
                + text(Tone.MUTED, description));
    }
}
