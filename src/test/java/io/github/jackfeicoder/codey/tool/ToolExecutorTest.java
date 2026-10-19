package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.MessageRole;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 测试统一执行入口的校验顺序、结果关联、错误隔离、输出边界和中断传播。 */
class ToolExecutorTest {
    /** 文件夹仅用于工作区可用性检查，测试工具不读取磁盘内容。 */
    @TempDir Path directory;

    /** 成功结果转换后仍关联原 ID，JSON 正文保留状态和原始文本。 */
    @Test
    void executesValidatedCallAndBuildsMatchingToolMessage() throws Exception {
        Echo tool = new Echo();
        ToolResult result = execute(tool, "{\"text\":\"中文\\n内容\"}");
        assertEquals(1, tool.executions);
        assertEquals(ToolResult.Status.SUCCESS, result.status());
        assertEquals("中文\n内容", result.output());
        assertNull(result.errorCode());
        Message message = result.toMessage();
        assertEquals(MessageRole.TOOL, message.role());
        assertEquals("call-1", message.toolCallId());
        JsonNode body = new ObjectMapper().readTree(message.content());
        assertEquals("success", body.path("status").asText());
        assertEquals(result.output(), body.path("output").asText());
        assertFalse(body.path("truncated").asBoolean());
        assertTrue(result.durationMillis() >= 0);
    }

    /** 非 JSON、非对象、重复键或尾随数据均不能进入工具实现。 */
    @ParameterizedTest
    @ValueSource(strings = {"not-json", "[]", "null", "{} {}", "{\"text\":\"x\",\"text\":\"fixture-secret\"}",
            "{\"text\":\"x\"} trailing"})
    void malformedArgumentsNeverExecute(String arguments) {
        Echo tool = new Echo();
        ToolResult result = execute(tool, arguments);
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, result.errorCode());
        assertEquals(0, tool.executions);
        assertFalse(result.output().contains("fixture-secret"));
    }

    /** 对象语法正确也必须通过具体工具的必填、类型和额外字段校验。 */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"text\":5}", "{\"text\":\"ok\",\"other\":1}"})
    void businessValidationRunsBeforeExecution(String arguments) {
        Echo tool = new Echo();
        ToolResult result = execute(tool, arguments);
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, result.errorCode());
        assertEquals(0, tool.executions);
    }

    /** 未注册名称只能产生明确错误，不能触发反射加载或任意执行。 */
    @Test
    void unknownToolReturnsLinkedFailure() {
        ToolResult result = new ToolExecutor(new ToolRegistry()).execute(new ToolCall("call-x", "missing", "{}"),
                new ToolContext(WorkspaceBinding.none()));
        assertEquals("call-x", result.toMessage().toolCallId());
        assertEquals(ToolResult.Status.ERROR, result.status());
        assertEquals(ToolResult.ErrorCode.UNKNOWN_TOOL, result.errorCode());
    }

    /** 需要工作区的工具在普通聊天或失效目录下都不能执行。 */
    @Test
    void workspaceRequirementIsEnforced() throws Exception {
        Echo tool = new Echo() { public boolean requiresWorkspace() { return true; } };
        ToolExecutor executor = new ToolExecutor(new ToolRegistry().register(tool));
        ToolCall call = new ToolCall("call-1", "echo", "{\"text\":\"ok\"}");
        assertEquals(ToolResult.ErrorCode.WORKSPACE_REQUIRED,
                executor.execute(call, new ToolContext(WorkspaceBinding.none())).errorCode());
        Path project = Files.createDirectory(directory.resolve("project"));
        ToolContext context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,
                project.toRealPath().toString()));
        assertEquals(ToolResult.Status.SUCCESS, executor.execute(call, context).status());
        Files.delete(project);
        assertEquals(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, executor.execute(call, context).errorCode());
        assertEquals(1, tool.executions);
    }

    /** 截断按码点处理，输出长度不额外增加提示文本，也不截断代理对字符。 */
    @Test
    void outputLimitPreservesUnicodeCharacters() {
        ToolExecutor executor = new ToolExecutor(new ToolRegistry().register(new Echo()));
        ToolResult result = executor.execute(new ToolCall("call-1", "echo", "{\"text\":\"😀😀\"}"),
                new ToolContext(WorkspaceBinding.none(), 1));
        assertEquals("😀", result.output());
        assertTrue(result.truncated());
    }

    /** 预期业务错误保留类别；任意 RuntimeException 文本不进入模型上下文。 */
    @Test
    void expectedAndUnexpectedErrorsAreNormalized() {
        Echo expected = new Echo() {
            public String execute(JsonNode arguments, ToolContext context) {
                throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED, "Expected fixture failure.");
            }
        };
        assertEquals("Expected fixture failure.", execute(expected, "{\"text\":\"ok\"}").output());
        Echo unexpected = new Echo() {
            public String execute(JsonNode arguments, ToolContext context) {
                throw new IllegalStateException("fixture-secret");
            }
        };
        ToolResult result = execute(unexpected, "{\"text\":\"ok\"}");
        assertEquals(ToolResult.ErrorCode.EXECUTION_FAILED, result.errorCode());
        assertFalse(result.toMessage().content().contains("fixture-secret"));
    }

    /** 工具的 InterruptedException 恢复线程标志，并阻止已中断线程再执行工具。 */
    @Test
    void interruptionIsPreservedAndPreventsFurtherExecution() {
        Echo interrupted = new Echo() {
            public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
                throw new InterruptedException("fixture");
            }
        };
        try {
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, execute(interrupted, "{\"text\":\"ok\"}").errorCode());
            assertTrue(Thread.currentThread().isInterrupted());
            Echo next = new Echo();
            assertEquals(ToolResult.ErrorCode.INTERRUPTED, execute(next, "{\"text\":\"ok\"}").errorCode());
            assertEquals(0, next.executions);
        } finally {
            Thread.interrupted(); // 不将测试的中断状态带入其他用例。
        }
    }

    /** 过大参数在解析前拒绝，工具仍未执行。 */
    @Test
    void oversizedArgumentsDoNotExecute() {
        Echo tool = new Echo();
        ToolResult result = execute(tool, "{\"text\":\"" + "x".repeat(64 * 1024) + "\"}");
        assertEquals(ToolResult.ErrorCode.INVALID_ARGUMENTS, result.errorCode());
        assertEquals(0, tool.executions);
    }

    /** 工具必须给出非 null 结果；JVM Error 不被执行器当作普通业务失败吞掉。 */
    @Test
    void nullOutputIsFailureButJvmErrorsPropagate() {
        Echo empty = new Echo() { public String execute(JsonNode args, ToolContext context) { return null; } };
        assertEquals(ToolResult.ErrorCode.EXECUTION_FAILED, execute(empty, "{\"text\":\"ok\"}").errorCode());
        Echo fatal = new Echo() {
            public String execute(JsonNode args, ToolContext context) { throw new AssertionError("fixture fatal error"); }
        };
        assertThrows(AssertionError.class, () -> execute(fatal, "{\"text\":\"ok\"}"));
    }

    /** 每次创建独立注册表，不把测试 echo 工具装进生产 CLI。 */
    private static ToolResult execute(Tool tool, String arguments) {
        return new ToolExecutor(new ToolRegistry().register(tool)).execute(new ToolCall("call-1", "echo", arguments),
                new ToolContext(WorkspaceBinding.none()));
    }

    /** 用于观察校验与执行顺序的内存工具。 */
    private static class Echo implements Tool {
        public ToolAccess access() { return ToolAccess.READ_ONLY; }
        /** 实际执行次数，校验失败时必须不增长。 */
        private int executions;

        public ToolDefinition definition() {
            var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
            schema.putObject("properties").putObject("text").put("type", "string");
            schema.putArray("required").add("text");
            schema.put("additionalProperties", false);
            return new ToolDefinition("echo", "返回输入的文本，仅用于测试", schema);
        }

        public void validateArguments(JsonNode arguments, ToolContext context) {
            if (!arguments.path("text").isTextual() || arguments.size() != 1) {
                throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, "A single text argument is required.");
            }
        }

        public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
            executions++;
            return arguments.path("text").asText();
        }
    }
}
