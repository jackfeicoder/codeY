package io.github.jackfeicoder.codey.cli;

import java.util.List;

/** 命令说明的统一目录，供 /help 和 Tab 候选共用；执行仍由 SessionLauncher 分派。 */
public final class CommandCatalog {
    /** name 是第一个命令单词，usage 包含参数，description 是控制台说明。 */
    public record Command(String name, String usage, String description) { }

    /** 固定命令说明，不包含会话内容或模型凭证。 */
    private static final List<Command> COMMANDS = List.of(
            new Command("/help", "/help", "查看全部交互命令"),
            new Command("/permissions", "/permissions [ask|auto|full|revoke]", "查看/切换权限模式或撤销文件授权"),
            new Command("/mode", "/mode [react|plan]", "切换工作模式；multi 尚待实现"),
            new Command("/status", "/status", "查看模式、模型、估算上下文、实际用量和文件授权"),
            new Command("/context", "/context", "查看上下文组装内容与 Token 估算拆分"),
            new Command("/list", "/list model", "查看供应商、模型 ID 和本地配置状态"),
            new Command("/model", "/model [供应商 [模型ID]]", "查看/切换当前模型；省略 ID 使用第一个模型"),
            new Command("/new", "/new", "新建会话，选择工作区和标题"),
            new Command("/sessions", "/sessions", "刷新全局会话列表"),
            new Command("/more_session", "/more_session", "查看下一批会话"),
            new Command("/resume", "/resume [标题或ID]", "打开会话列表或恢复指定会话"),
            new Command("/rename", "/rename 标题", "修改当前会话标题"),
            new Command("/back", "/back", "返回活动会话；新建输入时取消"),
            new Command("/clear", "/clear", "新建同工作区/模型会话，保留旧会话"),
            new Command("/exit", "/exit", "保存并退出程序；EOF 同样退出"));

    private CommandCatalog() { }

    /** 返回不可变命令目录，新增命令时同步更新这里和执行分派。 */
    public static List<Command> commands() { return COMMANDS; }
}
