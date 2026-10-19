package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceTextFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

/** 新建 UTF-8 文件或显式整体覆盖；局部编辑交给 edit_file，工具之间互不调用。 */
public final class WriteFileTool implements Tool {
    /** schema 和结果编码器，不保存调用状态。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 只接受完整内容、相对路径和覆盖开关。 */
    private static final Set<String> FIELDS = Set.of("path", "content", "overwrite");
    /** 参数默认值仅为说明，实际校验也将缺省 overwrite 解释为 false。 */
    private static final ToolDefinition DEFINITION = createDefinition();

    /** 返回注册时固定的结构化工具说明。 */
    @Override public ToolDefinition definition() { return DEFINITION; }
    /** 普通聊天不展示或执行文件写入。 */
    @Override public boolean requiresWorkspace() { return true; }
    @Override public ToolAccess access() { return ToolAccess.FILE_MUTATION; }
    /** 实际字段/类型/编码校验与 execute 共用，不只依赖 schema。 */
    @Override public void validateArguments(JsonNode arguments, ToolContext context) { options(arguments); }

    /** 先构造完整结果并检查输出预算，再落盘；成功输出不含文件正文。 */
    @Override public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        return prepare(arguments, context).execute(context);
    }

    /** 捕获本次完整变更，审批界面与真正提交使用相同快照和字节。 */
    @Override public PreparedToolOperation prepare(JsonNode arguments, ToolContext context) throws InterruptedException {
        io.github.jackfeicoder.codey.policy.PermissionPolicy.check(context.workMode(), access());
        WorkspaceTextFiles.checkInterrupted();
        Options options = options(arguments);
        try {
            var target = WorkspaceTextFiles.target(context, options.path());
            var original = WorkspaceTextFiles.snapshot(target);
            if (original != null && !options.overwrite()) {
                throw new ToolException(ToolResult.ErrorCode.FILE_ALREADY_EXISTS,
                        "File already exists. Read it first; use edit_file for a local change or overwrite=true for full replacement.");
            }
            boolean changed = original == null || !Arrays.equals(original.bytes(), options.content());
            String output = WorkspaceTextFiles.checkedOutput(MAPPER.createObjectNode().put("path", target.displayPath())
                    .put("operation", original == null ? "create" : "overwrite").put("changed", changed)
                    .put("bytesWritten", changed ? options.content().length : 0).put("fileBytes", options.content().length)
                    .put("sha256", WorkspaceTextFiles.sha256(options.content())).toString(), context);
            var approval = changed ? WorkspaceTextFiles.approval("write_file", target, original, options.content(),
                    original == null ? "create" : "overwrite") : null;
            return new PreparedToolOperation(approval, () -> {
                WorkspaceTextFiles.commitPrepared(target, original, options.content(), changed);
                return output;
            });
        } catch (IOException failed) {
            WorkspaceTextFiles.checkInterrupted();
            throw WorkspaceTextFiles.failure(failed);
        }
    }

    /** 定义由注册表通过 tools 注入模型，不另建提示词版参数文档。 */
    private static ToolDefinition createDefinition() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("minLength", 1).put("description", "相对工作区的文件路径；不接受链接或越界路径");
        properties.putObject("content").put("type", "string").put("maxLength", WorkspaceTextFiles.MAX_INPUT_BYTES)
                .put("description", "完整文件内容，可为空；UTF-8 最多 64 KiB，完整参数还受执行器长度限制");
        properties.putObject("overwrite").put("type", "boolean").put("default", false)
                .put("description", "默认 false，已有文件报错；true 表示请求整体覆盖，不代表用户授权");
        schema.putArray("required").add("path").add("content");
        schema.put("additionalProperties", false);
        return new ToolDefinition("write_file", "新建 UTF-8 文本文件并自动创建缺失父目录，或 overwrite=true 时整体重写已有文本文件。"
                + "必须提供完整内容；修改已有文件前先读取，局部修改优先 edit_file。单次内容最多 64 KiB，已有文件最多 4 MiB。", schema);
    }

    /** 显式 null 和字符串布尔值均报错，空 content 合法，空 path 非法。 */
    private static Options options(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || !arguments.path("path").isTextual()
                || !arguments.path("content").isTextual()) throw invalid("write_file requires string path and content.");
        arguments.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) throw invalid("Unsupported write_file argument."); });
        JsonNode overwrite = arguments.get("overwrite");
        if (overwrite != null && !overwrite.isBoolean()) throw invalid("overwrite must be a boolean.");
        return new Options(WorkspaceTextFiles.relativePath(arguments.path("path").asText()),
                WorkspaceTextFiles.inputBytes(arguments.path("content").asText()), overwrite != null && overwrite.booleanValue());
    }

    /** 已校验的相对路径、UTF-8 完整字节及覆盖意图。 */
    private record Options(Path path, byte[] content, boolean overwrite) { }
    /** 参数错误不回显模型传入的正文。 */
    private static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, message); }
}
