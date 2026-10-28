package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ModelProfiles;
import io.github.jackfeicoder.codey.config.ProfileSelection;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 根据输入位置生成候选：命令 → /model 供应商 → 该供应商的模型 ID。 */
public final class CommandCompleter implements Completer {
    /** 读取本地非敏感模型目录，Tab 不创建客户端、不请求模型。 */
    private final Supplier<List<ModelProfiles.ProfileInfo>> models;
    /** 仅管理页和聊天输入启用；路径、标题等辅助输入关闭补全。 */
    private final BooleanSupplier enabled;

    public CommandCompleter(Supplier<List<ModelProfiles.ProfileInfo>> models, BooleanSupplier enabled) {
        this.models = java.util.Objects.requireNonNull(models, "models");
        this.enabled = java.util.Objects.requireNonNull(enabled, "enabled");
    }

    /** JLine 负责显示菜单和编辑当前词；这里仅产生与当前位置匹配的候选。 */
    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (!enabled.getAsBoolean()) return;
        String prefix = line.word().substring(0, line.wordCursor());
        if (line.wordIndex() == 0) {
            for (var command : CommandCatalog.commands()) {
                add(candidates, prefix, command.name(), command.description());
            }
            return;
        }
        String command = line.words().get(0);
        if ("/remember".equals(command) && prefix.startsWith("--")) {
            for (String option : List.of("--user", "--project", "--always", "--on-demand", "--update", "--title", "--tags"))
                add(candidates, prefix, option, "长期记忆选项");
        } else if ("/permissions".equals(command) && line.wordIndex() == 1) {
            add(candidates, prefix, "ask", "请求批准 · 每个文件首次修改时询问");
            add(candidates, prefix, "auto", "帮我批准 · 普通修改自动，风险操作询问");
            add(candidates, prefix, "full", "完全访问 · 跳过常规审批，需明确确认");
            add(candidates, prefix, "revoke", "撤销当前会话的文件授权及拒绝记录");
        } else if ("/mode".equals(command) && line.wordIndex() == 1) {
            add(candidates, prefix, "react", "执行任务 · 模型与工具循环协作");
            add(candidates, prefix, "plan", "只读计划 · 探索代码并给出实施方案");
        } else if ("/list".equals(command) && line.wordIndex() == 1) {
            add(candidates, prefix, "model", "已配置模型目录");
        } else if ("/model".equals(command) && line.wordIndex() <= 2) {
            try {
                List<ModelProfiles.ProfileInfo> catalog = models.get();
                if (line.wordIndex() == 1) {
                    catalog.stream().map(ModelProfiles.ProfileInfo::alias).distinct()
                            .forEach(alias -> add(candidates, prefix, alias, "供应商 / Profile"));
                } else {
                    String alias = ProfileSelection.parse(line.words().get(1)).alias();
                    catalog.stream().filter(entry -> entry.alias().equals(alias)).forEach(entry -> {
                        try {
                            new ProfileSelection(alias, entry.modelId()); // 排除列表错误说明等非模型 ID。
                            add(candidates, prefix, entry.modelId(), entry.configured() ? "配置有效" : "配置待完善");
                        } catch (ConfigException ignored) { }
                    });
                }
            } catch (ConfigException ignored) {
                // 配置错误由 /list model 或真正切换报告，按 Tab 时不打断正在编辑的输入。
            }
        }
    }

    /** 精确前缀匹配，保留模型 ID 的大小写，普通聊天内容没有匹配候选。 */
    private static void add(List<Candidate> candidates, String prefix, String value, String description) {
        if (value.startsWith(prefix)) candidates.add(new Candidate(value, value, null, description, null, null, true));
    }
}
