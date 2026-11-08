package io.github.jackfeicoder.codey.policy;

import io.github.jackfeicoder.codey.tool.process.CommandResolver;
import io.github.jackfeicoder.codey.tool.process.CommandRunner;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Git 状态/差异也可能运行 clean/process 过滤器；通过固定的只读 config 查询核对实际配置。 */
public final class GitQueryGuard {
    private GitQueryGuard() { }
    public static boolean noExecutableFilters(Path executable, Path cwd) throws InterruptedException {
        try {
            var config = CommandResolver.resolve(executable.toString(),List.of("--no-pager","config","--null","--get-regexp","^filter\\..*\\.(clean|process)$"),cwd);
            config.verify();
            var result = new CommandRunner().run(config.invocation(),cwd,3,4096,StandardCharsets.UTF_8,java.util.Map.of());
            // git config 的 1 表示没有匹配项，0 表示存在过滤器；其他退出/超时均保持未知。
            return Integer.valueOf(1).equals(result.exitCode()) && !result.timedOut() && !result.stdoutTruncated()
                    && !result.stderrTruncated() && !result.outputIncomplete() && !result.cleanupIncomplete();
        } catch (io.github.jackfeicoder.codey.tool.ToolException unknown) { return false; }
    }
    public static boolean needsCheck(OperationImpact impact,List<String> args) {
        return impact.kind() == OperationImpact.Kind.LOCAL_QUERY && (args.contains("status") || args.contains("diff"));
    }
}
