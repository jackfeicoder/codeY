package io.github.jackfeicoder.codey.policy;

import java.nio.file.*;
import java.util.*;
import java.io.IOException;

/** 小范围、可解释的命令规则。未识别的参数/解释器/脚本交人工，不假装静态预测任意程序。 */
public final class CommandImpactClassifier {
    private CommandImpactClassifier() { }
    public record Classified(List<String> args, OperationImpact impact) {
        public Classified { args = List.copyOf(args); }
    }
    public static Classified classify(Path executable, List<String> args, Path root, Path cwd) {
        String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean installed = !executable.startsWith(root);
        if (installed && Set.of("git", "git.exe").contains(name)) {
            if (args.equals(List.of("--version"))) return query(args, "查询已安装 Git 版本，无项目脚本。");
            if (gitQuery(args)) {
                var fixed = new ArrayList<>(List.of("--no-pager", "--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "log.showSignature=false"));
                fixed.add(args.get(0));
                if (args.get(0).equals("diff")) fixed.addAll(List.of("--no-ext-diff", "--no-textconv"));
                fixed.addAll(args.subList(1, args.size()));
                return query(fixed, "受限 Git 本地查询；禁用分页器、fsmonitor 和 diff 外部程序，未开放任意 Git 参数。");
            }
        }
        boolean maven = installed && Set.of("mvn", "mvn.cmd", "mvn.bat").contains(name);
        if (maven && (args.equals(List.of("--version")) || args.equals(List.of("-v"))))
            return new Classified(args, OperationImpact.unknown()); // Maven 启动脚本可能读取项目 .mvn 配置。
        boolean build = maven && mavenBuild(args) && Files.isRegularFile(cwd.resolve("pom.xml"));
        boolean npm = installed && Set.of("npm", "npm.cmd", "npm.bat").contains(name);
        build |= npm && (args.equals(List.of("test")) || args.equals(List.of("run", "build"))
                || args.equals(List.of("run", "test")) || args.equals(List.of("run", "lint")))
                && Files.isRegularFile(cwd.resolve("package.json"));
        if (build) {
            String snapshot = configurationFingerprint(root);
            if (snapshot != null) {
                String identity;
                try { identity = Files.size(executable)+":"+Files.getLastModifiedTime(executable)+":"+Files.readAttributes(executable,java.nio.file.attribute.BasicFileAttributes.class).fileKey(); }
                catch (IOException unknown) { return new Classified(args,OperationImpact.unknown()); }
                String key = ApprovalScope.key("build", executable.toString(), identity, args.toString(), cwd.toString(), snapshot);
                return new Classified(args, new OperationImpact(OperationImpact.Kind.PROJECT_BUILD,
                        "项目构建/测试会执行插件或脚本并可能联网；授权绑定本次流程与构建配置快照。", key, true));
            }
        }
        return new Classified(args, OperationImpact.unknown());
    }
    private static Classified query(List<String> args, String reason) {
        return new Classified(args, OperationImpact.of(OperationImpact.Kind.LOCAL_QUERY, reason));
    }
    private static boolean gitQuery(List<String> args) {
        if (args.isEmpty()) return false;
        Set<String> flags = switch (args.get(0)) {
            case "status" -> Set.of("--short", "--porcelain", "--branch", "--untracked-files=no", "--untracked-files=normal");
            case "diff" -> Set.of("--stat", "--name-only", "--name-status", "--cached", "--staged", "--check");
            case "log" -> Set.of("--oneline", "-n", "1", "5", "10", "20", "--max-count=1", "--max-count=5", "--max-count=10", "--max-count=20");
            default -> null;
        };
        if (flags == null) return false;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (!flags.contains(arg)) return false;
            if (arg.equals("-n")) { if (++i >= args.size() || !Set.of("1", "5", "10", "20").contains(args.get(i))) return false; }
            else if (arg.matches("\\d+")) return false;
        }
        return true;
    }
    private static boolean mavenBuild(List<String> args) {
        boolean goal = false;
        for (String arg : args) {
            if (Set.of("compile", "test", "verify", "package", "test-compile").contains(arg)) goal = true;
            else if (!Set.of("-q", "-B", "--batch-mode", "-DskipTests", "-DskipTests=true").contains(arg)) return false;
        }
        return goal;
    }
    /** 配置/脚本改变即使仍是相同命令也重新确认；扫描有界，超限/链接/读取失败不产生可复用授权。 */
    public static String configurationFingerprint(Path root) {
        try {
            var files = new ArrayList<Path>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                int visited;
                @Override public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(root) && Set.of(".git", "target", "build", "dist", "node_modules", ".gradle").contains(dir.getFileName().toString()))
                        return FileVisitResult.SKIP_SUBTREE;
                    if (!dir.toRealPath().equals(dir.toAbsolutePath().normalize())) throw new IOException("Redirected directory");
                    if (++visited > 10000) throw new IOException("Budget");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    if (++visited > 10000) throw new IOException("Budget");
                    if (attrs.isSymbolicLink()) throw new IOException("Redirected file");
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    String path = root.relativize(file).toString().replace('\\', '/');
                    boolean relevant = Set.of("pom.xml", "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml", ".npmrc").contains(name)
                            || path.contains(".mvn/") || name.matches(".*\\.(sh|ps1|cmd|bat|py|js|mjs|cjs)$");
                    if (relevant) {
                        if (!attrs.isRegularFile() || attrs.size() > 2_097_152 || files.size() >= 1000) throw new IOException("Budget");
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.naturalOrder());
            var fingerprints = new ArrayList<String>(); int total = 0;
            for (Path file : files) {
                byte[] bytes;
                try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(2_097_153); }
                total += bytes.length; if (bytes.length > 2_097_152 || total > 8_388_608) return null;
                fingerprints.add(ApprovalScope.key(root.relativize(file).toString(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))));
            }
            return ApprovalScope.key(fingerprints.toArray(String[]::new));
        } catch (Exception unknown) { return null; }
    }
}
