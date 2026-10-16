package io.github.jackfeicoder.codey.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.filesystem.WorkspaceTextFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** 对已有 UTF-8 文件做大小写/空白/换行敏感的精确片段替换；不解析正则或行号。 */
public final class EditFileTool implements Tool {
    /** 参数 schema 与成功结果编码器，不保存文件正文或会话状态。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** replaceAll 必须显式开启，默认要求唯一匹配。 */
    private static final Set<String> FIELDS = Set.of("path", "oldText", "newText", "replaceAll");
    /** 注册时固定的用途和参数 schema。 */
    private static final ToolDefinition DEFINITION = createDefinition();

    /** 返回模型可见的稳定工具定义。 */
    @Override public ToolDefinition definition() { return DEFINITION; }
    /** 局部编辑只能在宿主绑定的项目工作区执行。 */
    @Override public boolean requiresWorkspace() { return true; }
    /** 校验实际参数；文件存在性和匹配情况在 execute 中检查。 */
    @Override public void validateArguments(JsonNode arguments, ToolContext context) { options(arguments); }

    /** 完整读取 → 计数/限额 → 构造新文本 → 检查结果预算 → 冲突检查与提交。 */
    @Override public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
        WorkspaceTextFiles.checkInterrupted();
        Options options = options(arguments);
        try {
            var target = WorkspaceTextFiles.target(context, options.path());
            var original = WorkspaceTextFiles.snapshot(target);
            if (original == null) throw new ToolException(ToolResult.ErrorCode.FILE_NOT_FOUND, "edit_file requires an existing file.");
            String text = original.text();
            int count = 0;
            for (int from = 0, match; (match = text.indexOf(options.oldText(), from)) >= 0; from = match + options.oldText().length()) {
                WorkspaceTextFiles.checkInterrupted();
                count++;
                if (!options.replaceAll() && count > 1) throw new ToolException(ToolResult.ErrorCode.AMBIGUOUS_MATCH,
                        "oldText matches more than once. Include more surrounding text or explicitly set replaceAll=true.");
            }
            if (count == 0) throw new ToolException(ToolResult.ErrorCode.TEXT_NOT_FOUND,
                    "oldText was not found exactly. Read the current file and preserve whitespace and line endings.");
            long length = text.length() + (long) count * (options.newText().length() - options.oldText().length());
            if (length > WorkspaceTextFiles.MAX_FILE_BYTES) throw new ToolException(ToolResult.ErrorCode.FILE_TOO_LARGE, "Edited text exceeds the file limit.");
            byte[] replacement = WorkspaceTextFiles.fileBytes(text.replace(options.oldText(), options.newText()));
            String output = WorkspaceTextFiles.checkedOutput(MAPPER.createObjectNode().put("path", target.displayPath())
                    .put("operation", "edit").put("changed", true).put("replacements", count)
                    .put("bytesWritten", replacement.length).put("fileBytes", replacement.length)
                    .put("sha256", WorkspaceTextFiles.sha256(replacement)).toString(), context);
            WorkspaceTextFiles.commit(target, original, replacement);
            return output;
        } catch (IOException failed) {
            WorkspaceTextFiles.checkInterrupted();
            throw WorkspaceTextFiles.failure(failed);
        }
    }

    /** schema 描述实际字面替换语义；添加内容需要把锚点一起保留在 newText 中。 */
    private static ToolDefinition createDefinition() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("path").put("type", "string").put("minLength", 1).put("description", "相对工作区的已有文本文件路径");
        properties.putObject("oldText").put("type", "string").put("minLength", 1).put("maxLength", WorkspaceTextFiles.MAX_INPUT_BYTES)
                .put("description", "非空旧片段，大小写、空白、换行必须完全匹配；默认须唯一，UTF-8 最多 64 KiB");
        properties.putObject("newText").put("type", "string").put("maxLength", WorkspaceTextFiles.MAX_INPUT_BYTES)
                .put("description", "新片段；空字符串表示删除，插入需保留旧片段作为锚点；UTF-8 最多 64 KiB");
        properties.putObject("replaceAll").put("type", "boolean").put("default", false)
                .put("description", "true 替换全部非重叠匹配，默认 false 要求唯一匹配");
        schema.putArray("required").add("path").add("oldText").add("newText");
        schema.put("additionalProperties", false);
        return new ToolDefinition("edit_file", "局部编辑已有 UTF-8 文本文件，精确替换 oldText 为 newText；可修改、以锚点插入或删除。"
                + "编辑前先 read_file 获取当前内容；不支持正则、模糊匹配或新建，文件最多 4 MiB。", schema);
    }

    /** 空旧片段或完全相同的替换没有明确编辑意图，直接拒绝。 */
    private static Options options(JsonNode arguments) {
        if (arguments == null || !arguments.isObject() || !arguments.path("path").isTextual()
                || !arguments.path("oldText").isTextual() || !arguments.path("newText").isTextual()) throw invalid("edit_file requires string path, oldText and newText.");
        arguments.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) throw invalid("Unsupported edit_file argument."); });
        String oldText = arguments.path("oldText").asText();
        String newText = arguments.path("newText").asText();
        if (oldText.isEmpty() || oldText.equals(newText)) throw invalid("oldText must be nonempty and different from newText.");
        JsonNode all = arguments.get("replaceAll");
        if (all != null && !all.isBoolean()) throw invalid("replaceAll must be a boolean.");
        WorkspaceTextFiles.inputBytes(oldText);
        WorkspaceTextFiles.inputBytes(newText);
        return new Options(WorkspaceTextFiles.relativePath(arguments.path("path").asText()), oldText, newText, all != null && all.booleanValue());
    }

    /** 一次编辑的不可变参数，不保存跨轮次文件状态。 */
    private record Options(Path path, String oldText, String newText, boolean replaceAll) { }
    /** 参数错误不回显模型传入的编辑片段。 */
    private static ToolException invalid(String message) { return new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS, message); }
}
