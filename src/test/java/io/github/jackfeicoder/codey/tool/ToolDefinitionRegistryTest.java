package io.github.jackfeicoder.codey.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证工具元数据的不可变性、重复注册、稳定顺序与工作区筛选，不执行实际文件操作。 */
class ToolDefinitionRegistryTest {
    /** 工作区筛选只使用 JUnit 临时目录。 */
    @TempDir Path directory;

    /** 构造参数和读取 schema 都不会暴露内部可变节点。 */
    @Test
    void schemaIsDefensivelyCopied() {
        ObjectNode schema = schema();
        ToolDefinition definition = new ToolDefinition("echo", "返回输入", schema);
        schema.put("type", "array");
        ((ObjectNode) definition.parameters()).put("type", "string");
        assertEquals("object", definition.parameters().path("type").asText());
    }

    /** 无效名称、空说明或非对象参数 schema 不能注册为工具定义。 */
    @Test
    void rejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("bad name", "description", schema()));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("x".repeat(65), "description", schema()));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("echo", " ", schema()));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("echo", "description", null));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinition("echo", "description",
                JsonNodeFactory.instance.objectNode().put("type", "array")));
    }

    /** 稳定名称绑定实现；重复注册失败，已经返回的定义列表不随新增工具改变。 */
    @Test
    void registryRejectsDuplicatesAndReturnsOrderedSnapshots() {
        ToolRegistry registry = new ToolRegistry().register(tool("first", false));
        List<ToolDefinition> before = registry.definitions();
        assertThrows(IllegalArgumentException.class, () -> registry.register(tool("first", false)));
        registry.register(tool("second", false));
        assertEquals(List.of("first", "second"), registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals(1, before.size());
        assertThrows(UnsupportedOperationException.class, before::clear);
        assertTrue(registry.find("missing").isEmpty());
    }

    /** 普通聊天隐藏项目工具，有有效目录时才展示其定义。 */
    @Test
    void definitionsReflectHostWorkspaceCapabilities() throws Exception {
        ToolRegistry registry = new ToolRegistry().register(tool("echo", false)).register(tool("project_tool", true));
        assertEquals(List.of("echo"), registry.definitions(new ToolContext(WorkspaceBinding.none()))
                .stream().map(ToolDefinition::name).toList());
        ToolContext context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,
                directory.toRealPath().toString()));
        assertEquals(2, registry.definitions(context).size());
    }

    /** 最小对象 schema，具体参数将在内置工具实现时定义。 */
    private static ObjectNode schema() {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
    }

    /** 返回仅用于注册测试的工具，不加入实际 CLI。 */
    private static Tool tool(String name, boolean workspace) {
        return new Tool() {
            public ToolDefinition definition() { return new ToolDefinition(name, "测试工具", schema()); }
            public boolean requiresWorkspace() { return workspace; }
            public void validateArguments(JsonNode arguments, ToolContext context) { }
            public String execute(JsonNode arguments, ToolContext context) { return "fixture"; }
        };
    }
}
