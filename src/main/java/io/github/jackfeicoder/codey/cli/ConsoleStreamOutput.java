package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.AgentStreamListener;
import java.io.PrintWriter;

/** 流式正文展示：即时刷新，不重复最终全文，跨分片处理 CRLF、代理对与终端控制字符。 */
public final class ConsoleStreamOutput implements AgentStreamListener {
    private final ConsoleTheme theme;
    private final PrintWriter output;
    private final Runnable afterResponse;
    private final boolean label;
    /** 只保存最小展示状态，完整消息由 LLM 聚合；不保存提示词或工具参数。 */
    private boolean responseHadText;
    private boolean displayedText;
    private char pendingHigh;
    private boolean pendingCr;

    public ConsoleStreamOutput(ConsoleTheme theme, PrintWriter output, Runnable afterResponse, boolean label) {
        this.theme = java.util.Objects.requireNonNull(theme); this.output = java.util.Objects.requireNonNull(output);
        this.afterResponse = java.util.Objects.requireNonNull(afterResponse); this.label = label;
    }
    @Override public void onResponseStart(int step) {
        responseHadText = false; pendingHigh = 0; pendingCr = false;
    }
    @Override public void onText(String delta) {
        if (delta.isEmpty()) return;
        if (!responseHadText) theme.beginAssistant(output, label);
        responseHadText = true; displayedText = true;
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (pendingCr) {
                pendingCr = false;
                if (ch == '\n') { visible.append('\n'); continue; }
                visible.append("\\u000d");
            }
            if (pendingHigh != 0) {
                if (Character.isLowSurrogate(ch)) {
                    visible.append(ConsoleTheme.safeText(new String(new char[]{pendingHigh, ch}))); pendingHigh = 0; continue;
                }
                visible.append(String.format("\\u%04x", (int) pendingHigh)); pendingHigh = 0;
            }
            if (ch == '\r') pendingCr = true;
            else if (Character.isHighSurrogate(ch)) pendingHigh = ch;
            else if (Character.isLowSurrogate(ch)) visible.append(String.format("\\u%04x", (int) ch));
            else visible.append(ConsoleTheme.safeText(String.valueOf(ch)));
        }
        output.print(visible); output.flush();
    }
    /** 工具进度/审批提示前关闭本段正文；失败时也收尾，不把错误接在半行回答后。 */
    @Override public void onResponseEnd(int step) {
        if (responseHadText) {
            if (pendingCr) output.print("\\u000d");
            if (pendingHigh != 0) output.print(String.format("\\u%04x", (int) pendingHigh));
            pendingCr = false; pendingHigh = 0;
            theme.endAssistant(output);
        }
        afterResponse.run();
    }
    public boolean lastResponseHadText() { return responseHadText; }
    public boolean hasDisplayedText() { return displayedText; }
}
