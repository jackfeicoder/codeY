package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ConfigLoader;
import io.github.jackfeicoder.codey.config.LlmConfig;
import io.github.jackfeicoder.codey.config.ModelProfiles;
import io.github.jackfeicoder.codey.config.ModelPreferenceStore;
import io.github.jackfeicoder.codey.config.SessionSettings;
import io.github.jackfeicoder.codey.config.AgentSettings;
import io.github.jackfeicoder.codey.llm.ChatCompletionsClient;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.prompt.PromptException;
import io.github.jackfeicoder.codey.prompt.PromptLoader;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.session.JsonSessionStore;
import io.github.jackfeicoder.codey.session.SessionException;
import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.tool.builtin.ReadFileTool;
import io.github.jackfeicoder.codey.tool.builtin.ListFilesTool;
import io.github.jackfeicoder.codey.tool.builtin.SearchFileTool;
import io.github.jackfeicoder.codey.tool.builtin.GrepCodeTool;
import io.github.jackfeicoder.codey.tool.builtin.WriteFileTool;
import io.github.jackfeicoder.codey.tool.builtin.EditFileTool;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 命令行入口与组件装配：解析参数 → 选择 Profile → 加载提示词 → 调用模型 → 展示结果。
 * 无参数或 --chat 打开持久化会话入口，--prompt 执行一条普通聊天任务；循环交给 AgentLoop。
 */
public final class Main {
    /** 入口类通过静态 main 启动，无需创建实例。 */
    private Main() {
    }

    /** args 是命令行参数；帮助/版本不依赖配置，其余操作按选择加载配置。 */
    public static void main(String[] args) {
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("CodeY 0.1.0-SNAPSHOT");
            return;
        }
        if (args.length == 1 && "--help".equals(args[0])) {
            printHelp();
            return;
        }
        String action = args.length == 0 ? "--chat" : null; // 无参数打开历史选择器。
        String profile = null; // --profile 指定的别名；null 表示采用配置中的默认选择。
        String prompt = null; // --prompt 后的一段用户输入，含空格时由命令行引号包围。
        String workspace = null; // 显式路径只用于新建会话，不改变 .env 来源。
        String title = null; // 可选手动标题，优先于自动生成。
        String resume = null; // 完整标题或 ID，交由会话选择器解析。
        boolean noWorkspace = false; // 明确指定普通聊天，与 --workspace 互斥。
        for (int index = 0; index < args.length; index++) {
            String argument = args[index]; // 当前参数；带值的选项会额外消费下一个参数。
            if ("--check-config".equals(argument) || "--list-models".equals(argument)
                    || "--chat".equals(argument)) {
                if (action != null) {
                    usageError();
                    return;
                }
                action = argument;
            } else if ("--prompt".equals(argument) && action == null
                    && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                action = argument;
                prompt = args[++index];
                if (prompt.isBlank()) {
                    usageError();
                    return;
                }
            } else if ("--profile".equals(argument) && profile == null
                    && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                profile = args[++index];
            } else if ("--workspace".equals(argument) && workspace == null
                    && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                workspace = args[++index];
            } else if ("--title".equals(argument) && title == null
                    && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                title = args[++index];
            } else if ("--resume".equals(argument) && resume == null
                    && index + 1 < args.length && !args[index + 1].startsWith("--")) {
                resume = args[++index];
            } else if ("--no-workspace".equals(argument) && !noWorkspace) {
                noWorkspace = true;
            } else {
                usageError();
                return;
            }
        }
        if (action == null || (workspace != null && noWorkspace)
                || (resume != null && (workspace != null || noWorkspace || title != null))
                || (!"--chat".equals(action) && (workspace != null || noWorkspace || title != null || resume != null))
                || (workspace != null && workspace.isBlank()) || (resume != null && resume.isBlank())
                || (title != null && !io.github.jackfeicoder.codey.session.SessionSnapshot.validTitle(title))) {
            usageError();
            return;
        }
        try {
            if ("--chat".equals(action)) {
                int status = startChat(new SessionLauncher.Options(workspace, noWorkspace, title, resume, profile));
                if (status != 0) System.exit(status);
                return;
            }
            // profiles 是启动时的配置快照，命令行别名覆盖默认选择。
            Path startup = Path.of("").toAbsolutePath().normalize();
            Map<String, String> environment = System.getenv();
            var preferences = new ModelPreferenceStore(SessionSettings.load(startup, environment).dataDirectory());
            String preferred = profile;
            if (preferred == null) {
                try { preferred = preferences.load().orElse(null); }
                catch (ConfigException invalid) { System.err.println("模型偏好：" + invalid.getMessage()); }
            }
            ModelProfiles profiles = ConfigLoader.loadProfiles(startup, environment, preferred);
            if ("--list-models".equals(action)) {
                System.out.println("Configured models (Profile | model ID | local configuration only):");
                for (var entry : profiles.listInfo()) {
                    System.out.println(entry.alias() + " | " + entry.modelId() + " | "
                            + (entry.configured() ? "配置有效" : "配置待完善"));
                }
            } else if ("--check-config".equals(action)) {
                AgentSettings agentSettings = AgentSettings.load(startup, environment);
                LlmConfig config = profiles.activeConfig(); // 只做本地字段校验，不发送 HTTP 请求。
                io.github.jackfeicoder.codey.config.PermissionSettings.load(startup, environment);
                System.out.println("LLM configuration is valid. Profile: " + profiles.activeSelection());
                System.out.println(config);
                System.out.println(agentSettings);
            } else {
                AgentSettings agentSettings = AgentSettings.load(startup, environment);
                // client 通过统一接口使用，实际 HTTP/JSON 逻辑位于 ChatCompletionsClient。
                Console console = System.console();
                PrintWriter debugOutput = console == null ? new PrintWriter(System.out, true, StandardCharsets.UTF_8)
                        : console.writer();
                var debug = new DebugPrinter(debugOutput); // 仅显示轮次、工具与文件摘要。
                LlmClient client = new ChatCompletionsClient(profiles.activeConfig());
                // basePrompt 是内置 Markdown 原文，与模型厂商和启动目录无关。
                String basePrompt = PromptLoader.loadBase();
                // --prompt 没有工作区，read_file 不会列入可用定义；循环仍由同一 Agent 处理。
                var result = new AgentLoop(client, createTools(agentSettings), agentSettings.maxIterations(), debug).run(new Session(basePrompt), prompt,
                        new ToolContext(WorkspaceBinding.none()));
                if (!result.completed()) {
                    System.err.println("Agent stopped [BUDGET_EXHAUSTED].");
                    System.exit(1);
                    return;
                }
                var response = result.response();
                System.out.println(response.text());
                if (result.budgetLimited()) System.err.println("本轮已到模型请求上限，回答基于当前已获得的信息。");
                try { preferences.save(profiles.activeSelection()); }
                catch (ConfigException failed) { System.err.println("默认模型偏好未保存：" + failed.getMessage()); }
                if (response.isTruncated()) {
                    System.err.println("Model output was truncated (finish_reason=length).");
                }
            }
        } catch (ConfigException invalid) {
            // 配置错误需要用户修正，退出码 2 与调用失败区分。
            System.err.println("Configuration error: " + invalid.getMessage());
            System.exit(2);
        } catch (PromptException failed) {
            // 资源加载失败时请求尚未发送，展示资源路径供排查打包问题。
            System.err.println("Prompt error: " + failed.getMessage());
            System.exit(1);
        } catch (LlmException failed) {
            // 调用错误展示已脱敏的类别与说明，退出码 1。
            System.err.println("LLM error [" + failed.kind() + "]: " + failed.getMessage());
            System.exit(1);
        } catch (SessionException failed) {
            System.err.println("Session error: " + failed.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException invalid) {
            System.err.println("Invalid session options: " + invalid.getMessage());
            System.exit(2);
        } catch (IOException failed) {
            // 不回显输入文本或底层异常，只报告输入流读取失败。
            System.err.println("Chat input error: failed to read input.");
            System.exit(1);
        }
    }

    /** 终端使用原生 Console 编码，重定向输入输出使用 UTF-8；输入流由进程持有。 */
    private static int startChat(SessionLauncher.Options options) throws IOException {
        Path startup = Path.of("").toAbsolutePath().normalize(); // 配置查找始终使用启动目录。
        Map<String, String> environment = System.getenv(); // 凭证只传给配置加载器，不写入快照。
        SessionSettings settings = SessionSettings.load(startup, environment);
        AgentSettings agentSettings = AgentSettings.load(startup, environment);
        var permissionSettings = io.github.jackfeicoder.codey.config.PermissionSettings.load(startup, environment);
        // Console 可正确处理 Windows 终端字符编码；管道和测试进程没有 Console。
        Console console = System.console();
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        PrintWriter errors = new PrintWriter(System.err, true,
                console == null ? StandardCharsets.UTF_8 : console.charset());
        try (ConsoleInput consoleInput = ConsoleInput.open(input, output,
                () -> ConfigLoader.loadModelCatalog(startup, environment))) {
            var debug = consoleInput.progressPrinter(output); // 原生终端统一进度样式，管道保留简洁文本。
            return new SessionLauncher(startup, settings, new JsonSessionStore(settings.dataDirectory()), name -> {
                // 历史浏览不执行此工厂；准备继续会话时才校验目标 Profile。
                ModelProfiles profiles = ConfigLoader.loadProfiles(startup, environment, name);
                LlmConfig config = profiles.activeConfig();
                Duration timeout = config.timeout().compareTo(Duration.ofSeconds(5)) < 0
                        ? config.timeout() : Duration.ofSeconds(5);
                LlmConfig titleConfig = new LlmConfig(config.baseUrl(), config.apiKey(), config.model(), timeout, config.authMode());
                return new SessionLauncher.ModelChoice(profiles.activeSelection(), new ChatCompletionsClient(config),
                        new ChatCompletionsClient(titleConfig));
            }, PromptLoader::loadBase, consoleInput, output, errors, options, createTools(agentSettings), debug,
                    () -> ConfigLoader.loadModelCatalog(startup, environment), agentSettings.maxIterations(), permissionSettings).run();
        }
    }

    /** 统一注册本阶段工具；是否向模型提供，由 Registry 按会话上下文筛选。 */
    private static ToolRegistry createTools(AgentSettings settings) {
        return new ToolRegistry().register(new ReadFileTool())
                .register(new ListFilesTool(settings.listFilesDefaultDepth())).register(new SearchFileTool())
                .register(new GrepCodeTool()).register(new WriteFileTool()).register(new EditFileTool());
    }

    /** 统一处理非法参数，不回显可能包含敏感内容的用户输入。 */
    private static void usageError() {
        System.err.println("Invalid arguments. Use --help. --profile requires --check-config, --list-models, --prompt or --chat.");
        System.exit(2);
    }

    /** 展示当前支持的命令、配置优先级和学习文档入口。 */
    private static void printHelp() {
        System.out.println("""
                CodeY - A CLI coding agent built from scratch in Java.

                Status: model profiles, persistent conversations and six file tools in Agent loops are available.

                Usage:
                  java -jar target/codey-0.1.0-SNAPSHOT.jar
                  java -jar target/codey-0.1.0-SNAPSHOT.jar [--help | --version]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --list-models [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --prompt "YOUR QUESTION" [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --chat [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --workspace PATH [--title TITLE]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --no-workspace [--title TITLE]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --chat --resume ID_OR_TITLE [--profile ALIAS]

                Configuration:
                  Process environment > CODEY_CONFIG_DIR .env (or startup-directory .env) > defaults.
                  --check-config validates settings without calling a model.
                  --prompt runs one non-streaming chat task without a workspace.
                  No arguments or --chat lists the latest 10 sessions across all workspaces.
                  /new creates; /more_session loads the next 10; /resume TITLE_OR_ID restores.
                  /sessions refreshes; /rename TITLE renames; /back returns to the active chat.
                  /help lists commands; /list model lists profile aliases and model IDs.
                  /model ALIAS [MODEL_ID] switches the active model while preserving history.
                  Each CODEY_PROFILE_*_MODEL accepts comma-separated model IDs; the first is the fallback.
                  Interactive terminals support Tab completion and arrow-key candidate selection.
                  Use --profile "ALIAS MODEL_ID" to select a specific model at startup.
                  /clear saves the old chat and creates a new one; /exit saves and exits.
                  In chat, exit returns to session management; in the session list, exit quits.
                  CODEY_DATA_DIR defaults to user.home/.codey; CODEY_AUTO_TITLE defaults to true.
                  Automatic titles use a separate model request with at most a 5-second timeout.
                  The built-in prompts/base.md is sent as the system message.
                  Initial selection: --profile > last-used profile > saved session profile > CODEY_ACTIVE_PROFILE.
                  Successful selections persist in CODEY_DATA_DIR/model-preference.json, without credentials.
                  Workspace chats expose list_files, search_file, grep_code, read_file, write_file and edit_file.
                  CODEY_MAX_ITERATIONS defaults to 20 (range 1..100).
                  The final request disables tools and asks for an evidence-based answer; budget hints are not saved.
                  CODEY_LIST_FILES_DEFAULT_DEPTH defaults to 10 (range 1..20); Agent settings load on startup.
                  Completed assistant/tool groups are saved; failed or exhausted tasks are not committed.
                  Progress shows numbered model requests, called tools and involved files.
                  Request/response bodies, prompts, history and tool output are not printed as debug logs.
                  list_files explores directory trees; search_file finds file paths by name or glob without reading content.
                  grep_code searches literal text in a directory or single UTF-8 file and returns matching lines.
                  write_file creates or explicitly overwrites full UTF-8 text; edit_file replaces exact text fragments.
                  Defaults: react + ask. First write to each file requires approval for that session.
                  /permissions ask|auto|full|revoke; /mode react|plan; /status shows permissions and last input tokens.
                  auto uses conservative local rules; full skips approval but does not expand tool or workspace capabilities.
                  Piped approvals require CODEY_ALLOW_PIPED_APPROVALS=true; otherwise pending writes fail closed.
                  Command execution, deletion, network tools and multi-agent execution will follow.

                Start learning:
                  README.md
                  docs/architecture.md
                """);
    }
}
