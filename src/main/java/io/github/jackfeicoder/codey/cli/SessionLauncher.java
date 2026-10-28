package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.config.PermissionSettings;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.SessionSettings;
import io.github.jackfeicoder.codey.config.ModelPreferenceStore;
import io.github.jackfeicoder.codey.config.ModelProfiles;
import io.github.jackfeicoder.codey.config.ModelCapabilities;
import io.github.jackfeicoder.codey.config.ProfileSelection;
import io.github.jackfeicoder.codey.context.ContextBuilder;
import io.github.jackfeicoder.codey.instruction.ProjectInstructionLoader;
import io.github.jackfeicoder.codey.instruction.ProjectInitializer;
import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.config.MemorySettings;
import io.github.jackfeicoder.codey.context.TokenEstimator;
import io.github.jackfeicoder.codey.context.ContextSnapshot;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.config.ContextBudgetSettings;
import io.github.jackfeicoder.codey.config.CompactionSettings;
import io.github.jackfeicoder.codey.context.HistoryCompactor;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
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
import java.util.function.Consumer;

/** 会话入口状态机：全局历史分页/选择 → 工作区与标题 → 连续对话，模型配置按需校验。 */
public final class SessionLauncher {
    /** 启动时的可选覆盖；无覆盖时显示历史列表。 */
    public record Options(String workspace, boolean noWorkspace, String title, String resume, String profile) { }
    /** profile 保存“供应商 模型ID”（兼容旧单别名）；标题客户端使用独立短超时。 */
    public record ModelChoice(String profile, LlmClient chat, LlmClient title,
                              ModelCapabilities capabilities) {
        /** 原调用者没有容量资料时保留未知，避免将模型规格写死在 CLI。 */
        public ModelChoice(String profile, LlmClient chat, LlmClient title) {
            this(profile, chat, title, ModelCapabilities.unknown());
        }
        public ModelChoice { java.util.Objects.requireNonNull(capabilities, "capabilities"); }
    }

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
    /** 仅加载非敏感模型目录；/list model 不触发客户端创建或远端请求。 */
    private final Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog;
    /** 用户级默认模型偏好，不随工作区改变。 */
    private final ModelPreferenceStore modelPreferences;
    /** 已读取或本次运行最后成功选择的别名。 */
    private String lastUsedProfile;
    /** 避免每次操作重复读取偏好文件或重复报告损坏。 */
    private boolean preferenceLoaded;
    /** --profile 只覆盖本次启动的首次选择，之后 /model 的选择可用于 /new。 */
    private String startupProfile;
    /** 新会话加载当前基础提示词，历史会话使用自己的快照。 */
    private final Supplier<String> basePrompt;
    /** 调用方持有的逐行输入，不在启动器内关闭。 */
    private final ConsoleInput input;
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
    /** 每条用户任务的模型请求上限，已包含最后一次仅回答的收尾请求。 */
    private final int maxIterations;
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
    /** 配置只在宿主启动时读取；模式、批准不写入聊天快照。 */
    private final ConsoleApprovalHandler approvalHandler;
    private PermissionMode permissionMode;
    private WorkMode workMode = WorkMode.REACT;
    private PermissionService permissions;
    private final RuntimeStatus runtimeStatus = new RuntimeStatus();
    /** 输入前预览与 Agent 实际请求共用同一组装器，避免两套用量计算逻辑。 */
    private final ProjectInstructionLoader instructionLoader = new ProjectInstructionLoader();
    private String lastInstructionStatus;
    private final ContextBuilder contexts = new ContextBuilder(this::projectRules, this::selectMemories);
    private final MemoryService memory;
    private String lastMemoryError;
    /** 全局默认值在启动时注入；模型切换后重新解析单模型覆盖值。 */
    private final ContextBudgetSettings budgetSettings;
    private ContextBudgetPolicy budgetPolicy = ContextBudgetPolicy.unknown();
    private final CompactionSettings compactionSettings;

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
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, debug, List::of);
    }

    /** 完整装配入口额外提供模型目录，偏好从 settings 的用户数据目录持久化。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           BufferedReader input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog) {
        this(startup, settings, store, models, basePrompt, new ConsoleInput(input, output), output, errors,
                options, tools, debug, modelCatalog);
    }

    /** 终端输入实现支持补全；原 BufferedReader 构造入口保留，便于管道与测试。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           ConsoleInput input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, debug,
                modelCatalog, AgentLoop.DEFAULT_MAX_STEPS);
    }

    /** 生产入口注入 Agent 预算；旧构造入口保留，测试也可显式提供较小预算。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           ConsoleInput input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog,
                           int maxIterations) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, debug,
                modelCatalog, maxIterations, new PermissionSettings());
    }

    /** 权限生产装配入口；旧测试/库构造默认 ask，且不接受管道中的隐式批准。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           ConsoleInput input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog,
                           int maxIterations, PermissionSettings permissionSettings) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, debug,
                modelCatalog, maxIterations, permissionSettings, new ContextBudgetSettings());
    }

    /** 完整生产装配入口，配置读取仍在 Main；预览和实际请求共用已选模型策略。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           ConsoleInput input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog,
                           int maxIterations, PermissionSettings permissionSettings, ContextBudgetSettings budgetSettings) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, debug, modelCatalog,
                maxIterations, permissionSettings, budgetSettings, new CompactionSettings());
    }

    /** 摘要设置在宿主启动时注入；恢复后的摘要不需要额外模型请求即可展示。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           ConsoleInput input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog,
                           int maxIterations, PermissionSettings permissionSettings, ContextBudgetSettings budgetSettings,
                           CompactionSettings compactionSettings) {
        this(startup, settings, store, models, basePrompt, input, output, errors, options, tools, debug,
                modelCatalog, maxIterations, permissionSettings, budgetSettings, compactionSettings, new MemorySettings());
    }

    /** 长期记忆与会话共用用户数据目录，配置由生产宿主显式注入。 */
    public SessionLauncher(Path startup, SessionSettings settings, SessionStore store,
                           Function<String, ModelChoice> models, Supplier<String> basePrompt,
                           ConsoleInput input, PrintWriter output, PrintWriter errors, Options options,
                           ToolRegistry tools, DebugPrinter debug, Supplier<List<ModelProfiles.ProfileInfo>> modelCatalog,
                           int maxIterations, PermissionSettings permissionSettings, ContextBudgetSettings budgetSettings,
                           CompactionSettings compactionSettings, MemorySettings memorySettings) {
        this.memory = new MemoryService(new JsonMemoryStore(settings.dataDirectory()), memorySettings);
        if (maxIterations < 1 || maxIterations > AgentLoop.MAX_STEPS) throw new IllegalArgumentException("Agent steps must be within 1..100.");
        this.maxIterations = maxIterations;
        this.budgetSettings = java.util.Objects.requireNonNull(budgetSettings, "budgetSettings");
        this.compactionSettings = java.util.Objects.requireNonNull(compactionSettings, "compactionSettings");
        this.startup = startup.toAbsolutePath().normalize();
        this.settings = settings;
        this.store = store;
        this.models = models;
        this.modelCatalog = java.util.Objects.requireNonNull(modelCatalog, "modelCatalog");
        this.modelPreferences = new ModelPreferenceStore(settings.dataDirectory());
        this.startupProfile = options.profile();
        this.basePrompt = basePrompt;
        this.input = input;
        this.output = output;
        this.errors = errors;
        this.options = options;
        this.tools = java.util.Objects.requireNonNull(tools, "tools");
        this.debug = java.util.Objects.requireNonNull(debug, "debug");
        java.util.Objects.requireNonNull(permissionSettings);
        this.approvalHandler = new ConsoleApprovalHandler(input, output, permissionSettings.pipedApprovals());
        this.permissionMode = permissionSettings.mode();
        input.status(runtimeStatus);
        updateModes();
    }

    /** EOF 或 /exit 结束；错误保存会话返回 1，参数配置错误由 Main 分类。 */
    public int run() throws IOException {
        try {
            if (permissionMode == PermissionMode.FULL && !confirmFull()) {
                permissionMode = PermissionMode.ASK;
                output.println("full 未启用，当前保持 ask；无交互终端时可显式配置管道审批。");
                updateModes();
            }
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
                    } else if ("/help".equals(command)) {
                        printCommands();
                    } else if (command.equals("/permissions") || command.startsWith("/permissions ")) {
                        changePermissions(command.substring(12).strip());
                    } else if (command.equals("/mode") || command.startsWith("/mode ")) {
                        changeWorkMode(command.substring(5).strip());
                    } else if (command.equals("/status")) {
                        printStatus();
                    } else if (command.equals("/context")) {
                        printContext();
                    } else if (command.equals("/remember") || command.startsWith("/remember ")) {
                        memoryCommands().remember(command.substring(9).strip());
                    } else if (command.equals("/memory") || command.startsWith("/memory ")) {
                        memoryCommands().list(command.substring(7).strip());
                    } else if (command.equals("/forget") || command.startsWith("/forget ")) {
                        memoryCommands().forget(command.substring(7).strip());
                    } else if (command.equals("/init")) {
                        initializeProject();
                    } else if (command.equals("/compact")) {
                        compact();
                    } else if (command.matches("/list\\s+model")) {
                        listModels();
                    } else if ("/model".equals(command)) {
                        output.println("当前模型：" + (model == null ? "未选择" : model.profile())
                                + "；用法：/model 供应商 [模型ID]，/list model 查看列表。");
                    } else if (command.matches("/model\\s+.*")) {
                        switchModel(unquote(command.substring(6).strip()));
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
                        output.println("未知命令，输入 /help 查看全部命令。");
                    } else if (browsing) {
                        resume(command, true);
                    } else {
                        answer(line);
                    }
                } catch (ConfigException failed) {
                    input.theme().line(errors, ConsoleTheme.Tone.ERROR, "Configuration error: " + failed.getMessage());
                } catch (MemoryException failed) {
                    input.theme().line(errors, ConsoleTheme.Tone.ERROR, "Memory error: " + failed.getMessage());
                } catch (SessionException | IllegalArgumentException failed) {
                    input.theme().line(errors, ConsoleTheme.Tone.ERROR, (active != null && active.dirty() ? "未保存，结果仍在内存：" : "Session error: ")
                            + failed.getMessage());
                } catch (LlmException failed) {
                    input.theme().line(errors, ConsoleTheme.Tone.ERROR, "LLM error [" + failed.kind() + "]: " + failed.getMessage()
                            + " This turn was not saved.");
                    if (active != null && active.snapshot().workspace().mode() == WorkspaceBinding.Mode.DIRECTORY) {
                        input.theme().line(errors, ConsoleTheme.Tone.ERROR, "工具已执行的文件修改不会自动回滚；重试前请检查当前文件。");
                    }
                    if (failed.kind() == LlmException.Kind.INTERRUPTED) return 1;
                }
                output.flush();
                errors.flush();
            }
            return savePending() ? 0 : 1;
        } finally {
            if (permissions != null) permissions.revoke();
            input.status(null);
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
        output.println("输入序号、完整标题或 ID；/new 新建，/more_session 下一批，/sessions 刷新，/help 查看命令，exit 或 /exit 退出。");
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
                catch (IllegalArgumentException invalid) { input.theme().line(errors, ConsoleTheme.Tone.ERROR, invalid.getMessage()); }
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

    /** 首次 --profile 优先，然后最后使用、会话保存别名、配置默认；无效时要求明确选择。 */
    private ModelChoice chooseModel(String savedProfile) throws IOException {
        String last = lastProfile();
        String profile = startupProfile != null ? startupProfile : last != null ? last : savedProfile;
        while (!exiting) {
            try {
                ModelChoice selected = models.apply(profile);
                new ContextBudgetPolicy(selected.capabilities(), budgetSettings); // 验证后再创建/保存新会话。
                return selected;
            }
            catch (ConfigException invalid) {
                input.theme().line(errors, ConsoleTheme.Tone.ERROR, "Configuration error: " + invalid.getMessage());
                String choice = read("输入可用的供应商 [模型ID]（/back 返回）> ");
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
        budgetPolicy = new ContextBudgetPolicy(selected.capabilities(), budgetSettings);
        // 替换会话清除旧批准；full 不自动扩散到另一个会话，工作模式回到 ReAct。
        if (permissions != null && permissionMode == PermissionMode.FULL) permissionMode = PermissionMode.ASK;
        workMode = WorkMode.REACT;
        if (permissions != null) permissions.revoke();
        permissions = new PermissionService(permissionMode, active.snapshot().workspace(), approvalHandler);
        runtimeStatus.model(selected.profile(), selected.capabilities());
        updateModes();
        startupProfile = null; // 后续新建与恢复使用最近选择，不重复套用启动覆盖。
        rememberModel(selected.profile());
        browsing = false;
        output.println("会话：" + active.snapshot().title() + " | ID " + active.snapshot().id()
                + " | " + active.snapshot().workspace().display() + " | Profile " + selected.profile());
        if (!SessionSnapshot.hash(basePrompt.get()).equals(active.snapshot().promptHash())) {
            output.println("基础提示词版本已变化，本会话沿用创建时的快照。");
        }
    }

    /** 统一列出交互命令；管理页和聊天中都可查看，不向模型发送。 */
    private void printCommands() {
        input.theme().line(output, ConsoleTheme.Tone.USER, "CodeY 命令：");
        for (var command : CommandCatalog.commands()) {
            input.theme().command(output, command.usage(), command.description(), input.columns());
        }
        output.println("  exit  聊天返回列表；列表中退出程序");
        output.println("默认模型为最后一次成功选择的配置；首次没有记录时使用 .env 默认值。");
        output.println("交互终端：Tab 补全命令/供应商/模型；多个候选可用上下键选择、Enter 确认。");
    }

    /** 列表不校验远端连通性、不切换选择、不写偏好，也不展示凭证。 */
    private void listModels() {
        List<ModelProfiles.ProfileInfo> entries = modelCatalog.get();
        String last = lastProfile();
        output.println("模型目录（/model 供应商 [模型ID]；配置通过仅代表本地校验）：");
        output.println("当前会话：" + (model == null ? "未选择" : model.profile())
                + "；默认模型：" + (last == null ? "首次使用 .env 的 CODEY_ACTIVE_PROFILE" : last));
        for (var entry : entries) {
            boolean selected = false;
            if (model != null) {
                ProfileSelection current = ProfileSelection.parse(model.profile());
                selected = current.alias().equals(entry.alias()) && (current.modelId() == null
                        ? entries.stream().filter(e -> e.alias().equals(entry.alias())).findFirst().orElseThrow().equals(entry)
                        : current.modelId().equals(entry.modelId()));
            }
            output.println((selected ? "* " : "  ")
                    + entry.alias() + " | " + entry.modelId() + " | " + (entry.configured() ? "配置有效" : "配置待完善"));
        }
        if (entries.isEmpty()) output.println("暂无模型目录，请检查 CODEY_PROFILE_NAMES 配置。");
    }

    /** 验证目标客户端与保存会话属性成功后才替换模型，Session 对象与工具历史保持原样。 */
    private void switchModel(String profile) {
        if (active == null) { output.println("请先 /new 或 /resume 进入一个会话，再切换模型。"); return; }
        ModelChoice selected = models.apply(profile); // 不套用启动覆盖，也不发探测请求。
        var nextBudget = new ContextBudgetPolicy(selected.capabilities(), budgetSettings);
        active.switchProfile(selected.profile());
        model = selected;
        budgetPolicy = nextBudget;
        runtimeStatus.model(selected.profile(), selected.capabilities());
        rememberModel(selected.profile());
        output.println("已切换模型：" + selected.profile() + "；原会话和消息历史已保留。");
    }

    /** 惰性加载用户默认偏好，损坏时报告并回退至保存别名/配置默认，不影响浏览。 */
    private String lastProfile() {
        if (!preferenceLoaded) {
            preferenceLoaded = true;
            try { lastUsedProfile = modelPreferences.load().orElse(null); }
            catch (ConfigException invalid) { input.theme().line(errors, ConsoleTheme.Tone.ERROR, "模型偏好：" + invalid.getMessage()); }
        }
        return lastUsedProfile;
    }

    /** 当前运行立即记住选择；默认文件保存失败单独提示，不撤销已成功保存的会话切换。 */
    private void rememberModel(String profile) {
        lastUsedProfile = profile;
        preferenceLoaded = true;
        try { modelPreferences.save(profile); }
        catch (ConfigException failed) { input.theme().line(errors, ConsoleTheme.Tone.ERROR, "模型已选择，但默认偏好未保存：" + failed.getMessage()); }
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
    private void answer(String line) { answer(line, ""); }

    private void answer(String line, String taskInstructions) {
        ContextBuilder taskContexts = taskInstructions.isEmpty() ? contexts : contexts.withTaskInstructions(taskInstructions);
        // 模型客户端/模式在任务开始时固定，运行中不消费切换命令；状态只观察响应。
        final var selectedClient = model.chat();
        LlmClient observed = new LlmClient() {
            @Override public LlmResponse generate(LlmRequest request) {
                runtimeStatus.usage(null);
                var response = selectedClient.generate(request);
                if (response != null) runtimeStatus.usage(response.usage());
                input.refreshStatus(); return response;
            }
            @Override public LlmResponse generateStream(LlmRequest request, Consumer<String> onText) {
                runtimeStatus.usage(null); input.refreshStatus();
                var response = selectedClient.generateStream(request, onText);
                if (response != null) runtimeStatus.usage(response.usage());
                // 正文先收尾，ConsoleStreamOutput.onResponseEnd 再刷新底栏，避免管道状态插入正文中间。
                return response;
            }
        };
        ChatCli chat = new ChatCli(new AgentLoop(observed, tools, maxIterations, debug, taskContexts, this::updateContext, budgetPolicy,
                new HistoryCompactor(selectedClient, budgetPolicy, compactionSettings, taskContexts, debug), this::saveCompaction), active.session(),
                new ToolContext(active.snapshot().workspace(), ToolContext.DEFAULT_MAX_OUTPUT_CHARS, workMode, permissions), input, output, errors);
        if (chat.answer(line)) {
            active.captureTurn();
            active.save();
            generateTitle();
        }
    }

    /** 无参数选择菜单，参数直接切换；revoke 由用户撤销本会话的批准与拒绝。 */
    private void changePermissions(String value) throws IOException {
        if (value.isEmpty()) {
            output.println("当前权限：" + permissionMode.value() + "；ask 请求批准 / auto 规则审查 / full 跳过常规审批。");
            if (permissions != null) {
                for (String file : permissions.grantedFiles()) output.println("  已批准文件：" + ConsoleApprovalHandler.clean(file));
                output.println("/permissions revoke 撤销当前会话全部文件授权和拒绝记录。");
            }
            if (!input.isInteractive()) return;
            value = read("选择权限 ask/auto/full（空行保持，revoke 撤销文件授权）> ");
            if (value == null || value.isBlank()) return;
        }
        if (value.equals("revoke")) {
            if (permissions != null) permissions.revoke();
            output.println("已撤销当前会话文件授权和拒绝记录。");
            return;
        }
        var next = PermissionMode.parse(value);
        if (next == PermissionMode.FULL && permissionMode != next && !confirmFull()) {
            output.println("未启用 full，原权限保持。"); return;
        }
        if (permissionMode != next) {
            if (permissions != null) permissions.revoke();
            permissionMode = next;
            if (active != null) permissions = new PermissionService(next, active.snapshot().workspace(), approvalHandler);
        }
        updateModes();
        output.println("权限模式：" + next.value() + "（" + next.label() + "）。命令在 ask/auto 下逐次批准；full 跳过审批，工作目录不构成进程沙箱。");
    }

    /** 工作模式和权限分别切换；Multi 未实现时不更新任何状态。 */
    private void changeWorkMode(String value) throws IOException {
        if (value.isEmpty()) {
            output.println("当前工作模式：" + workMode.value() + "；multi 尚待实现。");
            if (!input.isInteractive()) return;
            value = read("选择工作模式 react/plan（空行保持）> ");
            if (value == null || value.isBlank()) return;
        }
        WorkMode next = WorkMode.parse(value);
        if (next == WorkMode.MULTI) { output.println("multi 尚待实现，工作模式保持不变。"); return; }
        workMode = next; updateModes();
        output.println("工作模式：" + next.value() + (next == WorkMode.PLAN ? "（只读探索并输出计划）" : "（执行任务）"));
    }

    private void updateModes() { runtimeStatus.modes(workMode, permissionMode); }
    private void printStatus() {
        output.println("工作模式：" + workMode.value() + "；权限：" + permissionMode.value());
        output.println("模型与用量：" + runtimeStatus.right());
        output.println("已用为当前上下文的本地估算（≈），百分比为估算输入 / 配置容量，不是累计消耗；/context 查看拆分。");
        output.println("最近实际输入：" + (runtimeStatus.lastInputTokens() == null ? "未知" : runtimeStatus.lastInputTokens() + " tok")
                + "（服务商最近一次响应的 usage；不与当前估算相加）。");
        output.println("上下文容量来源：" + (model == null ? "未配置" : model.capabilities().source())
                + "；已启用已知容量的请求前预算检查。");
        printBudget();
        printCompaction();
        output.println("文件授权 " + (permissions == null ? 0 : permissions.grantCount()) + "；拒绝记录 "
                + (permissions == null ? 0 : permissions.denialCount()) + "。/permissions revoke 可撤销。");
        output.println("文件权限限制来自内置工具路径检查，当前没有进程/网络沙箱。");
    }

    /** 输入前只估算已存消息；当前尚未提交的输入不在预览中，不触发模型或工具执行。 */
    private void previewContext() {
        if (active == null) return;
        var toolContext = new ToolContext(active.snapshot().workspace(), ToolContext.DEFAULT_MAX_OUTPUT_CHARS, workMode, permissions);
        updateContext(contexts.build(active.session().messages(), List.of(), tools.definitions(toolContext), workMode, 1, maxIterations, active.session().compaction()));
    }

    /** 同步估算和预算；请求展示交给现有模型调用/输入刷新位置，避免重复刷状态。 */
    private void updateContext(ContextSnapshot snapshot) {
        runtimeStatus.context(snapshot);
        runtimeStatus.budget(budgetPolicy.evaluate(snapshot));
    }

    /** 总窗口占比与输入预算占比分开；未知不填零，超限显示实际负的剩余空间。 */
    private void printBudget() {
        var value = runtimeStatus.budget();
        if (value == null) { output.println("输入预算：尚未选择会话/模型。"); return; }
        output.println("  输入预算：" + (value.inputBudget() == null ? "?" : value.inputBudget()) + " tok（"
                + RuntimeStatus.formatTokens(value.inputBudget()) + "；窗口 - 输出预留 - 安全余量）");
        output.println("  输出预留：" + value.outputReserveTokens() + " tok；安全余量：" + value.safetyMarginTokens() + " tok");
        output.println("  预警比例：" + budgetSettings.warningRatio().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                + "% 输入预算；预警水位：" + (value.warningThreshold() == null ? "?" : value.warningThreshold()) + " tok");
        String percent = value.inputBudget() == null ? "?%" : java.math.BigDecimal.valueOf(value.estimatedInput())
                .multiply(java.math.BigDecimal.valueOf(100)).divide(java.math.BigDecimal.valueOf(value.inputBudget()), 1,
                        java.math.RoundingMode.HALF_UP).toPlainString() + "%";
        output.println("  输入预算占用：" + (value.inputBudget() == null ? "" : "≈") + percent + "；剩余："
                + (value.remainingTokens() == null ? "?" : value.remainingTokens()) + " tok；" + value.label());
        output.println("  输出预留为本地预算参数；普通任务不自动添加 max_tokens，摘要请求单独限制输出。估算与服务端分词可能不同。");
    }

    /** 只打印分类计数，避免把历史正文、文件内容和提示词整段刷到控制台。 */
    private void printContext() {
        if (active == null) { output.println("请先选择或新建会话，再用 /context 查看上下文。"); return; }
        previewContext();
        var snapshot = runtimeStatus.context();
        var tokens = snapshot.tokens();
        output.println("上下文预览（尚未包含下一条用户输入），估算方法：" + TokenEstimator.METHOD);
        output.println(instructionLoader.load(active.snapshot().workspace()).description());
        output.println("  项目规则及标记：≈" + tokens.projectInstructions() + " tok");
        output.println("  用户常驻记忆：≈" + tokens.alwaysMemory() + " tok");
        output.println("  按需召回记忆：≈" + tokens.recalledMemory() + " tok（预览无新任务，暂不检索）");
        output.println("  基础提示词：≈" + tokens.basePrompt() + " tok");
        output.println("  运行时模式/轮次指令：≈" + tokens.runtimeInstructions() + " tok");
        output.println("  成功历史（含过去工具调用与结果）：≈" + tokens.history() + " tok");
        output.println("  历史摘要及标记：≈" + tokens.summary() + " tok");
        output.println("  本次用户输入：≈" + tokens.userInput() + " tok");
        output.println("  本次模型正文/工具调用：≈" + tokens.assistant() + " tok");
        output.println("  本次工具结果：≈" + tokens.toolResults() + " tok");
        output.println("  可用工具定义（名称/描述/完整 JSON Schema，" + snapshot.request().tools().size() + " 个）：≈" + tokens.toolDefinitions() + " tok");
        output.println("  消息边界/协议开销：≈" + tokens.protocolOverhead() + " tok");
        output.println("  总计：≈" + tokens.total() + " tok；" + runtimeStatus.right());
        output.println("发送前加入当前输入，每次 ReAct 请求前重新组装；原始历史完整保存，请求可使用摘要。");
        printBudget();
        printCompaction();
        output.println("压缩后仍超限时阻止下一次模型请求；长期记忆已接入；MCP、Skill 尚未接入。");
    }

    private MemoryCommands memoryCommands() {
        return new MemoryCommands(memory, active == null ? WorkspaceBinding.none() : active.snapshot().workspace(),
                active == null ? null : active.snapshot().id(), output);
    }

    /** 预算不足时先减少低相关召回；存储错误显式提示，不修改损坏文件。 */
    private MemorySelection selectMemories(String query, Long used) {
        try {
            Long limit = budgetPolicy.evaluate(used).inputBudget();
            var selected = memory.select(active == null ? WorkspaceBinding.none() : active.snapshot().workspace(), query,
                    limit == null ? Long.MAX_VALUE : Math.max(0, limit - used));
            lastMemoryError = null;
            return selected;
        } catch (MemoryException failed) {
            if (!failed.getMessage().equals(lastMemoryError)) {
                input.theme().line(errors, ConsoleTheme.Tone.ERROR, "记忆未加载：" + failed.getMessage());
                errors.flush(); lastMemoryError = failed.getMessage();
            }
            return MemorySelection.EMPTY;
        }
    }

    /** 每次请求与预览都重新检查小文件；状态变化才提示，避免每轮刷屏。无效规则不截断、不沿用旧副本。 */
    private String projectRules() {
        if (active == null) return "";
        var rules = instructionLoader.load(active.snapshot().workspace());
        String status = rules.description();
        if (!status.equals(lastInstructionStatus)) {
            output.println(status);
            output.flush();
            lastInstructionStatus = status;
        }
        return rules.requestText();
    }

    /** 显式初始化，写入继续走当前模式和逐文件审批；随后读磁盘确认实际结果。 */
    private void initializeProject() {
        if (active == null || browsing) {
            output.println("请先进入一个项目会话，再执行 /init。");
            return;
        }
        var initializer = new ProjectInitializer();
        String task = initializer.task(active.snapshot().workspace());
        answer(task, initializer.instructions());
        output.println("初始化检查：" + instructionLoader.load(active.snapshot().workspace()).description());
        previewContext();
    }

    private void saveCompaction() { active.captureCompaction(); active.save(); }

    /** 手动摘要不新增用户轮次，也不触发标题生成；失败只报告原因，旧摘要继续有效。 */
    private void compact() {
        if (active == null) { output.println("请先选择或新建会话。"); return; }
        var context = new ToolContext(active.snapshot().workspace(), ToolContext.DEFAULT_MAX_OUTPUT_CHARS, workMode, permissions);
        var outcome = new HistoryCompactor(model.chat(), budgetPolicy, compactionSettings, contexts, debug)
                .prepare(active.session(), List.of(), tools.definitions(context), workMode, 1, maxIterations, true);
        if (outcome.changed()) saveCompaction();
        updateContext(outcome.context());
        output.println("手动压缩：" + outcome.reason() + "；摘要调用 " + outcome.summaryCalls() + " 次。");
    }

    private void printCompaction() {
        output.println("  自动压缩：" + (compactionSettings.enabled() ? "开启" : "关闭") + "；触发/期望上限："
                + compactionSettings.triggerRatio().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%/"
                + compactionSettings.targetRatio().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                + "% 输入预算（必要时额外容许 5 个百分点）；优先保留 " + compactionSettings.keepRecentTurns() + " 个完整用户轮次。");
        output.println("  摘要额度：" + (compactionSettings.summaryMaxTokens() == 0 ? "动态剩余空间，无最低长度" : "动态空间内额外限制 " + compactionSettings.summaryMaxTokens() + " tok")
                + "；模型单次输出上限：" + (budgetPolicy.maxOutputTokens() == null ? "未知（可配置 models.json maxOutputTokens）" : budgetPolicy.maxOutputTokens() + " tok") + "。");
        var state = active == null ? null : active.session().compaction();
        output.println("  策略：来源≤" + compactionSettings.singleMaxSourceTokens() + " tok 且单次可容纳时 Single；否则 MapReduce；块目标 "
                + compactionSettings.chunkTokens() + " tok；并发≤" + compactionSettings.concurrency() + "；总调用≤" + compactionSettings.maxCalls()
                + "（含重试和 Reduce）；Map 最多重试 3 次。");
        output.println("  当前摘要：" + (state == null ? "尚无" : "覆盖原始历史前 " + state.coveredMessages() + " 条消息")
                + "；/compact 手动压缩；原始消息保留。");
    }
    private boolean confirmFull() {
        try { return approvalHandler.confirmFull(); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); return false; }
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
        if (settings.generateTitles() && !result.generated()) input.theme().line(errors, ConsoleTheme.Tone.ERROR, "标题生成未完成，已保留首问摘要。");
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
            input.theme().line(errors, ConsoleTheme.Tone.ERROR, "未保存：" + failed.getMessage() + "；结果仍在内存，下一次输入将重试保存。");
            errors.flush();
            return false;
        }
    }

    /** 显示输入提示并处理所有输入阶段的 EOF 与 /exit。 */
    private String read(String prompt) throws IOException {
        previewContext();
        output.flush();
        errors.flush();
        String line = input.readLine(prompt);
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
