package io.github.jackfeicoder.codey.tool.process;

import io.github.jackfeicoder.codey.tool.ToolException;
import io.github.jackfeicoder.codey.tool.ToolResult.ErrorCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/** 有界本地进程执行：并行排空两个管道，超时/中断回收进程，禁止后台交互输入。 */
public final class CommandRunner {
    /** complete 只说明主进程结束；exitCode、超时、截断与清理状态仍需调用方判断。 */
    public record Result(String status, Integer exitCode, String stdout, String stderr, long durationMs,
                         boolean timedOut, boolean stdoutTruncated, boolean stderrTruncated,
                         boolean outputIncomplete, boolean cleanupIncomplete) { }

    /** invocation 已完成平台适配，工作目录必须由宿主验证；不通过 Shell 解释常规 argv。 */
    public Result run(List<String> invocation, Path directory, int timeoutSeconds, int outputChars, Charset charset)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long start = System.nanoTime();
        Process process;
        try { process = new ProcessBuilder(invocation).directory(directory.toFile()).start(); }
        catch (IOException failed) { throw new ToolException(ErrorCode.COMMAND_START_FAILED, "Failed to start the program. Check installation and executable permissions."); }
        var executor = Executors.newFixedThreadPool(2, action -> {
            Thread thread = new Thread(action, "codey-command-output"); thread.setDaemon(true); return thread;
        });
        Capture stdout = new Capture(process.getInputStream(), outputChars / 2, charset);
        Capture stderr = new Capture(process.getErrorStream(), outputChars / 2, charset);
        Future<?> out = executor.submit(stdout), err = executor.submit(stderr);
        Map<Long, ProcessHandle> children = new LinkedHashMap<>();
        boolean timedOut = false;
        boolean cleanupIncomplete = false;
        try {
            process.getOutputStream().close(); // 首版没有 stdin 参数，读输入的程序立即看到 EOF。
            long deadline = start + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (true) {
                discover(process, children);
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline) { timedOut = true; break; }
            }
            cleanupIncomplete = terminate(process, children); // 正常结束也不保留已观察到的后台子进程。
            stdout.finish(); stderr.finish();
            boolean drained = await(out) & await(err);
            String output = stdout.text(), error = stderr.text();
            return new Result(timedOut ? "timed_out" : "completed", timedOut ? null : process.exitValue(), output, error,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), timedOut, stdout.truncated(), stderr.truncated(),
                    !drained || stdout.incomplete || stderr.incomplete, cleanupIncomplete);
        } catch (IOException failed) {
            throw new ToolException(ErrorCode.COMMAND_START_FAILED, "Failed to initialize process input.");
        } finally {
            // InterruptedException 也经过这里，不把仍运行的主进程留给下一轮。
            terminate(process, children);
            stdout.finish(); stderr.finish();
            executor.shutdown();
            // Windows 进程报告退出后，管道线程也要关闭完毕再返回，避免残留句柄锁住工作目录。
            boolean interrupted = Thread.interrupted();
            try { if (!executor.awaitTermination(750, TimeUnit.MILLISECONDS)) executor.shutdownNow(); }
            catch (InterruptedException cancelled) { interrupted = true; executor.shutdownNow(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static void discover(Process process, Map<Long, ProcessHandle> children) {
        try { process.descendants().forEach(child -> children.put(child.pid(), child)); }
        catch (RuntimeException unavailable) { /* 部分系统限制进程枚举，清理只做到宿主能力范围内。 */ }
    }

    /** 仅处理实际观察到的后代；不调用 taskkill、Shell 或杀死同名无关进程。 */
    private static boolean terminate(Process process, Map<Long, ProcessHandle> children) {
        discover(process, children);
        var reverse = new java.util.ArrayList<>(children.values()); java.util.Collections.reverse(reverse);
        for (ProcessHandle child : reverse) if (child.isAlive()) {
            try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
        }
        if (process.isAlive()) try { process.destroyForcibly(); } catch (RuntimeException ignored) { }
        // 等待真正的进程退出通知；只看退出码可能早于 Windows 释放进程所持的目录句柄。
        boolean interrupted = Thread.interrupted();
        try { process.onExit().get(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException cancelled) { interrupted = true; }
        catch (ExecutionException | TimeoutException ignored) { }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        // cleanup 不能再次抛中断而跳过回收；保留调用线程原有中断状态。
        while (System.nanoTime() < deadline && (process.isAlive() || reverse.stream().anyMatch(ProcessHandle::isAlive)))
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        return process.isAlive() || reverse.stream().anyMatch(ProcessHandle::isAlive);
    }

    private static boolean await(Future<?> future) throws InterruptedException {
        try { future.get(1, TimeUnit.SECONDS); return true; }
        catch (ExecutionException | TimeoutException failed) { return false; }
    }

    /** 只保留有限字节，超出部分继续消费；轮询 available 避免后代持有管道导致无限阻塞。 */
    private static final class Capture implements Runnable {
        private final InputStream stream;
        private final int chars;
        private final Charset charset;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int byteLimit;
        private volatile boolean stop;
        private volatile long drainDeadline;
        private volatile boolean incomplete;
        private boolean byteTruncated;
        Capture(InputStream stream, int chars, Charset charset) {
            this.stream = stream; this.chars = chars; this.charset = charset;
            byteLimit = Math.max(1024, chars * 4);
        }
        void finish() { drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200); stop = true; }
        @Override public void run() {
            byte[] buffer = new byte[8192];
            try (stream) {
                while (!Thread.currentThread().isInterrupted()) {
                    int available = stream.available();
                    if (stop && available == 0) break;
                    if (stop && System.nanoTime() >= drainDeadline) { incomplete = available > 0; break; }
                    if (available == 0) { Thread.sleep(5); continue; }
                    int read = stream.read(buffer, 0, Math.min(available, buffer.length));
                    if (read < 0) break;
                    synchronized (bytes) {
                        int keep = Math.min(read, byteLimit - bytes.size());
                        bytes.write(buffer, 0, keep);
                        if (keep < read) byteTruncated = true;
                    }
                }
            } catch (IOException | InterruptedException stopped) { incomplete = true; }
        }
        String text() {
            String value;
            synchronized (bytes) { value = bytes.toString(charset); }
            return value.codePointCount(0, value.length()) > chars
                    ? value.substring(0, value.offsetByCodePoints(0, chars)) : value;
        }
        boolean truncated() {
            synchronized (bytes) { String value = bytes.toString(charset);
                return byteTruncated || value.codePointCount(0, value.length()) > chars; }
        }
    }
}
