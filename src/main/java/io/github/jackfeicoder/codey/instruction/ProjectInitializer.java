package io.github.jackfeicoder.codey.instruction;

import io.github.jackfeicoder.codey.prompt.PromptLoader;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

/** 组织初始化任务；模型探索、工具执行和审批复用现有 AgentLoop，不另开执行通道。 */
public final class ProjectInitializer {
    public String task(WorkspaceBinding workspace) {
        if (workspace.mode() != WorkspaceBinding.Mode.DIRECTORY)
            throw new IllegalArgumentException("请先选择项目工作区，再执行 /init。");
        return "检查当前项目，创建或针对性更新工作区根目录的 AGENTS.md，完成后报告文件路径与主要内容。";
    }
    /** 临时指令只进入本次请求的 system 副本，不写入会话基础提示词。 */
    public String instructions() { return PromptLoader.loadInit(); }
}
