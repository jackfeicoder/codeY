package io.github.jackfeicoder.codey.tool.process;

import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult.ErrorCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 程序定位与平台启动适配：审批前固定实际程序，不把普通参数拼成 Shell 脚本。 */
public final class CommandResolver {
    private CommandResolver() { }
    public static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"); }

    /** 捕获启动向量和程序基本身份；审批结束后检查常规替换，仍不构成进程沙箱或原子 exec。 */
    public record Resolved(Path executable, List<String> invocation, long size, FileTime modified, Object fileKey) {
        public Resolved { invocation = List.copyOf(invocation); }
        public void verify() {
            try {
                var now = Files.readAttributes(executable, BasicFileAttributes.class);
                if (!executable.toRealPath().equals(executable) || !now.isRegularFile() || now.size() != size
                        || !now.lastModifiedTime().equals(modified) || !Objects.equals(now.fileKey(), fileKey))
                    throw new ToolException(ErrorCode.FILE_CONFLICT, "Executable changed during approval; inspect it and request again.");
            } catch (IOException failed) {
                throw new ToolException(ErrorCode.COMMAND_START_FAILED, "Executable became unavailable before launch.");
            }
        }
    }

    /** 显式路径相对工作目录解析；裸程序名只搜索 PATH，跳过空项，避免隐式从项目目录找同名程序。 */
    public static Resolved resolve(String command, List<String> args, Path directory) {
        try {
            Path executable = locate(command, directory).toRealPath();
            var attributes = Files.readAttributes(executable, BasicFileAttributes.class);
            List<String> invocation = new ArrayList<>();
            String lower = executable.toString().toLowerCase(Locale.ROOT);
            if (windows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
                // cmd 的百分号展开和元字符有额外语义，首版批处理明确拒绝这类参数。
                // 普通 .exe 使用独立 argv，仍支持原样传递这些字符。
                List<String> words = new ArrayList<>(); words.add(executable.toString()); words.addAll(args);
                for (String word : words) if (word.chars().anyMatch(cp -> cp < 32 || "\"%!^&|<>()".indexOf(cp) >= 0))
                    throw new ToolException(ErrorCode.INVALID_ARGUMENTS,
                            "Windows batch arguments may not contain control characters or quote/%/!/caret/ampersand/pipe/redirection/parentheses. Use a direct executable with separate args.");
                Path system = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe");
                if (!Files.isRegularFile(system)) throw new ToolException(ErrorCode.COMMAND_NOT_FOUND, "Windows command interpreter is unavailable.");
                invocation.addAll(List.of(system.toString(), "/d", "/v:off", "/s", "/c"));
                invocation.add("\"" + words.stream().map(word -> "\"" + word + "\"")
                        .collect(java.util.stream.Collectors.joining(" ")) + "\"");
            } else {
                invocation.add(executable.toString());
                // JDK 17 Windows 的默认 legacy 模式不会转义参数内部引号，使用 CRT argv 规则编码。
                // 这是本地程序参数编码，不是 Shell 字符串拼接。
                boolean legacyWindows = windows() && !"false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true"));
                for (String arg : args) invocation.add(legacyWindows ? quoteWindowsArgument(arg) : arg);
            }
            return new Resolved(executable, invocation, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        } catch (IOException | java.nio.file.InvalidPathException failed) {
            throw new ToolException(ErrorCode.COMMAND_NOT_FOUND, "Program was not found or is not accessible. Check its path and installation.");
        }
    }

    /** Windows CRT：双引号前的反斜杠翻倍，末尾反斜杠也翻倍，保留空参数和引号本身。 */
    private static String quoteWindowsArgument(String argument) {
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (char ch : argument.toCharArray()) {
            if (ch == '\\') { slashes++; continue; }
            if (ch == '"') result.append("\\".repeat(slashes * 2 + 1)).append('"');
            else result.append("\\".repeat(slashes)).append(ch);
            slashes = 0;
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    private static Path locate(String command, Path directory) throws IOException {
        Path path = Path.of(command);
        if (path.isAbsolute() || command.indexOf('/') >= 0 || command.indexOf('\\') >= 0) {
            Path found = candidate(path.isAbsolute() ? path : directory.resolve(path));
            if (found != null) return found;
        } else {
            String environment = System.getenv().getOrDefault("PATH", "");
            for (String entry : environment.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) continue;
                if (entry.startsWith("\"") && entry.endsWith("\"")) entry = entry.substring(1, entry.length() - 1);
                try {
                    Path base = Path.of(entry);
                    if (!base.isAbsolute()) continue;
                    Path found = candidate(base.resolve(command));
                    if (found != null) return found;
                } catch (java.nio.file.InvalidPathException ignored) { }
            }
        }
        throw new ToolException(ErrorCode.COMMAND_NOT_FOUND, "Program was not found. Install it or use an explicit executable path.");
    }

    private static Path candidate(Path path) {
        // Maven 同时分发无扩展名的 Unix mvn 和 mvn.cmd；Windows 必须优先匹配可启动扩展名。
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (windows() && List.of(".exe", ".com", ".cmd", ".bat").stream().noneMatch(name::endsWith)) {
            for (String extension : List.of(".exe", ".com", ".cmd", ".bat")) {
                Path withExtension = Path.of(path + extension);
                if (Files.isRegularFile(withExtension)) return withExtension;
            }
        }
        if (Files.isRegularFile(path) && (windows() || Files.isExecutable(path))) return path;
        return null;
    }
}
