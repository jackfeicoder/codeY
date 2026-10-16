package io.github.jackfeicoder.codey.tool.filesystem;

import io.github.jackfeicoder.codey.tool.ToolContext;
import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult.ErrorCode;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** 两个写工具共用的文本文件操作；不注册为模型工具，不调用 Shell 或模型。 */
public final class WorkspaceTextFiles {
    /** 修改时最多读取 4 MiB；完整读取用于精确替换和提交前冲突检查。 */
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** 模型一次提供的文本片段最多 64 KiB UTF-8；执行器另限制完整 JSON 参数长度。 */
    public static final int MAX_INPUT_BYTES = 64 * 1024;

    private WorkspaceTextFiles() { }

    /** 解析后的目标；path 可以尚不存在，但所有已有父级均已验证。 */
    public record Target(Path root, Path path) {
        /** 只返回工作区相对路径，不将宿主绝对位置放进结果。 */
        public String displayPath() { return root.relativize(path).toString().replace('\\', '/'); }
    }

    /** 原文件字节及身份属性，供本次工具调用提交前检查；不跨工具保存读取状态。 */
    public record Snapshot(byte[] bytes, BasicFileAttributes attributes) {
        /** 保留 BOM、空白和原换行符，局部替换之外的内容不重新排版。 */
        public String text() { return decode(bytes); }
    }

    /** 路径参数统一采用相对路径，Windows 的盘符、ADS 和控制字符也明确拒绝。 */
    public static Path relativePath(String value) {
        if (value == null || value.isBlank() || value.indexOf(':') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw error(ErrorCode.INVALID_ARGUMENTS, "Expected a nonblank relative file path without control characters or colons.");
        }
        return WorkspaceFiles.relativePath(value);
    }

    /** 以宿主真实工作区为根；写操作拒绝路径中的链接/junction，减少写入对象歧义。 */
    public static Target target(ToolContext context, Path relative) throws IOException {
        if (context.workspace().mode() == WorkspaceBinding.Mode.NONE) {
            throw error(ErrorCode.WORKSPACE_REQUIRED, "File writes require a project workspace.");
        }
        if (!context.hasWorkspace()) throw error(ErrorCode.WORKSPACE_UNAVAILABLE, "The workspace is unavailable.");
        Path root = Path.of(context.workspace().path());
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw error(ErrorCode.PATH_OUTSIDE_WORKSPACE, "File path is outside the workspace.");
        if (path.equals(root)) throw error(ErrorCode.NOT_A_FILE, "The workspace directory is not a file.");
        Target target = new Target(root, path);
        verifyPath(target);
        return target;
    }

    /** 检查所有已存在的组件；创建父目录后、提交前还会再次调用。不是操作系统级沙箱。 */
    private static void verifyPath(Target target) throws IOException {
        if (!target.root().toRealPath().equals(target.root())) {
            throw error(ErrorCode.PATH_OUTSIDE_WORKSPACE, "Workspace location changed; reopen the workspace.");
        }
        Path current = target.root();
        for (Path part : target.root().relativize(target.path())) {
            current = current.resolve(part);
            BasicFileAttributes attributes;
            try { attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException missing) { break; }
            if (attributes.isSymbolicLink() || !current.toRealPath().equals(current)) {
                throw error(ErrorCode.PATH_OUTSIDE_WORKSPACE, "Write paths must not traverse symbolic links or junctions.");
            }
            if (!current.equals(target.path()) && !attributes.isDirectory()) {
                throw error(ErrorCode.NOT_A_DIRECTORY, "A parent path is not a directory.");
            }
        }
    }

    /** 不存在时返回 null；存在时只接受普通文本文件，严格读取完整字节。 */
    public static Snapshot snapshot(Target target) throws IOException, InterruptedException {
        checkInterrupted();
        verifyPath(target);
        BasicFileAttributes before;
        try { before = Files.readAttributes(target.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return null; }
        if (!before.isRegularFile()) throw error(ErrorCode.NOT_A_FILE, "Only regular text files can be written.");
        if (before.size() > MAX_FILE_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Existing file exceeds the 4 MiB edit limit.");
        byte[] bytes;
        try (var input = Files.newInputStream(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        checkInterrupted();
        if (bytes.length > MAX_FILE_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Existing file grew beyond the edit limit.");
        BasicFileAttributes after = Files.readAttributes(target.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!sameIdentity(before, after) || bytes.length != after.size()) {
            throw error(ErrorCode.FILE_CONFLICT, "File changed during reading; read it again and retry.");
        }
        decode(bytes); // 二进制/NUL、非法 UTF-8 即使整体覆盖也拒绝。
        return new Snapshot(bytes, after);
    }

    /** 文本参数严格编码，拒绝未配对代理字符和 NUL，避免静默替换为问号。 */
    public static byte[] inputBytes(String text) {
        byte[] bytes = encode(text);
        if (bytes.length > MAX_INPUT_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Input text exceeds the 64 KiB UTF-8 limit.");
        return bytes;
    }

    /** 编辑后的整文件受独立大小限制，replaceAll 可能使结果大于输入片段。 */
    public static byte[] fileBytes(String text) {
        if (text.length() > MAX_FILE_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Result exceeds the 4 MiB file limit.");
        byte[] bytes = encode(text);
        if (bytes.length > MAX_FILE_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Result exceeds the 4 MiB file limit.");
        return bytes;
    }

    /** 临时文件写完后检查原文件，已有文件要求同目录原子替换；失败清理临时文件。 */
    public static void commit(Target target, Snapshot original, byte[] replacement) throws IOException, InterruptedException {
        checkInterrupted();
        verifyPath(target);
        if (original == null) createParents(target);
        Path temporary = Files.createTempFile(target.path().getParent(), ".codey-", ".tmp");
        try {
            Files.write(temporary, replacement, StandardOpenOption.TRUNCATE_EXISTING);
            // POSIX 平台保留模式位；Windows 文件权限仍由目标目录 ACL 与宿主权限控制。
            if (original != null && Files.getFileAttributeView(target.path(), PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target.path(), LinkOption.NOFOLLOW_LINKS));
            }
            checkInterrupted();
            verifyPath(target);
            verifyUnchanged(target, original);
            if (original == null) {
                // 同目录硬链接原子发布完整文件，CREATE_NEW 式语义防止并发创建覆盖；finally 移除临时名称。
                Files.createLink(target.path(), temporary);
            } else {
                if (!Files.isWritable(target.path())) throw new AccessDeniedException(target.path().toString());
                Files.move(temporary, target.path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 逐级创建并复检父目录；写入失败可能留下已创建的空父目录。 */
    private static void createParents(Target target) throws IOException, InterruptedException {
        Path parent = target.root();
        for (Path part : target.root().relativize(target.path().getParent())) {
            checkInterrupted();
            parent = parent.resolve(part);
            try { Files.createDirectory(parent); }
            catch (FileAlreadyExistsException exists) { /* 下方统一检查实际类型与链接。 */ }
            verifyPath(target);
        }
    }

    /** 捕获本次读取后发生的普通外部修改；检查与 rename 之间仍有短暂竞态窗口。 */
    public static void verifyUnchanged(Target target, Snapshot original) throws IOException, InterruptedException {
        Snapshot current = snapshot(target);
        if (original == null) {
            if (current != null) throw error(ErrorCode.FILE_CONFLICT, "File appeared before creation; read it and retry.");
        } else if (current == null || !sameIdentity(original.attributes(), current.attributes())
                || !Arrays.equals(original.bytes(), current.bytes())) {
            throw error(ErrorCode.FILE_CONFLICT, "File changed before writing; read it again and retry.");
        }
    }

    /** 指纹仅描述写入后的内容，便于核对；当前不是跨工具的读取凭证或授权。 */
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** 两个工具共用输出预算检查，在写入前调用；不截断成功 JSON。 */
    public static String checkedOutput(String output, ToolContext context) {
        if (output.codePointCount(0, output.length()) > context.maxOutputChars()) {
            throw error(ErrorCode.OUTPUT_LIMIT_TOO_SMALL, "Output budget cannot fit the write result; no file was changed.");
        }
        return output;
    }

    /** 写工具统一映射 I/O 失败，不向模型回显绝对路径或底层异常。 */
    public static ToolException failure(IOException failure) {
        if (failure instanceof AtomicMoveNotSupportedException) return error(ErrorCode.FILE_WRITE_FAILED, "Filesystem does not support atomic replacement; original file was preserved.");
        if (failure instanceof AccessDeniedException) return error(ErrorCode.FILE_NOT_WRITABLE, "File or parent directory access was denied.");
        if (failure instanceof FileAlreadyExistsException) return error(ErrorCode.FILE_CONFLICT, "Target appeared before creation; read it and retry.");
        if (failure instanceof NoSuchFileException) return error(ErrorCode.FILE_CONFLICT, "File or parent changed during writing; refresh and retry.");
        return error(ErrorCode.FILE_WRITE_FAILED, "Failed to write the text file.");
    }

    /** 工具边界检查中断；执行器负责恢复中断状态和错误结果。 */
    public static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    /** 同内容替换也检查身份和时间，避免误认为已换过的文件仍是原对象。 */
    private static boolean sameIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return java.util.Objects.equals(first.fileKey(), second.fileKey()) && first.size() == second.size()
                && first.lastModifiedTime().equals(second.lastModifiedTime()) && second.isRegularFile();
    }

    /** 解码保留字节对应的原始文本，包括开头 BOM；NUL 视为非文本文件。 */
    private static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw error(ErrorCode.NOT_TEXT_FILE, "NUL-containing files are not supported.");
            return text;
        } catch (CharacterCodingException invalid) { throw error(ErrorCode.INVALID_ENCODING, "Existing file is not valid UTF-8."); }
    }

    /** 先限制字符串大小，再严格编码，内存使用受输入或文件上限约束。 */
    private static byte[] encode(String text) {
        if (text.length() > MAX_FILE_BYTES) throw error(ErrorCode.FILE_TOO_LARGE, "Text exceeds the file limit.");
        if (text.indexOf('\0') >= 0) throw error(ErrorCode.NOT_TEXT_FILE, "Text must not contain NUL characters.");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException invalid) { throw error(ErrorCode.INVALID_ENCODING, "Text contains invalid Unicode characters."); }
    }

    /** 所有预期文件错误交由 ToolExecutor 转换为原调用对应的错误消息。 */
    private static ToolException error(ErrorCode code, String message) { return new ToolException(code, message); }
}
