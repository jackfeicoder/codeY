package io.github.jackfeicoder.codey.tool.filesystem;

import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.channels.Channels;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 读取与内容搜索共用的有界 UTF-8 行读取器；只负责文本读取，不执行工具或解析模型参数。 */
public final class Utf8TextReader implements AutoCloseable {
    /** 限制实际读取字节数，覆盖读取过程中外部增长的文件。 */
    public static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    /** 单行 UTF-16 单元上限，避免 readLine 无界分配。 */
    public static final int MAX_LINE_CHARS = 16_384;
    /** 解码器可少量预读；回退一个字符用于 CRLF 与分页探测。 */
    private final PushbackReader reader;

    /** 外部先完成路径与类型检查，再打开已解析路径；不跟随最终路径上的符号链接。 */
    private Utf8TextReader(PushbackReader reader) { this.reader = reader; }

    /** 单文件读取仅限制自身字节数，不影响其他工具调用。 */
    public static Utf8TextReader open(Path file) throws IOException { return open(file, null); }

    /** 内容搜索可额外传入跨文件共享预算，实际底层读取字节（包括预读）都计入。 */
    public static Utf8TextReader open(Path file, ByteBudget budget) throws IOException {
        InputStream stream = new LimitedInputStream(Channels.newInputStream(
                Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)), budget);
        var reader = new PushbackReader(new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1);
        try {
            int first = reader.read(); // 只移除文件开头 BOM。
            if (first != -1 && first != '\uFEFF') reader.unread(first);
            return new Utf8TextReader(reader);
        } catch (IOException | RuntimeException failed) {
            try { reader.close(); } catch (IOException closeFailed) { failed.addSuppressed(closeFailed); }
            throw failed;
        }
    }

    /** 兼容 LF/CRLF/CR，保留空白，末尾换行不产生额外空行；NUL 视为非文本。 */
    public String readLine() throws IOException, InterruptedException {
        var text = new StringBuilder();
        while (true) {
            if ((text.length() & 255) == 0) checkInterrupted();
            int current = reader.read();
            if (current == -1) return text.isEmpty() ? null : text.toString();
            if (current == '\n') return text.toString();
            if (current == '\r') {
                int next = reader.read();
                if (next != -1 && next != '\n') reader.unread(next);
                return text.toString();
            }
            if (current == 0) throw new ToolException(ToolResult.ErrorCode.NOT_TEXT_FILE, "File contains a NUL character.");
            if (text.length() >= MAX_LINE_CHARS) {
                throw new ToolException(ToolResult.ErrorCode.LINE_TOO_LONG, "A line exceeds the text reader character limit.");
            }
            text.append((char) current);
        }
    }

    /** 分页读取仅探测下一字符，不预先读取下一整条可能很长的行。 */
    public boolean hasMore() throws IOException {
        int next = reader.read();
        if (next != -1) reader.unread(next);
        return next != -1;
    }

    /** 关闭解码器及其持有的文件通道。 */
    @Override
    public void close() throws IOException { reader.close(); }

    /** 行读取期间检查取消，由 ToolExecutor 恢复和保留中断标志。 */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    /** 单次搜索的跨文件实际字节预算，不接受模型修改，不在多个任务之间共享。 */
    public static final class ByteBudget {
        /** 宿主固定的实际字节上限。 */
        private final long maximum;
        /** 已被底层文件流读取的字节数。 */
        private long used;

        /** 宿主提供正数预算；计数包含解码器预读。 */
        public ByteBudget(long maximum) {
            if (maximum <= 0) throw new IllegalArgumentException("Byte budget must be positive.");
            this.maximum = maximum;
        }

        /** 已读取的实际字节，供搜索结果展示。 */
        public long used() { return used; }
        /** 底层流最多再读取多少字节。 */
        private long remaining() { return maximum - used; }
    }

    /** 跨文件预算耗尽属于搜索截断，不伪装成某个文件的编码或读取失败。 */
    public static final class ByteLimitExceeded extends IOException { }

    /** 单文件和跨文件预算都在解码前执行，不使用整文件缓冲或大小估算。 */
    private static final class LimitedInputStream extends FilterInputStream {
        /** 当前文件实际读取量。 */
        private int count;
        /** 可选跨文件预算，read_file 独立读取时为 null。 */
        private final ByteBudget budget;

        private LimitedInputStream(InputStream input, ByteBudget budget) { super(input); this.budget = budget; }

        @Override
        public int read() throws IOException {
            requireBudget();
            int value = in.read();
            if (value != -1) record(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            requireBudget();
            int allowed = Math.min(length, MAX_FILE_BYTES - count + 1); // 最多多读一字节以确认文件已增长越界。
            if (budget != null) allowed = (int) Math.min(allowed, budget.remaining());
            int read = in.read(bytes, offset, allowed);
            if (read > 0) record(read);
            return read;
        }

        /** 总预算耗尽时不再探测 EOF，宁可报告截断，也不额外读取字节。 */
        private void requireBudget() throws ByteLimitExceeded {
            if (budget != null && budget.remaining() == 0) throw new ByteLimitExceeded();
        }

        /** 文件增长越界时终止；总预算由 read 的请求长度保证不会越界。 */
        private void record(int bytes) {
            count += bytes;
            if (budget != null) budget.used += bytes;
            if (count > MAX_FILE_BYTES) throw new ToolException(ToolResult.ErrorCode.FILE_TOO_LARGE, "File exceeds the 4 MiB text limit.");
        }
    }
}
