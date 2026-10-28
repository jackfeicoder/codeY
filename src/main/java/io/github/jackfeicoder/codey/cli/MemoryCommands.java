package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.io.PrintWriter;
import java.util.*;

/** 记忆管理命令；只处理用户显式输入，不接受模型回复触发写入。 */
public final class MemoryCommands {
    private final MemoryService service;
    private final WorkspaceBinding workspace;
    private final String sessionId;
    private final PrintWriter output;
    public MemoryCommands(MemoryService service, WorkspaceBinding workspace, String sessionId, PrintWriter output) {
        this.service = service; this.workspace = workspace; this.sessionId = sessionId; this.output = output;
    }
    /** 选项只在内容前解析，-- 后全文当内容；选项参数支持单双引号。 */
    public void remember(String arguments) {
        MemoryScope scope = null;
        MemoryLoading loading = MemoryLoading.ON_DEMAND;
        boolean loadingSet = false;
        String title = null, update = null;
        List<String> tags = List.of();
        int cursor = 0;
        var seen = new HashSet<String>();
        while (true) {
            cursor = skip(arguments, cursor);
            if (!arguments.startsWith("--", cursor)) break;
            var option = word(arguments, cursor); cursor = option.end();
            if (option.value().equals("--")) break;
            if (!seen.add(option.value())) throw new MemoryException("记忆选项重复。");
            switch (option.value()) {
                case "--user", "--project" -> {
                    if (scope != null) throw new MemoryException("只选择一个记忆作用域。");
                    scope = option.value().equals("--user") ? MemoryScope.USER : MemoryScope.PROJECT;
                }
                case "--always", "--on-demand" -> {
                    if (loadingSet) throw new MemoryException("只选择一种加载方式。");
                    loadingSet = true;
                    loading = option.value().equals("--always") ? MemoryLoading.ALWAYS : MemoryLoading.ON_DEMAND;
                }
                case "--title", "--tags", "--update" -> {
                    var value = word(arguments, skip(arguments, cursor)); cursor = value.end();
                    if (value.value().isBlank() || value.value().startsWith("--")) throw new MemoryException("记忆选项缺少参数。");
                    if (option.value().equals("--title")) title = value.value();
                    else if (option.value().equals("--update")) update = value.value();
                    else tags = Arrays.stream(value.value().split(",", -1)).map(String::strip).toList();
                }
                default -> throw new MemoryException("未知记忆选项；使用 /help 查看用法。");
            }
        }
        if (scope == null) {
            if (workspace.mode() == WorkspaceBinding.Mode.NONE) throw new MemoryException("无工作区时请显式指定 --user。");
            scope = MemoryScope.PROJECT;
        }
        var saved = service.remember(workspace, scope, loading, title, arguments.substring(cursor), tags, sessionId, update);
        output.println((update == null ? "已保存记忆：" : "已更新记忆：") + saved.id() + " | " + saved.scope() + " | " + saved.loading());
    }
    /** 列出当前可访问条目；关键词本地检索，不遍历其他项目，最多展示100条。 */
    public void list(String query) {
        var entries = service.list(workspace);
        if (!query.isBlank()) entries = new Bm25MemoryRetriever().search(query, entries).stream().map(MemoryRetriever.Hit::entry).toList();
        else entries = entries.stream().sorted(Comparator.comparing(MemoryEntry::updatedAt).reversed()).toList();
        output.println("长期记忆（用户级 + 当前项目；AGENTS.md 单独管理）：" + entries.size() + " 条");
        entries.stream().limit(100).forEach(e -> {
            output.println(e.id() + " | " + e.scope() + " | " + e.loading() + " | " + ConsoleApprovalHandler.clean(e.title()));
            output.println("  " + ConsoleApprovalHandler.clean(e.content()) + (e.tags().isEmpty() ? "" : " | 标签 " + ConsoleApprovalHandler.clean(String.join(",", e.tags()))));
        });
        if (entries.size() > 100) output.println("仅展示前100条，可用 /memory 关键词 缩小范围。");
    }
    public void forget(String id) { service.forget(workspace, id.strip()); output.println("已删除记忆：" + id.strip()); }
    private record Word(String value, int end) { }
    private static int skip(String text, int offset) {
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
        return offset;
    }
    private static Word word(String text, int start) {
        if (start >= text.length()) throw new MemoryException("记忆选项缺少参数。");
        char quote = text.charAt(start);
        if (quote == '\'' || quote == '"') {
            int end = text.indexOf(quote, start + 1);
            if (end < 0 || (end + 1 < text.length() && !Character.isWhitespace(text.charAt(end + 1))))
                throw new MemoryException("记忆选项引号未正确闭合。");
            return new Word(text.substring(start + 1, end), end + 1);
        }
        int end = start;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        return new Word(text.substring(start, end), end);
    }
}
