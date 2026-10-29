package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.io.PrintWriter;

/** 用户显式整理、查看及恢复命令；默认当前项目，无工作区默认用户范围。 */
public final class MemoryMaintenanceCommands {
    private final MemoryConsolidator maintenance;
    private final WorkspaceBinding workspace;
    private final PrintWriter output;
    public MemoryMaintenanceCommands(MemoryConsolidator maintenance, WorkspaceBinding workspace, PrintWriter output) {
        this.maintenance = maintenance; this.workspace = workspace; this.output = output;
    }
    /** false表示普通/memory关键词查询，继续交给原MemoryCommands。 */
    public boolean execute(String arguments, LlmClient client, ContextBudgetPolicy budget) {
        String[] parts = arguments.strip().split("\\s+");
        String action = parts[0];
        if (!java.util.List.of("tidy", "archives", "versions", "restore", "rollback").contains(action)) return false;
        MemoryScope scope = workspace.mode() == WorkspaceBinding.Mode.DIRECTORY ? MemoryScope.PROJECT : MemoryScope.USER;
        boolean recovery = action.equals("restore") || action.equals("rollback");
        int index = 1;
        if (index < parts.length && parts[index].startsWith("--")) {
            scope = switch (parts[index++]) { case "--user" -> MemoryScope.USER; case "--project" -> MemoryScope.PROJECT;
                default -> throw new MemoryException("仅支持 --user 或 --project。"); };
        }
        if (parts.length - index != (recovery ? 1 : 0)) throw new MemoryException("用法：/memory " + action + " [--user|--project]" + (recovery ? " 完整ID" : ""));
        if (action.equals("tidy")) {
            if (client == null) throw new MemoryException("请先选择会话/模型，再整理记忆。");
            var result = maintenance.tidy(workspace,scope,true,client,budget,text -> { output.println(text); output.flush(); });
            output.println("[记忆整理] " + ConsoleApprovalHandler.clean(result.detail()));
            result.reviews().forEach(text -> output.println("  待检查：" + ConsoleApprovalHandler.clean(text)));
        } else if (action.equals("archives")) {
            var archives = maintenance.archives(workspace,scope); output.println(scope + " 记忆归档：" + archives.size() + " 组（最多展示100组）");
            archives.stream().limit(100).forEach(a -> {
                output.println(a.id() + " | " + a.kind() + " | " + a.createdAt() + " | " + ConsoleApprovalHandler.clean(a.reason()));
                a.originals().forEach(e -> output.println("  来源 " + e.id() + " | " + ConsoleApprovalHandler.clean(e.title()) + " | " + ConsoleApprovalHandler.clean(e.content())));
            });
        } else if (action.equals("versions")) {
            var versions = maintenance.versions(workspace,scope); output.println(scope + " 可恢复记忆版本：" + versions.size());
            versions.forEach(v -> output.println(v.id() + " | " + v.createdAt() + " | " + ConsoleApprovalHandler.clean(v.reason()) + " | 有效 " + v.entries().size() + " / 归档 " + v.archived().size()));
        } else if (action.equals("restore")) {
            maintenance.restoreArchive(workspace,scope,parts[index]); output.println("已恢复此组归档原文；恢复前状态也已保留为版本。");
        } else {
            maintenance.rollback(workspace,scope,parts[index]); output.println("已回滚整个 " + scope + " 范围的有效/归档记忆，版本后的修改已撤销；回滚前状态也已保存。");
        }
        return true;
    }
}
