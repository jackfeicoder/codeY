package io.github.jackfeicoder.codey.agent;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.context.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 预算在真实循环的每次模型请求前生效，不能误报请求次数、重放工具或修改历史。 */
class ContextBudgetAgentTest {
    @TempDir Path directory;
    private final ContextBuilder builder = new ContextBuilder();

    @Test void blocksFirstRequestBeforeClientAndStreamCallbacksWithoutSavingUser() {
        var calls = new AtomicInteger(); var starts = new AtomicInteger(); var snapshots = new ArrayList<ContextSnapshot>();
        var session = new Session("base"); var output = new StringWriter();
        var policy = new ContextBudgetPolicy(new ModelCapabilities(10000L), new ContextBudgetSettings(1, 0, new BigDecimal("0.8")));
        var loop = new AgentLoop(request -> { calls.incrementAndGet(); throw new AssertionError("No HTTP expected"); },
                new ToolRegistry(), 20, new DebugPrinter(new PrintWriter(output)), builder, snapshots::add, policy);
        var listener = new AgentStreamListener() {
            public void onText(String text) { fail("No text expected"); }
            public void onResponseStart(int step) { starts.incrementAndGet(); }
        };
        var limited = assertThrows(ContextLimitException.class, () -> loop.run(session, "x".repeat(40000), new ToolContext(WorkspaceBinding.none()), listener));
        assertEquals(0, limited.modelCalls()); assertEquals(0, limited.toolCalls());
        assertEquals(0, calls.get()); assertEquals(0, starts.get()); assertEquals(1, snapshots.size());
        assertEquals(List.of(Message.system("base")), session.messages()); assertFalse(output.toString().contains("请求模型"));
    }

    @Test void toolCallArgumentsCanExceedNextBudgetAfterWriteAndDoNotRollbackOrCommitPartialTurn() throws Exception {
        var workspace = new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toString());
        var context = new ToolContext(workspace, ToolContext.DEFAULT_MAX_OUTPUT_CHARS, WorkMode.REACT,
                new PermissionService(PermissionMode.FULL, workspace, ApprovalHandler.unavailable()));
        String body = "x".repeat(30000);
        var tool = new ToolCall("write-1", "write_file", "{\"path\":\"created.txt\",\"content\":\"" + body + "\"}");
        var calls = new AtomicInteger(); var session = new Session("base");
        session.completeTurn("old", Message.assistant("old answer")); var original = session.messages();
        LlmClient client = request -> {
            assertEquals(1, calls.incrementAndGet());
            return new LlmResponse(Message.assistantToolCalls(List.of(tool)), "tool_calls", null);
        };
        var snapshots = new ArrayList<ContextSnapshot>(); var trace = new StringWriter();
        var loop = new AgentLoop(client, new ToolRegistry().register(new WriteFileTool()), 20, new DebugPrinter(new PrintWriter(trace)),
                builder, snapshots::add, new ContextBudgetPolicy(new ModelCapabilities(7000L), new ContextBudgetSettings(1000, 1000, new BigDecimal("0.8"))));
        var limited = assertThrows(ContextLimitException.class, () -> loop.run(session, "write fixture", context));
        assertEquals(1, limited.modelCalls()); assertEquals(1, limited.toolCalls()); assertEquals(1, calls.get());
        assertEquals(2, snapshots.size()); assertTrue(snapshots.get(1).tokens().assistant() > 9000);
        assertEquals(body, Files.readString(directory.resolve("created.txt")));
        assertEquals(original, session.messages()); assertFalse(trace.toString().contains("[第 2 轮] 请求模型"));
    }

    @Test void checksReadResultsAndWarnsOnlyOnceWhileContinuingToolLoop() throws Exception {
        Files.writeString(directory.resolve("a.txt"), "small");
        var context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toString()));
        var registry = new ToolRegistry().register(new ReadFileTool());
        var session = new Session("base"); String question = "x".repeat(10000);
        long initial = builder.build(session.messages(), List.of(Message.user(question)), registry.definitions(context), WorkMode.REACT, 1, 20).tokens().total();
        var policy = new ContextBudgetPolicy(new ModelCapabilities(initial + 1001), new ContextBudgetSettings(1, 0, new BigDecimal("0.5")));
        var kinds = new ArrayList<DebugPrinter.Kind>(); var calls = new AtomicInteger(); var snapshots = new ArrayList<ContextSnapshot>();
        LlmClient client = request -> {
            int call = calls.incrementAndGet();
            if (call <= 2) return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("read-" + call, "read_file", "{\"path\":\"a.txt\"}"))), "tool_calls", null);
            return new LlmResponse(Message.assistant("done"), "stop", null);
        };
        var result = new AgentLoop(client, registry, 20, new DebugPrinter((kind, text) -> kinds.add(kind)), builder, snapshots::add, policy)
                .run(session, question, context);
        assertTrue(result.completed()); assertEquals(3, calls.get()); assertEquals(2, result.toolCalls());
        assertEquals(1, kinds.stream().filter(kind -> kind == DebugPrinter.Kind.WARNING).count());
        assertTrue(snapshots.get(2).tokens().toolResults() > snapshots.get(1).tokens().toolResults());
        assertEquals(1, session.messages().size());
    }

    @Test void oversizedReadResultStopsNextRequestAndUnknownCapacitySkipsLocalLimit() throws Exception {
        Files.writeString(directory.resolve("large.txt"), "中".repeat(4000));
        var context = new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toString()));
        var calls = new AtomicInteger(); var session = new Session("base");
        LlmClient client = request -> {
            if (calls.incrementAndGet() == 1) return new LlmResponse(Message.assistantToolCalls(List.of(new ToolCall("read", "read_file", "{\"path\":\"large.txt\"}"))), "tool_calls", null);
            return new LlmResponse(Message.assistant("done"), "stop", null);
        };
        var registry = new ToolRegistry().register(new ReadFileTool());
        var loop = new AgentLoop(client, registry, 20, DebugPrinter.NONE, builder, snapshot -> { },
                new ContextBudgetPolicy(new ModelCapabilities(7000L), new ContextBudgetSettings(1000, 1000, new BigDecimal("0.8"))));
        var limited = assertThrows(ContextLimitException.class, () -> loop.run(session, "read", context));
        assertEquals(1, calls.get()); assertEquals(1, limited.toolCalls());
        calls.set(0);
        assertTrue(new AgentLoop(client, registry, 20).run(session, "read", context).completed());
        assertEquals(2, calls.get());
    }
}
