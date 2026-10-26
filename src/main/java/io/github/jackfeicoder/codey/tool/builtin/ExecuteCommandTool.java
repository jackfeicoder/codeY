package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.config.CommandSettings;
import io.github.jackfeicoder.codey.policy.ApprovalRequest;
import io.github.jackfeicoder.codey.policy.PermissionPolicy;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceFiles;
import io.github.jackfeicoder.codey.tool.process.CommandResolver;
import io.github.jackfeicoder.codey.tool.process.CommandRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 启动本地程序并返回退出码和有界日志；失败日志回填模型，由 AgentLoop 决定后续修复。 */
public final class ExecuteCommandTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> FIELDS = Set.of("command", "args", "workingDirectory", "timeoutSeconds");
    /** 参数、超时和输出预算由宿主装配，模型只在约定范围内选择。 */
    private final CommandSettings settings;
    private final ToolDefinition definition;
    private final CommandRunner runner = new CommandRunner();

    public ExecuteCommandTool() { this(new CommandSettings()); }
    public ExecuteCommandTool(CommandSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings); definition = createDefinition();
    }
    @Override public ToolDefinition definition() { return definition; }
    @Override public boolean requiresWorkspace() { return true; }
    @Override public ToolAccess access() { return ToolAccess.PROCESS; }
    @Override public void validateArguments(JsonNode arguments, ToolContext context) { options(arguments); }
    @Override public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        return prepare(arguments, context).execute(context);
    }

    /** 定位目录/程序、固定 argv、生成完整审批预览，批准后使用同一启动计划。 */
    @Override public PreparedToolOperation prepare(JsonNode arguments, ToolContext context) throws InterruptedException {
        PermissionPolicy.check(context.workMode(), access());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        Options options = options(arguments);
        var directory = WorkspaceFiles.directory(context, WorkspaceFiles.relativePath(options.directory()));
        var rootIdentity = directoryIdentity(directory.root());
        var startIdentity = directoryIdentity(directory.start());
        var executable = CommandResolver.resolve(options.command(), options.args(), directory.start());
        String preview = preview(executable, options, directory.start());
        String key = fingerprint(executable.executable(), options.args(), directory.start());
        var approval = new ApprovalRequest("execute_command", null, null, "execute_command", null, null,
                0, 0, true, true, preview, false, key);
        // 启动前确保最小结果也装得下；执行后仅缩短日志，不让执行器截断 JSON。
        encode(new CommandRunner.Result("completed", Integer.MIN_VALUE, "", "", Long.MAX_VALUE,
                false, false, false, false, false), context);
        return new PreparedToolOperation(approval, () -> {
            var current = WorkspaceFiles.directory(context, WorkspaceFiles.relativePath(options.directory()));
            if (!current.root().equals(directory.root()) || !current.start().equals(directory.start())
                    || !directoryIdentity(current.root()).equals(rootIdentity) || !directoryIdentity(current.start()).equals(startIdentity))
                throw new ToolException(ToolResult.ErrorCode.FILE_CONFLICT, "Working directory changed during approval.");
            executable.verify();
            return encode(runner.run(executable.invocation(), directory.start(), options.timeoutSeconds(),
                    settings.maxOutputChars(), settings.charset()), context);
        });
    }

    /** completed + 非零 exitCode 是执行得到的有效结果，不在工具内部重试或改文件。 */
    private static String encode(CommandRunner.Result result, ToolContext context) {
        String stdout = result.stdout(), stderr = result.stderr();
        boolean outCut = result.stdoutTruncated(), errCut = result.stderrTruncated();
        while (true) {
            ObjectNode body = MAPPER.createObjectNode().put("status", result.status());
            if (result.exitCode() == null) body.putNull("exitCode"); else body.put("exitCode", result.exitCode());
            body.put("stdout", stdout).put("stderr", stderr).put("durationMs", result.durationMs())
                    .put("timedOut", result.timedOut()).put("stdoutTruncated", outCut).put("stderrTruncated", errCut)
                    .put("outputIncomplete", result.outputIncomplete()).put("cleanupIncomplete", result.cleanupIncomplete());
            String json = body.toString();
            if (json.codePointCount(0, json.length()) <= context.maxOutputChars()) return json;
            if (stdout.isEmpty() && stderr.isEmpty()) throw new ToolException(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL,
                    "Output limit is too small for command result metadata; no process was started if this occurs during preparation.");
            if (stdout.length() >= stderr.length()) { stdout = half(stdout); outCut = true; }
            else { stderr = half(stderr); errCut = true; }
        }
    }
    private static String half(String text) {
        return text.substring(0, text.offsetByCodePoints(0, text.codePointCount(0, text.length()) / 2));
    }

    /** 每个参数独立 JSON 编码，空格、引号、换行等边界可见，且不截掉任何待审批参数。 */
    private static String preview(CommandResolver.Resolved executable, Options options, Path directory) {
        StringBuilder text = new StringBuilder("实际程序：").append(quoted(executable.executable().toString()))
                .append("\n工作目录：").append(quoted(directory.toString()))
                .append("\n超时：").append(options.timeoutSeconds()).append(" 秒；无交互标准输入");
        for (int i = 0; i < options.args().size(); i++) text.append("\nargs[").append(i).append("]：").append(quoted(options.args().get(i)));
        return text.toString();
    }
    private static String quoted(String value) {
        if (value.codePoints().anyMatch(cp -> cp <= 0xFFFF && Character.isSurrogate((char) cp)))
            throw invalid("Command text must contain valid Unicode.");
        try {
            String json = MAPPER.writeValueAsString(value);
            StringBuilder visible = new StringBuilder();
            json.codePoints().forEach(cp -> {
                if (Character.getType(cp) == Character.FORMAT || cp == 0x2028 || cp == 0x2029) {
                    for (char unit : Character.toChars(cp)) visible.append(String.format("\\u%04x", (int) unit));
                }
                else visible.appendCodePoint(cp);
            });
            json = visible.toString();
            if (json.length() > 1050) throw invalid("An argument or resolved path is too long for complete approval display.");
            return json;
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 拒绝缓存按程序真实路径 + 参数 + 工作目录区分；改超时不解除同一命令的拒绝。 */
    private static String fingerprint(Path executable, List<String> args, Path directory) {
        try {
            String canonical = MAPPER.writeValueAsString(List.of(executable.toString(), args, directory.toString()));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | IOException impossible) { throw new IllegalStateException(impossible); }
    }

    private Options options(JsonNode node) {
        if (node == null || !node.isObject() || !node.path("command").isTextual()) throw invalid("command must be a program name or executable path.");
        node.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) throw invalid("Unsupported execute_command argument."); });
        String command = node.path("command").asText();
        if (command.isBlank() || command.length() > 1024 || command.codePoints().anyMatch(cp -> Character.isISOControl(cp)))
            throw invalid("command must be nonblank, at most 1024 characters, and contain no control characters.");
        quoted(command);
        List<String> args = new ArrayList<>();
        if (node.has("args")) {
            if (!node.get("args").isArray() || node.get("args").size() > 32) throw invalid("args must be an array of at most 32 strings.");
            int total = 0;
            for (JsonNode arg : node.get("args")) {
                if (!arg.isTextual() || arg.asText().indexOf(0) >= 0 || arg.asText().length() > 1024) throw invalid("Each argument must be a string of at most 1024 characters without NUL.");
                total += quoted(arg.asText()).length();
                if (total > 8192) throw invalid("Combined argument display exceeds 8192 characters.");
                args.add(arg.asText());
            }
        }
        String directory = ".";
        if (node.has("workingDirectory")) {
            if (!node.get("workingDirectory").isTextual() || node.get("workingDirectory").asText().isBlank()) throw invalid("workingDirectory must be a nonblank relative path.");
            directory = node.get("workingDirectory").asText(); quoted(directory);
            if (directory.codePoints().anyMatch(Character::isISOControl)) throw invalid("workingDirectory may not contain control characters.");
        }
        int timeout = settings.timeoutSeconds();
        if (node.has("timeoutSeconds")) {
            if (!node.get("timeoutSeconds").isIntegralNumber() || !node.get("timeoutSeconds").canConvertToInt()) throw invalid("timeoutSeconds must be an integer.");
            timeout = node.get("timeoutSeconds").intValue();
        }
        if (timeout < 1 || timeout > settings.maxTimeoutSeconds()) throw invalid("timeoutSeconds must be within 1.." + settings.maxTimeoutSeconds() + ".");
        return new Options(command, List.copyOf(args), directory, timeout);
    }
    private record Options(String command, List<String> args, String directory, int timeoutSeconds) { }
    private record DirectoryIdentity(Object fileKey, java.nio.file.attribute.FileTime created) { }
    private static DirectoryIdentity directoryIdentity(Path directory) {
        try {
            var attributes = java.nio.file.Files.readAttributes(directory, java.nio.file.attribute.BasicFileAttributes.class);
            return new DirectoryIdentity(attributes.fileKey(), attributes.creationTime());
        } catch (IOException failed) { throw new ToolException(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, "Working directory became unavailable."); }
    }
    private static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, message); }

    private ToolDefinition createDefinition() {
        var schema = MAPPER.createObjectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("command").put("type", "string").put("minLength", 1).put("maxLength", 1024)
                .put("description", "单个程序名或程序路径，例如 mvn、git、python；参数放在 args，不自动解释 Shell 语法");
        properties.putObject("args").put("type", "array").put("maxItems", 32)
                .putObject("items").put("type", "string").put("maxLength", 1024);
        properties.putObject("workingDirectory").put("type", "string").put("default", ".")
                .put("description", "相对会话工作区的现有目录；默认工作区根目录");
        properties.putObject("timeoutSeconds").put("type", "integer").put("minimum", 1)
                .put("maximum", settings.maxTimeoutSeconds()).put("default", settings.timeoutSeconds());
        schema.putArray("required").add("command"); schema.put("additionalProperties", false);
        return new ToolDefinition("execute_command", "仅用于已确认的项目任务中确有需要的构建、测试和诊断，在本地启动程序并返回 status/exitCode/stdout/stderr/超时与截断状态。"
                + "独立单文件任务（例如单个 HTML/CSS/JS 文件）不使用本工具，修改和检查使用文件工具；用户只说测试一下也先做静态检查。"
                + "绑定工作区目录不等于项目；先依据项目结构、配置或已有脚本确认项目及验证方式，不为每次文件修改强制运行程序。"
                + "程序未找到时说明环境限制，不连续猜测 node/python/python3/powershell/git/cmd 等替代程序。"
                + "退出码 0 不代表用户任务已完成；非零需分析日志后决定下一步。"
                + "ask/auto 每次人工确认，full 跳过审批，plan 禁用。工作目录不是文件或网络沙箱；无交互输入。"
                + "Windows 自动适配 .cmd/.bat，但批处理参数不支持引号、百分号、感叹号和 Shell 元字符。", schema);
    }
}
