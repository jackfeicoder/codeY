package io.github.jackfeicoder.codey.cli;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** 按终端显示列宽排左右两端，窄屏优先模式；不按 Java 字符数对齐中文。 */
public final class StatusRenderer {
    private StatusRenderer() { }
    public static String render(String left, String right, int columns) {
        return layout(new AttributedString(ConsoleApprovalHandler.clean(left)),
                new AttributedString(ConsoleApprovalHandler.clean(right)), columns).toString();
    }

    /** 左侧保留边距与完整模式；不同权限、模型和用量各自着色，不用 ANSI 字符数排版。 */
    public static AttributedString styled(RuntimeStatus status, int columns) {
        var left = new AttributedStringBuilder();
        if (columns >= 16) left.append("  ");
        left.style(status.workMode() == io.github.jackfeicoder.codey.agent.WorkMode.PLAN
                ? AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold()
                : ConsoleTheme.style(ConsoleTheme.Tone.USER)).append(status.workMode().value());
        left.style(ConsoleTheme.style(ConsoleTheme.Tone.MUTED)).append(" · ");
        var permissionTone = switch (status.permissionMode()) {
            case ASK -> ConsoleTheme.Tone.WARNING;
            case AUTO -> ConsoleTheme.Tone.SUCCESS;
            case FULL -> ConsoleTheme.Tone.ERROR;
        };
        left.style(ConsoleTheme.style(permissionTone)).append(status.permissionMode().value());
        String count = status.usedTokensText();
        String capacity = status.contextWindowText();
        String percent = status.usagePercentText();
        int budget = Math.max(0, columns - 1 - left.columnLength() - 2);
        boolean compact = budget < new AttributedString("已用 " + count + " / " + capacity + " (" + percent + ")").columnLength() + 7;
        String prefix = compact ? "已用" : "已用 ";
        String separator = compact ? "/" : " / ";
        String percentagePrefix = compact ? "(" : " (";
        int modelBudget = Math.max(0, budget - new AttributedString(prefix + count + separator + capacity
                + percentagePrefix + percent + ")").columnLength() - 3);
        AttributedString model = new AttributedString(ConsoleApprovalHandler.clean(status.model()));
        if (model.columnLength() > modelBudget) {
            model = modelBudget < 2 ? AttributedString.EMPTY : new AttributedStringBuilder()
                    .append(model.columnSubSequence(0, modelBudget - 1)).append("…").toAttributedString();
        }
        var right = new AttributedStringBuilder()
                .style(ConsoleTheme.style(ConsoleTheme.Tone.COMMAND)).append(model.toString())
                .style(ConsoleTheme.style(ConsoleTheme.Tone.MUTED)).append(model.isEmpty() ? "" : " | ").append(prefix)
                .style(status.usedTokens() == null ? ConsoleTheme.style(ConsoleTheme.Tone.MUTED)
                        : AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold())
                .append(count)
                .style(ConsoleTheme.style(ConsoleTheme.Tone.MUTED)).append(separator)
                .style(ConsoleTheme.style(status.contextWindow() == null ? ConsoleTheme.Tone.MUTED : ConsoleTheme.Tone.COMMAND))
                .append(capacity)
                .style(ConsoleTheme.style(ConsoleTheme.Tone.MUTED)).append(percentagePrefix)
                .style(status.usedTokens() == null || status.contextWindow() == null
                        ? ConsoleTheme.style(ConsoleTheme.Tone.MUTED)
                        : AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA).bold())
                .append(percent).style(ConsoleTheme.style(ConsoleTheme.Tone.MUTED)).append(")");
        return layout(left.toAttributedString(), right.toAttributedString(), columns);
    }

    /** 属性串裁剪保持颜色和 Unicode 显示列一致，窄屏先让右侧模型/用量腾出空间。 */
    private static AttributedString layout(AttributedString left, AttributedString right, int columns) {
        int width = Math.max(1, columns - 1);
        AttributedString l = left;
        if (l.columnLength() > width) l = l.columnSubSequence(0, width);
        AttributedString r = right;
        if (l.columnLength() + r.columnLength() + 2 > width) {
            if (width < 40) return l.columnSubSequence(0, width);
            r = r.columnSubSequence(0, Math.max(0, width - l.columnLength() - 2));
        }
        return new AttributedStringBuilder().append(l).append(" ".repeat(Math.max(0,
                width - l.columnLength() - r.columnLength()))).append(r).toAttributedString();
    }
}
