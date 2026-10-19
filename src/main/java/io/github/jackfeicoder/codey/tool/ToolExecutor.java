package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 同步执行入口：查找 → JSON 解析 → 工作区检查 → 工具参数校验 → 执行 → 有界结果。 */
public final class ToolExecutor {
    /** 原始 JSON 参数文本上限；具体工具还需限制读取量、命令和其他业务输入。 */
    private static final int MAX_ARGUMENT_CHARS = 64 * 1024;
    /** 查找实现和注册时的稳定定义。 */
    private final ToolRegistry registry;
    /** 不接受重复键、尾随 JSON 或非对象参数，错误不回显原始参数。 */
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    /** registry 由宿主装配，不允许模型动态指定 Java 类。 */
    public ToolExecutor(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 预期失败归一化为结果，不自动重试；非法宿主参数与 JVM Error 不吞掉。 */
    public ToolResult execute(ToolCall call, ToolContext context) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(context, "context");
        long start = System.nanoTime(); // 单调时钟用于耗时测量，不是执行超时控制。
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new ToolException(ToolResult.ErrorCode.INTERRUPTED, "Tool execution was interrupted.");
            }
            ToolRegistry.RegisteredTool entry = registry.find(call.name()).orElseThrow(() ->
                    new ToolException(ToolResult.ErrorCode.UNKNOWN_TOOL, "Tool is not registered."));
            JsonNode arguments = parseArguments(call.arguments()); // 防止绕过 LLM 层直接调用时传入非法 JSON。
            if (entry.requiresWorkspace()) {
                if (context.workspace().mode() == WorkspaceBinding.Mode.NONE) {
                    throw new ToolException(ToolResult.ErrorCode.WORKSPACE_REQUIRED, "Tool requires a project workspace.");
                }
                if (!context.hasWorkspace()) {
                    throw new ToolException(ToolResult.ErrorCode.WORKSPACE_UNAVAILABLE, "Tool workspace is unavailable.");
                }
            }
            entry.implementation().validateArguments(arguments, context);
            io.github.jackfeicoder.codey.policy.PermissionPolicy.check(context.workMode(), entry.access());
            String output = entry.implementation().prepare(arguments.deepCopy(), context).execute(context);
            if (output == null) {
                throw new ToolException(ToolResult.ErrorCode.EXECUTION_FAILED, "Tool returned no result.");
            }
            return result(call, context, ToolResult.Status.SUCCESS, output, null, start);
        } catch (ToolException expected) {
            return result(call, context, ToolResult.Status.ERROR, expected.getMessage(), expected.code(), start);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return result(call, context, ToolResult.Status.ERROR, "Tool execution was interrupted.",
                    ToolResult.ErrorCode.INTERRUPTED, start);
        } catch (RuntimeException failed) {
            // 不附带任意实现的原始异常文本，避免把凭证或内部细节写入模型上下文。
            return result(call, context, ToolResult.Status.ERROR, "Tool execution failed.",
                    ToolResult.ErrorCode.EXECUTION_FAILED, start);
        }
    }

    /** 只校验 JSON 对象语法；schema/业务约束由 Tool.validateArguments 实现。 */
    private JsonNode parseArguments(String text) {
        if (text.length() > MAX_ARGUMENT_CHARS) {
            throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Tool arguments exceed the size limit.");
        }
        try {
            JsonNode arguments = mapper.readTree(text);
            if (arguments == null || !arguments.isObject()) {
                throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Tool arguments must be a JSON object.");
            }
            return arguments;
        } catch (JsonProcessingException invalid) {
            throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, "Tool arguments must be one valid JSON object.");
        }
    }

    /** 限制结果码点数量，保留调用 ID/错误码/耗时，截断不会破坏代理对字符。 */
    private ToolResult result(ToolCall call, ToolContext context, ToolResult.Status status,
                              String output, ToolResult.ErrorCode error, long start) {
        boolean truncated = output.codePointCount(0, output.length()) > context.maxOutputChars();
        if (truncated) {
            output = output.substring(0, output.offsetByCodePoints(0, context.maxOutputChars()));
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new ToolResult(call.id(), call.name(), status, output, error, elapsed, truncated);
    }
}
