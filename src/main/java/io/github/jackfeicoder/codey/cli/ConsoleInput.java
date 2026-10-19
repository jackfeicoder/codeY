package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ModelProfiles;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.function.Supplier;

/** 输入适配：交互终端使用 JLine，管道/测试沿用逐行读取，退出恢复终端状态。 */
public final class ConsoleInput implements AutoCloseable {
    /** 原逐行输入及输出；不关闭进程标准输入。 */
    private final BufferedReader plain;
    private final PrintWriter output;
    /** 仅原生交互控制台创建，关闭时释放并恢复属性。 */
    private Terminal terminal;
    private LineReader reader;
    /** 主输入与辅助输入共用同一个 Reader，但辅助输入不提示命令。 */
    private boolean completeCommands;
    /** 仅交互终端固定底栏；管道使用可读单行，不发送 ANSI 控制码。 */
    private RuntimeStatus runtimeStatus;
    private org.jline.utils.Status footer;

    /** 测试与重定向使用普通输入，不依赖原生终端。 */
    public ConsoleInput(BufferedReader plain, PrintWriter output) {
        this.plain = java.util.Objects.requireNonNull(plain, "plain");
        this.output = java.util.Objects.requireNonNull(output, "output");
    }

    /** 已有终端的宿主/包内测试可注入终端，关闭输入时一并释放它。 */
    ConsoleInput(BufferedReader plain, PrintWriter output, Terminal terminal) {
        this(plain, output);
        this.terminal = java.util.Objects.requireNonNull(terminal);
        this.reader = createReader(terminal, new CommandCompleter(List::of, () -> completeCommands));
    }

    /** 仅真实控制台尝试启动 JLine；环境不支持时保留原输入功能。 */
    public static ConsoleInput open(BufferedReader plain, PrintWriter output,
                                    Supplier<List<ModelProfiles.ProfileInfo>> models) {
        ConsoleInput input = new ConsoleInput(plain, output);
        String term = System.getenv("TERM");
        if (System.console() == null || (term != null && term.startsWith("dumb"))) return input;
        try {
            input.terminal = TerminalBuilder.builder().system(true).dumb(false)
                    .encoding(System.console().charset()).build();
            input.reader = createReader(input.terminal, new CommandCompleter(models, () -> input.completeCommands));
        } catch (IOException | RuntimeException | LinkageError unsupported) {
            try { input.close(); } catch (IOException ignored) { }
            input.reader = null;
            input.terminal = null;
            output.println("当前终端未启用 Tab 补全，继续使用普通逐行输入。");
        }
        return input;
    }

    /** 统一编辑器配置；测试可用虚拟终端验证实际按键行为，无需真实控制台。 */
    static LineReader createReader(Terminal terminal, org.jline.reader.Completer completer) {
        LineReader lineReader = LineReaderBuilder.builder().terminal(terminal).appName("CodeY")
                .parser(new DefaultParser().escapeChars(null).quoteChars(null)).completer(completer).build();
        // 不展开 ! 历史语法；候选只做前缀匹配，不纠正模型 ID 的拼写。
        lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION);
        lineReader.setOpt(LineReader.Option.AUTO_LIST);
        lineReader.setOpt(LineReader.Option.LIST_ROWS_FIRST);
        lineReader.setOpt(LineReader.Option.AUTO_MENU_LIST);
        lineReader.unsetOpt(LineReader.Option.AUTO_MENU);
        lineReader.setVariable(LineReader.ERRORS, 0);
        // JLine 的部分终端会把鼠标序列注册为字面量 \E[M，使反斜杠等待后续按键并吞掉中文前的路径分隔符。
        // 此 CLI 不使用鼠标操作，移除这个字面量前缀，保持 Windows 路径原样输入。
        KeyMap<org.jline.reader.Binding> original = lineReader.getKeyMaps().get(LineReader.MAIN);
        KeyMap<org.jline.reader.Binding> clean = new KeyMap<>();
        original.getBoundKeys().forEach((key, binding) -> {
            if (!key.startsWith("\\E[")) clean.bind(binding, key);
        });
        clean.setUnicode(original.getUnicode());
        clean.setNomatch(original.getNomatch());
        clean.setAmbiguousTimeout(original.getAmbiguousTimeout());
        // unbind 会保留空前缀节点，因此重建映射；MAIN 与 EMACS 原本指向同一个映射。
        lineReader.getKeyMaps().replaceAll((name, keys) -> keys == original ? clean : keys);
        // 多个候选时直接进入菜单，方向键由 JLine 的菜单按键映射处理。
        lineReader.getWidgets().put("codey-complete", () -> {
            if (terminal.getHeight() < 4 || terminal.getWidth() < 20) {
                // 没有足够空间显示候选时只做文字补全，不进入菜单；终端恢复大小后自动恢复菜单。
                boolean autoList = lineReader.isSet(LineReader.Option.AUTO_LIST);
                lineReader.unsetOpt(LineReader.Option.AUTO_LIST);
                try { lineReader.callWidget(LineReader.COMPLETE_WORD); }
                finally { if (autoList) lineReader.setOpt(LineReader.Option.AUTO_LIST); }
            } else lineReader.callWidget(LineReader.MENU_COMPLETE);
            return true;
        });
        lineReader.getKeyMaps().get(LineReader.MAIN)
                .bind(new org.jline.reader.Reference("codey-complete"), "\t");
        lineReader.getKeyMaps().get(LineReader.MENU)
                .bind(new org.jline.reader.Reference(LineReader.MENU_COMPLETE), "\t");
        lineReader.getKeyMaps().get(LineReader.MENU)
                .bind(new org.jline.reader.Reference(LineReader.REVERSE_MENU_COMPLETE), "\u001b[A", "\u001bOA");
        lineReader.getKeyMaps().get(LineReader.MENU)
                .bind(new org.jline.reader.Reference(LineReader.MENU_COMPLETE), "\u001b[B", "\u001bOB");
        String backTab = KeyMap.key(terminal, org.jline.utils.InfoCmp.Capability.back_tab);
        if (backTab != null) lineReader.getKeyMaps().get(LineReader.MENU)
                .bind(new org.jline.reader.Reference(LineReader.REVERSE_MENU_COMPLETE), backTab);
        return lineReader;
    }

    /** Ctrl+C 取消当前编辑，EOF 交给启动器保存退出；补全本身不会执行命令。 */
    public String readLine(String prompt) throws IOException {
        boolean mainPrompt = "You> ".equals(prompt) || "选择会话> ".equals(prompt);
        if (mainPrompt) refreshStatus();
        if (reader == null) {
            output.print(prompt);
            output.flush();
            return plain.readLine();
        }
        completeCommands = "You> ".equals(prompt) || "选择会话> ".equals(prompt);
        try { return reader.readLine(prompt); }
        catch (UserInterruptException cancelled) { return ""; }
        catch (EndOfFileException ended) { return null; }
    }

    /** 首版状态来源由启动器配置；测试或其他调用不配置时保持原输入行为。 */
    public void status(RuntimeStatus status) {
        runtimeStatus = status;
        if (status == null && footer != null) footer.hide();
    }
    public boolean isInteractive() { return reader != null; }
    public void refreshStatus() {
        if (runtimeStatus == null) return;
        if (terminal == null) {
            output.println("[" + runtimeStatus.left() + "] " + ConsoleApprovalHandler.clean(runtimeStatus.right()));
        } else {
            if (footer == null) footer = org.jline.utils.Status.getStatus(terminal);
            footer.resize();
            footer.update(List.of(new org.jline.utils.AttributedString(StatusRenderer.render(
                    runtimeStatus.left(), runtimeStatus.right(), terminal.getWidth()))));
        }
        output.flush();
    }
    /** 审批中 Ctrl+C/EOF 均返回未批准，不进入主聊天命令处理。 */
    public String readApproval(String prompt) throws IOException {
        completeCommands = false;
        if (reader == null) { output.print(prompt); output.flush(); return plain.readLine(); }
        try { return reader.readLine(prompt); }
        catch (UserInterruptException | EndOfFileException cancelled) { return null; }
    }

    /** 关闭终端恢复原始模式，普通输入由进程持有。 */
    @Override
    public void close() throws IOException {
        if (footer != null) footer.close();
        if (terminal != null) terminal.close();
    }
}
