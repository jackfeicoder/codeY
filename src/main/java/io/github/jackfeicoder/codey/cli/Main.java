package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ConfigLoader;
import io.github.jackfeicoder.codey.config.LlmConfig;
import io.github.jackfeicoder.codey.config.ModelProfiles;
import io.github.jackfeicoder.codey.llm.ChatCompletionsClient;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.session.Message;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令行入口与组件装配：解析参数 → 选择 Profile → 校验配置或调用模型 → 展示结果。
 * 当前每次 --prompt 只发送一次请求，尚未进入持续对话或 Agent 工具循环。
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
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            printHelp();
            return;
        }
        String action = null; // 本次操作：列出配置、校验配置或发送问题，三者互斥。
        String profile = null; // --profile 指定的别名；null 表示采用配置中的默认选择。
        String prompt = null; // --prompt 后的一段用户输入，含空格时由命令行引号包围。
        for (int index = 0; index < args.length; index++) {
            String argument = args[index]; // 当前参数；带值的选项会额外消费下一个参数。
            if ("--check-config".equals(argument) || "--list-models".equals(argument)) {
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
            } else {
                usageError();
                return;
            }
        }
        if (action == null) {
            usageError();
            return;
        }
        try {
            // profiles 是启动时的配置快照，命令行别名覆盖默认选择。
            ModelProfiles profiles = ConfigLoader.loadProfiles(Path.of(""), System.getenv(), profile);
            if ("--list-models".equals(action)) {
                System.out.println("Configured profiles (* = selected; credentials are validated on use):");
                for (String name : profiles.profileNames()) {
                    // name 是列表中的别名，星号标识当前选择，列表操作不校验凭证。
                    System.out.println((name.equals(profiles.activeProfile()) ? "* " : "  ") + name);
                }
            } else if ("--check-config".equals(action)) {
                LlmConfig config = profiles.activeConfig(); // 只做本地字段校验，不发送 HTTP 请求。
                System.out.println("LLM configuration is valid. Profile: " + profiles.activeProfile());
                System.out.println(config);
            } else {
                // client 通过统一接口使用，实际 HTTP/JSON 逻辑位于 ChatCompletionsClient。
                LlmClient client = new ChatCompletionsClient(profiles.activeConfig());
                // response 保存完整回答和元数据；本次请求仅包含系统指令与当前问题。
                LlmResponse response = client.generate(new LlmRequest(List.of(
                        Message.system("You are CodeY, a helpful coding assistant."), Message.user(prompt))));
                System.out.println(response.text());
                if (!response.toolCalls().isEmpty()) {
                    // 当前只识别工具请求，真实执行和结果回填由后续 Agent 层负责。
                    System.out.println("Model returned tool calls. Tool execution is not implemented yet.");
                }
                if (response.isTruncated()) {
                    System.err.println("Model output was truncated (finish_reason=length).");
                }
            }
        } catch (ConfigException invalid) {
            // 配置错误需要用户修正，退出码 2 与调用失败区分。
            System.err.println("Configuration error: " + invalid.getMessage());
            System.exit(2);
        } catch (LlmException failed) {
            // 调用错误展示已脱敏的类别与说明，退出码 1。
            System.err.println("LLM error [" + failed.kind() + "]: " + failed.getMessage());
            System.exit(1);
        }
    }

    /** 统一处理非法参数，不回显可能包含敏感内容的用户输入。 */
    private static void usageError() {
        System.err.println("Invalid arguments. Use --help. --profile requires --check-config, --list-models or --prompt.");
        System.exit(2);
    }

    /** 展示当前支持的命令、配置优先级和学习文档入口。 */
    private static void printHelp() {
        System.out.println("""
                CodeY - A CLI coding agent built from scratch in Java.

                Status: model profiles and non-streaming LLM calls are available. Agent is not implemented yet.

                Usage:
                  java -jar target/codey-0.1.0-SNAPSHOT.jar [--help | --version]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --list-models [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --prompt "YOUR QUESTION" [--profile ALIAS]

                Configuration:
                  Process environment > current-working-directory .env > defaults.
                  --check-config validates settings without calling a model.
                  --prompt sends one real, non-streaming request to the selected model.
                  --profile overrides CODEY_ACTIVE_PROFILE for this invocation.
                  /model will be added with the interactive conversation loop.

                Start learning:
                  README.md
                  docs/architecture.md
                """);
    }
}
