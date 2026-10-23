package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.agent.AgentLoop;
import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.config.CommandSettings;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.process.CommandResolver;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 真正启动隔离 Java 子进程，覆盖 argv、管道、审批和 AgentLoop，不依赖厂商或项目构建成功。 */
@Timeout(15)
class ExecuteCommandToolTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ExecuteCommandTool tool = new ExecuteCommandTool(new CommandSettings(3, 5, 8000, StandardCharsets.UTF_8));
    private ToolContext context(PermissionMode mode, ApprovalHandler handler, WorkMode work, int maxOutput) throws IOException {
        var binding = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, workspace.toRealPath().toString());
        return new ToolContext(binding, maxOutput, work, new PermissionService(mode, binding, handler));
    }
    private ToolContext full() throws IOException { return context(PermissionMode.FULL, request -> { throw new AssertionError("No prompt expected"); }, WorkMode.REACT, 16384); }
    private ObjectNode command(String mode, String... arguments) throws Exception {
        var node = JSON.createObjectNode().put("command", javaProgram());
        var args = node.putArray("args").add("-cp").add(classpath()).add(Fixture.class.getName()).add(mode);
        for (String arg : arguments) args.add(arg);
        return node;
    }
    private static String javaProgram() { return Path.of(System.getProperty("java.home"), "bin", CommandResolver.windows() ? "java.exe" : "java").toString(); }
    private static String classpath() throws Exception { return Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    private ToolResult execute(JsonNode node, ToolContext context) {
        return new ToolExecutor(new ToolRegistry().register(tool)).execute(new ToolCall("command-call", "execute_command", node.toString()), context);
    }
    private JsonNode body(ToolResult result) throws Exception {
        assertEquals(ToolResult.Status.SUCCESS, result.status(), result.output()); assertFalse(result.truncated());
        return JSON.readTree(result.output());
    }

    @Test void capturesStdoutStderrLiteralArgumentsAndRelativeWorkingDirectory() throws Exception {
        Files.createDirectory(workspace.resolve("sub"));
        String literal = "空格 & | % ! \" value\\";
        var result = body(execute(command("echo", literal).put("workingDirectory", "sub"), full()));
        assertEquals("completed", result.path("status").asText()); assertEquals(0, result.path("exitCode").asInt());
        assertTrue(result.path("stdout").asText().contains(workspace.resolve("sub").toRealPath().toString()));
        assertTrue(result.path("stdout").asText().contains(literal), result.toString());
        assertTrue(result.path("stdout").asText().contains("output😀"));
        assertTrue(result.path("stderr").asText().contains("error-stream"));
        assertFalse(result.path("outputIncomplete").asBoolean());
    }

    @Test void nonzeroExitIsAnExecutionResultAndAgentCanUseItOnNextRound() throws Exception {
        var node = command("exit", "7");
        var step = new AtomicInteger();
        var result = new AgentLoop(request -> {
            if (step.incrementAndGet() == 1) return new LlmResponse(Message.assistantToolCalls(
                    List.of(new ToolCall("exit-call", "execute_command", node.toString()))), "tool_calls", null);
            try {
                var envelope = JSON.readTree(request.messages().get(request.messages().size() - 1).content());
                assertEquals("success", envelope.path("status").asText());
                var command = JSON.readTree(envelope.path("output").asText());
                assertEquals(7, command.path("exitCode").asInt());
                assertTrue(command.path("stderr").asText().contains("fixture failure"));
            } catch (IOException failed) { throw new AssertionError(failed); }
            return new LlmResponse(Message.assistant("测试失败，已获得报错，下一步需要修复。"), "stop", null);
        }, new ToolRegistry().register(tool)).run(new Session("base"), "运行测试", full());
        assertTrue(result.completed()); assertEquals(2, result.modelCalls()); assertEquals(1, result.toolCalls());
    }

    @Test void drainsLargeStreamsAndReturnsValidJsonWithinSmallOutputBudget() throws Exception {
        var result = body(execute(command("flood"), context(PermissionMode.FULL, ApprovalHandler.unavailable(), WorkMode.REACT, 512)));
        assertTrue(result.path("stdoutTruncated").asBoolean()); assertTrue(result.path("stderrTruncated").asBoolean());
        assertEquals(0, result.path("exitCode").asInt());
        assertTrue(result.toString().codePointCount(0, result.toString().length()) <= 512);
    }

    @ParameterizedTest @ValueSource(strings = {"", "\"quoted\"", "a\\\"b", "C:\\space path\\"})
    void independentArgumentsPreserveQuotesEmptyValuesAndTrailingBackslashes(String argument) throws Exception {
        var result = body(execute(command("echo", argument), full()));
        String output = result.path("stdout").asText().replace("\r\n", "\n");
        assertTrue(output.endsWith("output😀\n" + argument + "\n"), result.toString());
    }

    @Test void standardInputIsClosedInsteadOfWaitingForInteractiveAnswers() throws Exception {
        var result = body(execute(command("stdin"), full()));
        assertEquals(0, result.path("exitCode").asInt()); assertTrue(result.path("stdout").asText().contains("EOF"));
    }

    @Test void timeoutKillsObservedProcessTreeAndReturnsExplicitTimeout() throws Exception {
        var result = body(execute(command("tree", "child.pid").put("timeoutSeconds", 2), full()));
        assertEquals("timed_out", result.path("status").asText()); assertTrue(result.path("timedOut").asBoolean());
        assertTrue(result.path("exitCode").isNull());
        long pid = Long.parseLong(Files.readString(workspace.resolve("child.pid")).strip());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(result.path("cleanupIncomplete").asBoolean());
    }

    @Test void interruptionStopsProcessAndPreservesExecutorInterruptSignal() throws Exception {
        var node = command("sleep", "parent.pid").put("timeoutSeconds", 5);
        var context = full(); var result = new AtomicReference<ToolResult>(); var interrupted = new AtomicReference<Boolean>();
        Thread worker = new Thread(() -> { result.set(execute(node, context)); interrupted.set(Thread.currentThread().isInterrupted()); });
        worker.start();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while (!Files.exists(workspace.resolve("parent.pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(workspace.resolve("parent.pid")));
            worker.interrupt(); worker.join(3000); assertFalse(worker.isAlive());
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, result.get().errorCode()); assertEquals(Boolean.TRUE, interrupted.get());
            long pid = Long.parseLong(Files.readString(workspace.resolve("parent.pid")).strip());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { worker.interrupt(); worker.join(3000); }
    }

    @ParameterizedTest @EnumSource(value = PermissionMode.class, names = {"ASK", "AUTO"})
    void everyCommandRequiresApprovalEvenWithFileGrants(PermissionMode mode) throws Exception {
        var prompts = new AtomicInteger();
        var context = context(mode, request -> { prompts.incrementAndGet(); if (request.command()) assertFalse(request.fileGrant()); return ApprovalHandler.Answer.APPROVE; }, WorkMode.REACT, 16384);
        if (mode == PermissionMode.ASK) {
            new WriteFileTool().execute(JSON.createObjectNode().put("path", "a.txt").put("content", "x"), context);
            prompts.set(0);
        }
        body(execute(command("echo"), context)); body(execute(command("echo"), context));
        assertEquals(2, prompts.get());
        assertEquals(mode == PermissionMode.ASK ? 1 : 0, context.permissions().grantCount());
    }

    @Test void denialOnlyCachesSameCommandAndMissingApprovalStartsNothing() throws Exception {
        var prompts = new AtomicInteger();
        var context = context(PermissionMode.ASK, request -> prompts.incrementAndGet() == 1
                ? ApprovalHandler.Answer.DENY : ApprovalHandler.Answer.APPROVE, WorkMode.REACT, 16384);
        var node = command("touch", "denied.txt");
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(node, context).errorCode());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(node.deepCopy().put("timeoutSeconds", 4), context).errorCode());
        body(execute(command("echo"), context)); assertEquals(2, prompts.get()); assertFalse(Files.exists(workspace.resolve("denied.txt")));
        var unavailable = context(PermissionMode.AUTO, ApprovalHandler.unavailable(), WorkMode.REACT, 16384);
        assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED, execute(node, unavailable).errorCode());
        assertFalse(Files.exists(workspace.resolve("denied.txt")));
    }

    @Test void planFullAndNoWorkspaceHideOrRejectTheToolBeforeExecution() throws Exception {
        var plan = context(PermissionMode.FULL, ApprovalHandler.unavailable(), WorkMode.PLAN, 16384);
        assertTrue(new ToolRegistry().register(tool).definitions(plan).isEmpty());
        assertEquals(ToolResult.ErrorCode.PERMISSION_DENIED, execute(command("touch", "plan.txt"), plan).errorCode());
        assertThrows(ToolException.class, () -> tool.execute(command("touch", "plan.txt"), plan));
        var none = new ToolContext(WorkspaceBinding.none());
        assertTrue(new ToolRegistry().register(tool).definitions(none).isEmpty());
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED, execute(command("echo"), none).errorCode());
        assertFalse(Files.exists(workspace.resolve("plan.txt")));
    }

    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"command\":\"\"}", "{\"command\":\"java\",\"args\":null}",
            "{\"command\":\"java\",\"args\":[1]}", "{\"command\":\"java\",\"args\":[\"a\\u0000b\"]}",
            "{\"command\":\"java\",\"timeoutSeconds\":0}", "{\"command\":\"java\",\"timeoutSeconds\":6}",
            "{\"command\":\"java\",\"timeoutSeconds\":1.5}", "{\"command\":\"java\",\"timeoutSeconds\":\"3\"}",
            "{\"command\":\"java\",\"workingDirectory\":null}", "{\"command\":\"java\",\"shell\":true}"
    }) void rejectsInvalidArgumentsWithoutApproving(String arguments) throws Exception {
        var context = context(PermissionMode.ASK, request -> { fail("No approval for invalid request"); return null; }, WorkMode.REACT, 16384);
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(JSON.readTree(arguments), context).errorCode());
    }

    @Test void validatesPathsProgramAndBudgetBeforeApproval() throws Exception {
        var context = context(PermissionMode.ASK, request -> { fail("No approval for invalid request"); return null; }, WorkMode.REACT, 16384);
        assertEquals(ToolResult.ErrorCode.PATH_OUTSIDE_WORKSPACE, execute(command("echo").put("workingDirectory", ".."), context).errorCode());
        assertEquals(ToolResult.ErrorCode.DIRECTORY_NOT_FOUND, execute(command("echo").put("workingDirectory", "missing"), context).errorCode());
        assertEquals(ToolResult.ErrorCode.COMMAND_NOT_FOUND, execute(JSON.createObjectNode().put("command", "codey_missing_fixture_8a5"), context).errorCode());
        var small = context(PermissionMode.FULL, ApprovalHandler.unavailable(), WorkMode.REACT, 10);
        assertEquals(ToolResult.ErrorCode.OUTPUT_LIMIT_TOO_SMALL, execute(command("touch", "budget.txt"), small).errorCode());
        assertFalse(Files.exists(workspace.resolve("budget.txt")));
    }

    @Test void preparedArgumentsStayFixedAndDirectoryReplacementIsDetected() throws Exception {
        var node = command("touch", "original.txt");
        var context = context(PermissionMode.ASK, request -> {
            assertTrue(request.command()); assertFalse(request.fileGrant());
            assertTrue(request.preview().contains("original.txt"));
            ((com.fasterxml.jackson.databind.node.ArrayNode) node.get("args")).set(4, JSON.getNodeFactory().textNode("changed.txt"));
            return ApprovalHandler.Answer.APPROVE;
        }, WorkMode.REACT, 16384);
        var prepared = tool.prepare(node, context); prepared.execute(context);
        assertTrue(Files.exists(workspace.resolve("original.txt"))); assertFalse(Files.exists(workspace.resolve("changed.txt")));
        Files.createDirectory(workspace.resolve("sub"));
        var replaced = context(PermissionMode.ASK, request -> {
            try { Files.move(workspace.resolve("sub"), workspace.resolve("old")); Files.createDirectory(workspace.resolve("sub")); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
            return ApprovalHandler.Answer.APPROVE;
        }, WorkMode.REACT, 16384);
        assertEquals(ToolResult.ErrorCode.FILE_CONFLICT, execute(command("touch", "unexpected.txt").put("workingDirectory", "sub"), replaced).errorCode());
        assertFalse(Files.exists(workspace.resolve("sub/unexpected.txt")));
    }

    @Test void windowsBatchAdapterPreservesSpacesAndRefusesShellMetacharacters() throws Exception {
        Assumptions.assumeTrue(CommandResolver.windows());
        Path folder = Files.createDirectory(workspace.resolve("batch space"));
        Path script = folder.resolve("fixture.cmd");
        Files.writeString(script, "@echo off\r\necho %~1\r\nexit /b 7\r\n", StandardCharsets.US_ASCII);
        Files.writeString(folder.resolve("fixture"), "#!/bin/sh\necho unix fixture\n", StandardCharsets.US_ASCII);
        var node = JSON.createObjectNode().put("command", folder.resolve("fixture").toString()); node.putArray("args").add("hello world");
        var result = body(execute(node, full()));
        assertEquals(7, result.path("exitCode").asInt()); assertTrue(result.path("stdout").asText().contains("hello world"));
        node.putArray("args").add("hello & echo injected");
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, execute(node, full()).errorCode());
    }

    @Test void executableChangesDuringApprovalAreRejectedAndLaunchFailureIsClassified() throws Exception {
        Assumptions.assumeTrue(CommandResolver.windows());
        Path script = workspace.resolve("changed.cmd"); Files.writeString(script, "@echo off\r\nexit /b 0\r\n");
        var context = context(PermissionMode.ASK, request -> {
            try { Files.writeString(script, "@echo off\r\necho changed\r\nexit /b 0\r\n"); }
            catch (IOException failed) { throw new UncheckedIOException(failed); }
            return ApprovalHandler.Answer.APPROVE;
        }, WorkMode.REACT, 16384);
        assertEquals(ToolResult.ErrorCode.FILE_CONFLICT, execute(JSON.createObjectNode().put("command", script.toString()), context).errorCode());
        Path broken = workspace.resolve("broken.exe"); Files.writeString(broken, "fixture, not an executable");
        assertEquals(ToolResult.ErrorCode.COMMAND_START_FAILED, execute(JSON.createObjectNode().put("command", broken.toString()), full()).errorCode());
    }

    /** 子进程入口只使用 JDK，所有写入都落在测试工作区，不运行真实构建或读取凭证。 */
    public static final class Fixture {
        private static String javaProgram() { return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString(); }
        private static String classpath() throws Exception {
            return Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        }
        public static void main(String[] args) throws Exception {
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
            switch (args[0]) {
                case "echo" -> { System.out.println(Path.of("").toAbsolutePath()); System.out.println("output😀"); if (args.length > 1) System.out.println(args[1]); System.err.println("error-stream"); }
                case "exit" -> { System.err.println("fixture failure"); System.exit(Integer.parseInt(args[1])); }
                case "flood" -> { System.out.print("😀\\\"\n".repeat(60000)); System.out.flush(); System.err.print("错误\\\"\n".repeat(60000)); }
                case "touch" -> Files.writeString(Path.of(args[1]), "fixture");
                case "stdin" -> System.out.println(new BufferedReader(new InputStreamReader(System.in)).readLine() == null ? "EOF" : "unexpected");
                case "sleep" -> { Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid())); Thread.sleep(10000); }
                case "tree" -> { var child = new ProcessBuilder(javaProgram(), "-cp", classpath(), Fixture.class.getName(), "sleep", args[1]).inheritIO().start(); child.waitFor(); }
                default -> throw new IllegalArgumentException("fixture mode");
            }
        }
    }
}
