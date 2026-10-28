package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.policy.PermissionMode;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实 CLI、工具、审批和持久化，仅替换模型。 */
class ProjectInitCliTest {
    @TempDir Path directory;
    private final List<LlmRequest> requests = new ArrayList<>();
    private final StringWriter output = new StringWriter(), errors = new StringWriter();
    private int initStep;
    private boolean editExisting;
    private String run(String commands, PermissionSettings permissions, boolean noWorkspace) throws Exception {
        var data = directory.resolve("data");
        var store = new JsonSessionStore(data.resolve("sessions"));
        LlmClient client = request -> {
            requests.add(request);
            if (request.messages().get(0).content().contains("项目规则初始化任务")) {
                initStep++;
                if (initStep == 1) return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("read", editExisting ? "read_file" : "list_files", editExisting ? "{\"path\":\"AGENTS.md\"}" : "{}"))), "tool_calls", null);
                if (initStep == 2) {
                    if (editExisting) {
                        var args = new ObjectMapper().createObjectNode().put("path", "AGENTS.md")
                                .put("oldText", "OLD_COMMAND").put("newText", "mvn test");
                        return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("edit", "edit_file", args.toString()))), "tool_calls", null);
                    }
                    var args = new ObjectMapper().createObjectNode().put("path", "AGENTS.md").put("content", "# Project\nUNIQUE_INIT_RULE\nBuild: mvn test\n");
                    return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("write", "write_file", args.toString()))), "tool_calls", null);
                }
            }
            return new LlmResponse(Message.assistant("done"), "stop", null);
        };
        var writer = new PrintWriter(output);
        int status = new SessionLauncher(directory, new SessionSettings(data, null, false), store,
                selection -> new SessionLauncher.ModelChoice("fixture demo", client, client), () -> "base",
                new ConsoleInput(new BufferedReader(new StringReader(commands)), writer), writer, new PrintWriter(errors),
                new SessionLauncher.Options(noWorkspace ? null : directory.toString(), noWorkspace, "fixture", null, null),
                new ToolRegistry().register(new ListFilesTool()).register(new ReadFileTool()).register(new WriteFileTool()).register(new EditFileTool()),
                DebugPrinter.NONE, List::of, 20, permissions).run();
        assertEquals(0, status);
        return store.list().sessions().isEmpty() ? null : store.list().sessions().get(0).id();
    }
    @Test void initWritesWithApprovalAndSubsequentTaskUsesRulesWithoutInitPrompt() throws Exception {
        String id = run("/init\ny\n/context\nhello\n/exit\n", new PermissionSettings(PermissionMode.ASK, true), false);
        assertTrue(Files.readString(directory.resolve("AGENTS.md")).contains("UNIQUE_INIT_RULE"));
        assertEquals(4, requests.size());
        assertTrue(requests.get(2).messages().get(0).content().contains("UNIQUE_INIT_RULE"));
        assertTrue(requests.get(2).messages().stream().filter(m -> m.role() == MessageRole.TOOL)
                .anyMatch(m -> m.content().contains("bytesWritten")));
        var last = requests.get(3);
        assertFalse(last.messages().get(0).content().contains("项目规则初始化任务"));
        assertEquals(1, last.messages().get(0).content().split("UNIQUE_INIT_RULE", -1).length - 1);
        assertTrue(output.toString().contains("待批准：write_file"));
        assertTrue(output.toString().contains("项目规则及标记"));
        assertTrue(errors.toString().isEmpty(), errors.toString());
        var stored = new JsonSessionStore(directory.resolve("data/sessions")).load(id);
        assertTrue(stored.messages().stream().noneMatch(m -> m.content() != null && m.content().contains("项目规则初始化任务")));
        assertTrue(stored.messages().stream().noneMatch(m -> m.role() == MessageRole.SYSTEM));
    }
    @Test void deniedWriteDoesNotCreateRuleFileAndReportsMissing() throws Exception {
        run("/init\nn\n/exit\n", new PermissionSettings(PermissionMode.ASK, true), false);
        assertFalse(Files.exists(directory.resolve("AGENTS.md")));
        assertTrue(requests.get(2).messages().stream().filter(m -> m.role() == MessageRole.TOOL)
                .anyMatch(m -> m.content().contains("PERMISSION_DENIED")));
        assertTrue(output.toString().contains("初始化检查：项目规则：MISSING"));
    }
    @Test void planNeverExposesWriteAndEnforcesReadOnlyEvenWhenModelRequestsWrite() throws Exception {
        run("/mode plan\n/init\n/exit\n", new PermissionSettings(), false);
        assertFalse(Files.exists(directory.resolve("AGENTS.md")));
        assertTrue(requests.stream().allMatch(r -> r.tools().stream().noneMatch(t -> t.name().equals("write_file"))));
        assertTrue(requests.get(2).messages().stream().filter(m -> m.role() == MessageRole.TOOL)
                .anyMatch(m -> m.content().contains("PLAN")));
    }
    @Test void noWorkspaceInitDoesNotCallModel() throws Exception {
        run("/init\n/exit\n", new PermissionSettings(), true);
        assertTrue(requests.isEmpty());
        assertTrue(errors.toString().contains("请先选择项目工作区"));
    }
    @Test void existingRuleIsEditedWithApprovalAndUserContentPreserved() throws Exception {
        editExisting = true;
        Files.writeString(directory.resolve("AGENTS.md"), "USER_OWNED_RULE\nOLD_COMMAND");
        run("/init\ny\n/exit\n", new PermissionSettings(PermissionMode.ASK, true), false);
        assertEquals("USER_OWNED_RULE\nmvn test", Files.readString(directory.resolve("AGENTS.md")));
        assertTrue(output.toString().contains("待批准：edit_file"));
        assertTrue(requests.get(2).messages().get(0).content().contains("USER_OWNED_RULE\nmvn test"));
        assertFalse(requests.get(2).messages().get(0).content().contains("OLD_COMMAND"));
    }
    @Test void existingFileIsNotBlindlyOverwritten() throws Exception {
        Files.writeString(directory.resolve("AGENTS.md"), "USER_OWNED_RULE");
        run("/init\n/exit\n", new PermissionSettings(), false);
        assertEquals("USER_OWNED_RULE", Files.readString(directory.resolve("AGENTS.md")));
        assertTrue(requests.get(2).messages().stream().filter(m -> m.role() == MessageRole.TOOL)
                .anyMatch(m -> m.content().contains("FILE_ALREADY_EXISTS")));
        assertTrue(requests.get(0).messages().get(0).content().contains("USER_OWNED_RULE"));
    }
}
