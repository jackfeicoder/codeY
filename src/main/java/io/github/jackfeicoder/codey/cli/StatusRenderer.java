package io.github.jackfeicoder.codey.cli;

import org.jline.utils.AttributedString;

/** 按终端显示列宽排左右两端，窄屏优先模式；不按 Java 字符数对齐中文。 */
public final class StatusRenderer {
    private StatusRenderer() { }
    public static String render(String left, String right, int columns) {
        int width = Math.max(1, columns - 1);
        AttributedString l = new AttributedString(ConsoleApprovalHandler.clean(left));
        if (l.columnLength() > width) l = l.columnSubSequence(0, width);
        AttributedString r = new AttributedString(ConsoleApprovalHandler.clean(right));
        if (l.columnLength() + r.columnLength() + 2 > width) {
            if (width < 40) return l.columnSubSequence(0, width).toString();
            r = r.columnSubSequence(0, Math.max(0, width - l.columnLength() - 2));
        }
        return l.toString() + " ".repeat(Math.max(0, width - l.columnLength() - r.columnLength())) + r;
    }
}
