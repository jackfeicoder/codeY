package io.github.jackfeicoder.codey.cli;

/** Bootstrap entry point. Agent capabilities are implemented in later milestones. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("CodeY 0.1.0-SNAPSHOT");
            return;
        }
        if (args.length > 0 && !(args.length == 1 && "--help".equals(args[0]))) {
            System.err.println("Unknown arguments. Use --help to see bootstrap usage.");
            System.exit(2);
            return;
        }
        System.out.println("""
                CodeY - A CLI coding agent built from scratch in Java.

                Status: project skeleton. LLM and Agent features are not implemented yet.

                Usage:
                  java -jar target/codey-0.1.0-SNAPSHOT.jar [--help | --version]

                Start learning:
                  docs/architecture.md
                  docs/roadmap.md
                  docs/interview.md
                """);
    }
}
