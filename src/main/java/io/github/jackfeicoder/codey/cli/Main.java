package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ConfigLoader;
import io.github.jackfeicoder.codey.config.LlmConfig;
import io.github.jackfeicoder.codey.config.ModelProfiles;

import java.nio.file.Path;

/** CLI entry point. Agent capabilities are implemented in later milestones. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("CodeY 0.1.0-SNAPSHOT");
            return;
        }
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            printHelp();
            return;
        }
        String action = null;
        String profile = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if ("--check-config".equals(argument) || "--list-models".equals(argument)) {
                if (action != null) {
                    usageError();
                    return;
                }
                action = argument;
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
            ModelProfiles profiles = ConfigLoader.loadProfiles(Path.of(""), System.getenv(), profile);
            if ("--list-models".equals(action)) {
                System.out.println("Configured profiles (* = selected; credentials are validated on use):");
                for (String name : profiles.profileNames()) {
                    System.out.println((name.equals(profiles.activeProfile()) ? "* " : "  ") + name);
                }
            } else {
                LlmConfig config = profiles.activeConfig();
                System.out.println("LLM configuration is valid. Profile: " + profiles.activeProfile());
                System.out.println(config);
            }
        } catch (ConfigException invalid) {
            System.err.println("Configuration error: " + invalid.getMessage());
            System.exit(2);
        }
    }

    private static void usageError() {
        System.err.println("Invalid arguments. Use --help. --profile requires --check-config or --list-models.");
        System.exit(2);
    }

    private static void printHelp() {
        System.out.println("""
                CodeY - A CLI coding agent built from scratch in Java.

                Status: model profiles are available. LLM and Agent features are not implemented yet.

                Usage:
                  java -jar target/codey-0.1.0-SNAPSHOT.jar [--help | --version]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --list-models [--profile ALIAS]
                  java -jar target/codey-0.1.0-SNAPSHOT.jar --check-config [--profile ALIAS]

                Configuration:
                  Process environment > current-working-directory .env > defaults.
                  --check-config validates settings without calling a model.
                  --profile overrides CODEY_ACTIVE_PROFILE for this invocation.
                  /model will be added with the interactive conversation loop.

                Start learning:
                  README.md
                  docs/architecture.md
                """);
    }
}
