package io.github.jackfeicoder.codey.tool.filesystem;

import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** 目录工具共用的路径校验与有界遍历，不注册为模型工具，也不读取文件正文。 */
public final class WorkspaceFiles {
    /** 文件、目录及忽略项共同消耗扫描预算，同时限制每层排序缓存。 */
    public static final int MAX_SCANNED_ENTRIES = 10_000;
    /** 遍历中遇到这些目录时跳过；显式选择它们作为起点时仍可查看内容。 */
    public static final List<String> IGNORED_DIRECTORY_NAMES =
            List.of(".git", "target", "node_modules", ".gradle", "build", "dist");

    private WorkspaceFiles() { }

    /** 已验证的真实工作区与搜索起点，路径不来自工具的输出。 */
    public record Directory(Path root, Path start) { }
    /** 内容搜索支持目录或单个普通文件，起点已解析到真实工作区内。 */
    public record Scope(Path root, Path start, boolean directory) { }
    /** 发现的单个条目；depth 相对起点，直接子项为第一层。 */
    public record Entry(Path path, String type, int depth) { }
    /** false 表示调用方已达到结果或输出上限，立即停止遍历。 */
    @FunctionalInterface
    public interface Visitor { boolean visit(Entry entry) throws InterruptedException; }
    /** 扫描统计与停止边界；depthLimited 表示存在尚未进入的深层目录。 */
    public record Scan(int scannedEntries, boolean scanLimited, boolean depthLimited) { }

    /** 参数路径必须是工作区相对路径；真实链接和 .. 范围在打开目录时继续检查。 */
    public static Path relativePath(String value) {
        try {
            Path path = Path.of(value);
            if (path.isAbsolute() || path.getRoot() != null) {
                throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Use a path relative to the workspace.");
            }
            return path;
        } catch (InvalidPathException invalid) {
            throw error(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Directory path has invalid syntax.");
        }
    }

    /** 统一检查 NONE、失效工作区、规范化路径和真实链接目标，不回显绝对位置。 */
    public static Directory directory(ToolContext context, Path path) {
        Path start = resolve(context, path, ToolResult.ErrorCode.DIRECTORY_NOT_FOUND);
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            throw error(ToolResult.ErrorCode.NOT_A_DIRECTORY, "The search path must be a directory.");
        }
        return new Directory(Path.of(context.workspace().path()), start);
    }

    /** 与目录工具共享范围校验，允许内容搜索缩小到一个已知文件。 */
    public static Scope scope(ToolContext context, Path path) {
        Path start = resolve(context, path, ToolResult.ErrorCode.FILE_NOT_FOUND);
        try {
            BasicFileAttributes attributes = Files.readAttributes(start, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() && !attributes.isRegularFile()) {
                throw error(ToolResult.ErrorCode.NOT_A_FILE, "Search path must be a directory or regular file.");
            }
            return new Scope(Path.of(context.workspace().path()), start, attributes.isDirectory());
        } catch (NoSuchFileException missing) {
            throw error(ToolResult.ErrorCode.FILE_NOT_FOUND, "Search path was not found.");
        } catch (AccessDeniedException denied) {
            throw error(ToolResult.ErrorCode.FILE_NOT_READABLE, "Search path access was denied.");
        } catch (IOException failed) {
            throw error(ToolResult.ErrorCode.FILE_READ_FAILED, "Failed to inspect search path.");
        }
    }

    /** 解析起点；不同工具可以保留各自“路径不存在”的错误分类。 */
    private static Path resolve(ToolContext context, Path path, ToolResult.ErrorCode missingCode) {
        if (context.workspace().mode() == WorkspaceBinding.Mode.NONE) {
            throw error(ToolResult.ErrorCode.WORKSPACE_REQUIRED, "This tool requires a project workspace.");
        }
        if (!context.hasWorkspace()) {
            throw error(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, "The workspace is unavailable.");
        }
        Path root = Path.of(context.workspace().path());
        Path requested = root.resolve(path).normalize();
        if (!requested.startsWith(root)) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Directory path is outside the workspace.");
        }
        try {
            Path start = requested.toRealPath();
            if (!start.startsWith(root)) {
                throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Directory resolves outside the workspace.");
            }
            return start;
        } catch (NoSuchFileException missing) {
            throw error(missingCode, "Search path was not found.");
        } catch (AccessDeniedException denied) {
            throw error(missingCode == ToolResult.ErrorCode.FILE_NOT_FOUND ? ToolResult.ErrorCode.FILE_NOT_READABLE
                    : ToolResult.ErrorCode.DIRECTORY_NOT_READABLE, "Search path access was denied.");
        } catch (IOException failed) {
            throw error(missingCode == ToolResult.ErrorCode.FILE_NOT_FOUND ? ToolResult.ErrorCode.FILE_READ_FAILED
                    : ToolResult.ErrorCode.DIRECTORY_READ_FAILED, "Failed to inspect search path.");
        }
    }

    /** 文件定位使用完整深度遍历，仍受扫描预算、调用方结果预算和中断控制。 */
    public static Scan scan(Directory directory, Visitor visitor) throws InterruptedException {
        return scanInternal(directory, null, visitor);
    }

    /** 目录浏览保留显式深度；具体工具决定如何生成结果。 */
    public static Scan scan(Directory directory, int maxDepth, Visitor visitor) throws InterruptedException {
        if (maxDepth < 1) throw new IllegalArgumentException("Directory depth must be positive.");
        return scanInternal(directory, maxDepth, visitor);
    }

    /** null 表示没有人为深度边界，避免用一个很大的整数假装取消限制。 */
    private static Scan scanInternal(Directory directory, Integer maxDepth, Visitor visitor) throws InterruptedException {
        var state = new State();
        try {
            walk(directory, maxDepth, visitor, state);
            return new Scan(state.scanned, state.scanLimited, state.depthLimited);
        } catch (NoSuchFileException missing) {
            throw error(ToolResult.ErrorCode.DIRECTORY_NOT_FOUND, "A directory or entry was not found; refresh the search.");
        } catch (AccessDeniedException denied) {
            throw error(ToolResult.ErrorCode.DIRECTORY_NOT_READABLE, "Directory access was denied; retry a narrower path.");
        } catch (IOException | DirectoryIteratorException failed) {
            checkInterrupted();
            throw error(ToolResult.ErrorCode.DIRECTORY_READ_FAILED, "Failed to inspect the directory; retry a narrower path.");
        }
    }

    /** 显式栈实现深度优先遍历，目录再深也不增加 Java 方法调用层数；目录流在入栈前关闭。 */
    private static void walk(Directory directory, Integer maxDepth, Visitor visitor, State state)
            throws IOException, InterruptedException {
        var frames = new ArrayDeque<Frame>();
        frames.push(new Frame(1, children(directory.root(), directory.start(), state).iterator()));
        while (!frames.isEmpty()) {
            checkInterrupted();
            Frame frame = frames.peek();
            if (!frame.children().hasNext()) { frames.pop(); continue; }
            Path child = frame.children().next();
            BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isDirectory() && IGNORED_DIRECTORY_NAMES.contains(child.getFileName().toString())) continue;
            String type;
            if (attributes.isSymbolicLink()) type = "symlink";
            else if (attributes.isDirectory()) type = child.toRealPath().equals(child) ? "directory" : "symlink";
            else type = attributes.isRegularFile() ? "file" : "other";
            if (!visitor.visit(new Entry(child, type, frame.depth()))) return;
            if ("directory".equals(type)) {
                if (maxDepth != null && frame.depth() >= maxDepth) state.depthLimited = true;
                else if (!state.scanLimited) {
                    frames.push(new Frame(frame.depth() + 1, children(directory.root(), child, state).iterator()));
                }
            }
        }
    }

    /** 每层有界收集并排序，忽略目录、链接策略和扫描预算由所有调用方共享。 */
    private static List<Path> children(Path root, Path directory, State state) throws IOException, InterruptedException {
        checkInterrupted();
        Path real = directory.toRealPath();
        if (!real.startsWith(root) || !real.equals(directory)) {
            throw error(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, "Directory changed or resolves outside the workspace.");
        }
        if (!Files.isReadable(real)) throw error(ToolResult.ErrorCode.DIRECTORY_NOT_READABLE, "Directory is not readable.");
        var children = new ArrayList<Path>();
        try (var stream = Files.newDirectoryStream(real)) {
            for (Path child : stream) {
                checkInterrupted();
                if (state.scanned >= MAX_SCANNED_ENTRIES) { state.scanLimited = true; break; }
                state.scanned++;
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return children;
    }

    /** 对外路径统一使用 /，根目录显示为 .。 */
    public static String relative(Path root, Path path) {
        String value = root.relativize(path).toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }

    /** 每层扫描和逐项处理均检查取消，保持线程标志交由执行器处理。 */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    /** 仅生成受控错误说明，底层异常不直接发给模型。 */
    private static ToolException error(ToolResult.ErrorCode code, String message) { return new ToolException(code, message); }

    /** 一层尚未处理完的目录项；全体缓存仍由全局扫描预算限制。 */
    private record Frame(int depth, Iterator<Path> children) { }

    /** 单次调用独立的状态，不维护跨工具的扫描缓存。 */
    private static final class State {
        /** 已从目录流取得的条目数，包含忽略项。 */
        private int scanned;
        /** 目录流还有条目，但已达到扫描上限。 */
        private boolean scanLimited;
        /** 遇到深度边界，存在未进入的目录。 */
        private boolean depthLimited;
    }
}
