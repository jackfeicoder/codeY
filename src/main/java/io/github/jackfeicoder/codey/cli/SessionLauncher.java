package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.SessionSettings;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.SessionException;
import io.github.jackfeicoder.codey.session.SessionService;
import io.github.jackfeicoder.codey.session.SessionSnapshot;
import io.github.jackfeicoder.codey.session.SessionStore;
import io.github.jackfeicoder.codey.session.SessionTitleGenerator;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import io.github.jackfeicoder.codey.workspace.WorkspaceResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/** 会话入口状态机：全局历史分页/选择 → 工作区与标题 → 连续对话，模型配置按需校验。 */
public final class SessionLauncher {
    /** 启动时的可选覆盖；无覆盖时显示历史列表。 */
    public record Options(String workspace, boolean noWorkspace, String title, String resume, String profile) { }
    /** 模型组件在选择会话后才创建；标题客户端使用独立短超时。 */
    public record ModelChoice(String profile, LlmClient chat, LlmClient title) { }

    /** 每次展示/追加的记录数。 */
    private static final int PAGE_SIZE = 10;
    /** 配置查找与相对路径的固定基准。 */
    private final Path startup;
    /** 数据位置、建议工作区与标题开关。 */
    private final SessionSettings settings;
    /** 会话库访问，列表不要求获得写锁。 */
    private final SessionStore store;
    /** 延迟创建模型客户端，参数是明确选择的别名。 */
    private final Function<String, ModelChoice> models;
    /** 新会话加载当前基础提示词，历史会话使用自己的快照。 */
    private final Supplier<String> basePrompt;
    /** 调用方持有的逐行输入，不在启动器内关闭。 */
    private final BufferedReader input;
    /** 正常列表、回答和操作提示。 */
    private final PrintWriter output;
    /** 可展示的配置、存储和调用错误。 */
    private final PrintWriter errors;
    /** CLI 覆盖项，不包含密钥。 */
    private final Options options;
    /** Main 注入的工具注册表，具体读取工具不会散落在会话业务中创建。 */
    private final ToolRegistry tools;
    /** Agent 的简洁进度输出，不加入会话消息。 */
    private final DebugPrinter debug;
    /** 已显示列表的稳定快照与数量，分页不会重新编号。 */
    private List<SessionSnapshot> listing = List.of();
    /** 本次列表已显示数量，序号不随着翻页重置。 */
    private int displayed;
    /** 活动会话仍持有写锁，查看列表不会关闭或丢弃它。 */
    private SessionService active;
    /** 当前会话绑定的普通与标题客户端。 */
    private ModelChoice model;
    /** true 时普通文本是选择器，false 时普通文本是用户问题。 */
    private boolean browsing = true;
    /** EOF 或任意输入阶段的 /exit 使入口结束。 */
    private boolean exiting;

    /** 输入、模型工厂和存储可替换，测试无需真实终端、用户目录或厂商凭证。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           BufferedReader input, PrintWriter output, PrintWriter errors, Options options) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, new ToolRegistry());
    }

    /** 生产装配入口，工具列表由 Main 统一提供；旧入口默认没有注册工具。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           BufferedReader input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, DebugPrinter.NONE);
    }

    /** Main 统一提供工具与进度输出，独立标题请求保持静默。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           BufferedReader input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug) {
        this.startup = startup.toAbsolutePath().normalize();
        this.settings = settings;
        this.store = store;
        this.models = models;
        this.basePrompt = basePrompt;
        this.input = input;
        this.output = output;
        this.errors = errors;
        this.options = options;
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.debug = java.util.Objects.requireNonNull(debug, "debug");
    }

    /** EOF 或 /exit 结束；错误保存会话返回 1，参数配置错误由 Main 分类。 */
    public int run() throws IOException {
        try {
            if (options.resume() != null) {
                resume(options.resume(), false);
            } else if (options.workspace() != null || options.noWorkspace() || options.title() != null) {
                newSession(options.workspace(), options.noWorkspace(), options.title());
            }
            if (browsing && !exiting) refresh();
            while (!exiting && !Thread.currentThread().isInterrupted()) {
                String line = read(browsing ? "选择会话> " : "You> ");
                if (line == null || "/exit".equals(line.strip())) {
                    if (!savePending()) return 1;
                    output.println("Bye.");
                    return 0;
                }
                if (line.isBlank()) continue;
                if (!savePending()) continue; // 不覆盖未保存结果，也不重复调用模型。
                String command = line.strip();
                try {
                    if ("exit".equalsIgnoreCase(command)) {
                        if (browsing) {
                            output.println("Bye.");
                            return 0; // 列表中 exit 退出；聊天中 exit 只返回列表。
                        }
                        browsing = true;
                        refresh(); // 刚才的轮次已保存；保留活动会话，可 /back 或选择其他会话。
                    } else if ("/new".equals(command)) {
                        newSession(null, false, null);
                    } else if ("/clear".equals(command) && active != null) {
                        replace(SessionService.create(store, active.snapshot().workspace(), model.profile(),
                                basePrompt.get(), null), model);
                    } else if ("/sessions".equals(command) || "/resume".equals(command)) {
                        browsing = true;
                        refresh();
                    } else if ("/more_session".equals(command)) {
                        if (!browsing) {
                            browsing = true;
                            refresh();
                        } else more();
                    } else if (command.startsWith("/resume ")) {
                        resume(unquote(command.substring(8).strip()), false);
                    } else if (command.startsWith("/rename ") && active != null) {
                        rename(unquote(command.substring(8).strip()));
                    } else if ("/back".equals(command) && active != null) {
                        browsing = false;
                    } else if (command.startsWith("/")) {
                        output.println("命令：/new /sessions /more_session /resume 标题或ID /rename 标题 /clear；exit 返回列表；/exit 退出程序");
                    } else if (browsing) {
                        resume(command, true);
                    } else {
                        answer(line);
                    }
                } catch (SessionException | IllegalArgumentException failed) {
                    errors.println((active != null && active.dirty() ? "未保存，结果仍在内存：" : "Session error: ")
                            + failed.getMessage());
                } catch (LlmException failed) {
                    errors.println("LLM error [" + failed.kind() + "]: " + failed.getMessage()
                            + " This turn was not saved.");
                    if (failed.kind() == LlmException.Kind.INTERRUPTED) return 1;
                }
                output.flush();
                errors.flush();
            }
            return savePending() ? 0 : 1;
        } finally {
            if (active != null) active.close();
            output.flush();
            errors.flush();
        }
    }

    /** 更新全局列表快照，下一页沿用它，避免排序变化导致选错序号。 */
    private void refresh() {
        SessionStore.Listing result = store.list();
        listing = result.sessions();
        displayed = 0;
        result.warnings().forEach(errors::println);
        output.println("CodeY · 所有工作区的最近会话");
        output.println("序号 | ID | 标题 | 工作区 | 更新时间");
        more();
        output.println("输入序号、完整标题或 ID；/new 新建，/more_session 下一批，/sessions 刷新，exit 或 /exit 退出。");
        if (active != null) output.println("/back 返回当前会话。");
    }

    /** 每次追加 10 条，末尾提示明确，不循环回第一页。 */
    private void more() {
        if (displayed >= listing.size()) {
            output.println(displayed == 0 ? "暂无历史会话，请输入 /new。" : "没有更多会话。");
            return;
        }
        int end = Math.min(displayed + PAGE_SIZE, listing.size());
        for (int index = displayed; index < end; index++) {
            SessionSnapshot snapshot = listing.get(index);
            String time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault()).format(Instant.parse(snapshot.updatedAt()));
            output.println((index + 1) + " | " + shortId(snapshot.id()) + " | " + snapshot.title()
                    + " | " + snapshot.workspace().display() + " | " + time);
        }
        displayed = end;
        output.println("已显示 " + displayed + " / " + listing.size() + " 条。");
    }

    /** 显示前缀在整份列表中唯一；完整 ID 始终用于保存和读取。 */
    private String shortId(String id) {
        int length = 8;
        while (length < id.length()) {
            String prefix = id.substring(0, length);
            if (listing.stream().filter(s -> s.id().startsWith(prefix)).count() <= 1) break;
            length++;
        }
        return id.substring(0, length);
    }

    /** 新建只在明确选择后进行；标题留空走首轮生成，/back 保留原会话。 */
    private void newSession(String path, boolean none, String suppliedTitle) throws IOException {
        WorkspaceBinding workspace;
        if (none) workspace = WorkspaceBinding.none();
        else if (path != null) workspace = WorkspaceResolver.resolve(startup, path);
        else workspace = chooseWorkspace();
        if (workspace == null) return;
        String title = suppliedTitle;
        if (title == null) {
            String line = read("会话标题（留空自动生成，/back 返回）> ");
            if (line == null) { exiting = true; return; }
            if ("/back".equals(line.strip())) return;
            title = line.isBlank() ? null : line.strip();
        }
        if (title != null && !SessionSnapshot.validTitle(title)) {
            throw new IllegalArgumentException("标题应为 1–40 个字符的单行文本。");
        }
        ModelChoice selected = chooseModel(null);
        if (selected != null) {
            replace(SessionService.create(store, workspace, selected.profile(), basePrompt.get(), title), selected);
        }
    }

    /** 工作区选项只决定绑定，不 chdir，也不预先读取项目文件。 */
    private WorkspaceBinding chooseWorkspace() throws IOException {
        while (!exiting) {
            output.println("工作区：1 当前目录 " + startup + "；2 输入目录；3 普通聊天；0 返回");
            if (settings.suggestedWorkspace() != null && !settings.suggestedWorkspace().isBlank()) {
                output.println("4 配置建议目录 " + settings.suggestedWorkspace());
            }
            String choice = read("工作区> ");
            if (choice == null) { exiting = true; return null; }
            choice = choice.strip();
            if ("0".equals(choice) || "/back".equals(choice)) return null;
            if ("3".equals(choice)) return WorkspaceBinding.none();
            String path = null;
            if ("1".equals(choice)) path = startup.toString();
            else if ("2".equals(choice)) path = read("目录路径> ");
            else if ("4".equals(choice)) path = settings.suggestedWorkspace();
            if (path != null) {
                try { return WorkspaceResolver.resolve(startup, unquote(path.strip())); }
                catch (IllegalArgumentException invalid) { errors.println(invalid.getMessage()); }
            } else if ("2".equals(choice)) { exiting = true; return null; }
        }
        return null;
    }

    /** 在全库匹配精确标题或 ID；仅裸数字优先匹配已显示序号。 */
    private void resume(String selector, boolean allowNumber) throws IOException {
        List<SessionSnapshot> matches = new ArrayList<>();
        if (allowNumber && selector.matches("[0-9]+")) {
            try {
                int index = Integer.parseInt(selector) - 1;
                if (index >= 0 && index < displayed) matches.add(listing.get(index));
            } catch (NumberFormatException ignored) { }
        } else {
            String normalizedId = selector.toLowerCase(java.util.Locale.ROOT);
            // 空会话默认不在列表中，但已知完整 UUID 仍可直接恢复。
            if (SessionSnapshot.validId(normalizedId)) matches.add(store.load(normalizedId));
            for (SessionSnapshot snapshot : store.list().sessions()) {
                boolean id = selector.length() >= 8 && snapshot.id().startsWith(selector.toLowerCase(java.util.Locale.ROOT));
                if ((snapshot.title().equals(selector) || id)
                        && matches.stream().noneMatch(s -> s.id().equals(snapshot.id()))) matches.add(snapshot);
            }
        }
        if (matches.isEmpty()) { output.println("未找到会话，请检查标题、ID 或输入 /new。"); return; }
        if (matches.size() > 1) {
            output.println("匹配多个会话，请使用完整 ID：");
            matches.forEach(s -> output.println(s.id() + " | " + s.title() + " | " + s.workspace().display()));
            return;
        }
        SessionSnapshot snapshot = store.load(matches.get(0).id());
        preview(snapshot); // 历史可查看，不依赖模型凭证或有效工作区。
        if (!WorkspaceResolver.available(snapshot.workspace())) {
            output.println("原工作区已失效，保留历史。可 /new 创建其他工作区或普通聊天。");
            return;
        }
        if (active != null && active.snapshot().id().equals(snapshot.id())) {
            browsing = false;
            return;
        }
        ModelChoice selected = chooseModel(snapshot.profileAlias());
        if (selected != null) {
            SessionService candidate = SessionService.resume(store, snapshot.id(), selected.profile());
            try { candidate.save(); } catch (RuntimeException failed) { candidate.close(); throw failed; }
            replace(candidate, selected);
        }
    }

    /** 覆盖优先级：启动 --profile、保存的别名、配置默认；缺失时要求明确选择。 */
    private ModelChoice chooseModel(String savedProfile) throws IOException {
        String profile = options.profile() != null ? options.profile() : savedProfile;
        while (!exiting) {
            try { return models.apply(profile); }
            catch (ConfigException invalid) {
                errors.println("Configuration error: " + invalid.getMessage());
                String choice = read("输入可用的 Profile 别名（/back 返回）> ");
                if (choice == null) { exiting = true; return null; }
                if ("/back".equals(choice.strip())) return null;
                profile = choice.strip();
            }
        }
        return null;
    }

    /** 新会话准备成功后才释放旧锁，失败不会丢失当前会话。 */
    private void replace(SessionService candidate, ModelChoice selected) {
        try {
            if (active != null) active.close();
        } catch (RuntimeException failed) {
            candidate.close();
            throw failed;
        }
        active = candidate;
        model = selected;
        browsing = false;
        output.println("会话：" + active.snapshot().title() + " | ID " + active.snapshot().id()
                + " | " + active.snapshot().workspace().display() + " | Profile " + selected.profile());
        if (!SessionSnapshot.hash(basePrompt.get()).equals(active.snapshot().promptHash())) {
            output.println("基础提示词版本已变化，本会话沿用创建时的快照。");
        }
    }

    /** 预览最近四条消息，有界输出；纯工具调用没有文本时展示名称和调用 ID。 */
    private void preview(SessionSnapshot snapshot) {
        output.println("历史：" + snapshot.title() + " | " + snapshot.id() + " | " + snapshot.workspace().display());
        var messages = snapshot.messages();
        for (int index = Math.max(0, messages.size() - 4); index < messages.size(); index++) {
            var message = messages.get(index);
            String content = message.content() == null ? "工具调用：" + message.toolCalls().stream()
                    .map(call -> call.name() + " [" + call.id() + "]").toList() : message.content();
            if (content.length() > 500) content = content.substring(0, 500) + "…";
            output.println(message.role() + ": " + content);
        }
    }

    /** 先保存成功回答，再执行一次有界标题任务；生成文本不会加入历史。 */
    private void answer(String line) {
        ChatCli chat = new ChatCli(new AgentLoop(model.chat(), tools, AgentLoop.DEFAULT_MAX_STEPS, debug), active.session(),
                new ToolContext(active.snapshot().workspace()), input, output, errors);
        if (chat.answer(line)) {
            active.captureTurn();
            active.save();
            generateTitle();
        }
    }

    /** 提前保存已尝试标记，崩溃恢复也不会重复请求标题模型。 */
    private void generateTitle() {
        SessionSnapshot snapshot = active.snapshot();
        if (snapshot.titleSource() != SessionSnapshot.TitleSource.PENDING
                || snapshot.titleGenerationAttempted() || snapshot.messages().isEmpty()) return;
        active.setTitle(snapshot.title(), SessionSnapshot.TitleSource.PENDING, true);
        active.save();
        SessionTitleGenerator.Result result = settings.generateTitles()
                ? new SessionTitleGenerator().generate(model.title(), snapshot.messages().get(0).content())
                : new SessionTitleGenerator.Result(snapshot.title(), false);
        active.setTitle(result.title(), result.generated() ? SessionSnapshot.TitleSource.LLM
                : SessionSnapshot.TitleSource.FALLBACK, true);
        active.save();
        output.println("会话标题：" + result.title());
        if (settings.generateTitles() && !result.generated()) errors.println("标题生成未完成，已保留首问摘要。");
    }

    /** 用户改名优先，之后不自动覆盖；ID 与消息不变。 */
    private void rename(String title) {
        if (!SessionSnapshot.validTitle(title)) throw new IllegalArgumentException("标题应为 1–40 个字符的单行文本。");
        active.setTitle(title, SessionSnapshot.TitleSource.MANUAL, true);
        active.save();
        output.println("会话已改名：" + title);
    }

    /** 在任何新调用或切换前重试未保存快照，错误明确报告而不重复生成回答。 */
    private boolean savePending() {
        try {
            if (active != null) active.save();
            return true;
        } catch (SessionException failed) {
            errors.println("未保存：" + failed.getMessage() + "；结果仍在内存，下一次输入将重试保存。");
            errors.flush();
            return false;
        }
    }

    /** 显示输入提示并处理所有输入阶段的 EOF 与 /exit。 */
    private String read(String prompt) throws IOException {
        output.print(prompt);
        output.flush();
        errors.flush();
        String line = input.readLine();
        if (line == null || "/exit".equals(line.strip())) {
            exiting = true;
            return null;
        }
        return line;
    }

    /** 命令剩余文本作为完整标题/路径，支持可选外层引号，不交给 shell 解释。 */
    private static String unquote(String text) {
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) return text.substring(1, text.length() - 1);
        return text;
    }
}
