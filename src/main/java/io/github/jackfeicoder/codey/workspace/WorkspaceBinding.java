package io.github.jackfeicoder.codey.workspace;

import java.nio.file.Path;

/** 会话的工作区属性；NONE 明确表示普通聊天，不隐式使用当前目录。 */
public record WorkspaceBinding(Mode mode, String path) {
    /** 工作区是否绑定目录。 */
    public enum Mode { DIRECTORY, NONE }

    /** 磁盘数据仅校验结构；目录是否仍存在由恢复入口重新检查。 */
    public WorkspaceBinding {
        if (mode == null || (mode == Mode.NONE && path != null)
                || (mode == Mode.DIRECTORY && (path == null || !Path.of(path).isAbsolute()))) {
            throw new IllegalArgumentException("Invalid workspace binding.");
        }
    }

    /** 创建无工作区的会话属性。 */
    public static WorkspaceBinding none() {
        return new WorkspaceBinding(Mode.NONE, null);
    }

    /** 返回列表展示文本，不用于文件路径计算。 */
    public String display() {
        return mode == Mode.NONE ? "普通聊天" : path;
    }
}
