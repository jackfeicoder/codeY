package io.github.jackfeicoder.codey.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.llm.LlmClient;
import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmRequest;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.session.Session;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.ReadFileTool;
import io.github.jackfeicoder.codey.tool.builtin.ListFilesTool;
import io.github.jackfeicoder.codey.tool.builtin.SearchFileTool;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 可控模型替身验证决定行动、执行、观察、退出和会话隔离，文件使用临时工作区。 */
class AgentLoopTest {
    /** 测试文件专用目录，不接触项目 .env。 */
    @TempDir Path directory;
    /** 解析工具观察结果。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 模型先列出未知位置的文件，再使用返回路径读取；观察与调用 ID 完整回填。 */
    @Test
    void discoversFilesThenReadsTheirContentThroughThreeModelRounds() throws Exception {
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve("config/App.java"), "class App {}\n");
        var requests = new ArrayList<LlmRequest>();
        var registry = new ToolRegistry().register(new ListFilesTool()).register(new ReadFileTool());
        LlmClient client = request -> {
            requests.add(request);
            if (requests.size() == 1) return calls(new ToolCall("listing", "list_files", "{}"));
            if (requests.size() == 2) {
                var observed = request.messages().get(request.messages().size() - 1);
                assertEquals("listing", observed.toolCallId());
                try {
                    var listing = MAPPER.readTree(MAPPER.readTree(observed.content()).path("output").asText());
                    String found = listing.path("entries").get(1).path("path").asText();
                    assertEquals("config/App.java", found);
                    return calls(new ToolCall("reading", "read_file", MAPPER.createObjectNode().put("path", found).toString()));
                } catch (java.io.IOException malformed) { throw new AssertionError(malformed); }
            }
            assertTrue(request.messages().get(request.messages().size() - 1).content().contains("class App {}"));
            return answer("The config file contains class App.");
        };
        var session = new Session("base");
        var result = new AgentLoop(client, registry).run(session, "Locate and explain configuration",
                new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString())));
        assertTrue(result.completed());
        assertEquals(3, result.modelCalls());
        assertEquals(2, result.toolCalls());
        assertEquals(2, requests.get(0).tools().size());
        session.completeAgentTurn(result.turnMessages());
        assertEquals(7, session.messages().size());
    }

    /** 模型按名称直接定位未知文件，再读取并回答；进度只打印搜索范围与模式，不打印正文。 */
    @Test
    void findsFileByNameThenReadsWithoutListingDirectories() throws Exception {
        Files.createDirectories(directory.resolve("src/config"));
        Files.writeString(directory.resolve("src/config/AppConfig.java"), "class AppConfig {} // fixture-body\n");
        var requests = new ArrayList<LlmRequest>();
        var output = new StringWriter();
        var registry = new ToolRegistry().register(new ListFilesTool()).register(new SearchFileTool()).register(new ReadFileTool());
        LlmClient client = request -> {
            requests.add(request);
            if (requests.size() == 1) return calls(new ToolCall("locate", "search_file", "{\"pattern\":\"*Config*.java\",\"path\":\"src\"}"));
            if (requests.size() == 2) {
                var observed = request.messages().get(request.messages().size() - 1);
                assertEquals("locate", observed.toolCallId());
                try {
                    var found = MAPPER.readTree(MAPPER.readTree(observed.content()).path("output").asText());
                    assertEquals(1, found.path("returnedFiles").asInt());
                    String file = found.path("files").get(0).asText();
                    assertEquals("src/config/AppConfig.java", file);
                    return calls(new ToolCall("read", "read_file", MAPPER.createObjectNode().put("path", file).toString()));
                } catch (java.io.IOException malformed) { throw new AssertionError(malformed); }
            }
            assertTrue(request.messages().get(request.messages().size() - 1).content().contains("fixture-body"));
            return answer("AppConfig is the configuration entry.");
        };
        var session = new Session("base");
        var result = new AgentLoop(client, registry, 20, new DebugPrinter(new PrintWriter(output)))
                .run(session, "Locate config", workspace());
        assertTrue(result.completed());
        assertEquals(3, result.modelCalls());
        assertEquals(2, result.toolCalls());
        assertEquals(3, requests.get(0).tools().size());
        assertTrue(output.toString().contains("调用 search_file · src · *Config*.java"));
        assertFalse(output.toString().contains("调用 list_files"));
        assertFalse(output.toString().contains("fixture-body"));
        session.completeAgentTurn(result.turnMessages());
        assertEquals(7, session.messages().size());
    }

    /** 控制台只展示编号轮次、工具及文件，完整上下文和工具正文仍留在模型请求与结果中。 */
    @Test
    void progressShowsRoundsToolsAndFilesWithoutDumpingContextOrResults() {
        Session session = new Session("base-fragment-".repeat(1500) + "base-tail");
        session.completeTurn("old question", Message.assistant("old answer"));
        StringWriter output = new StringWriter();
        AtomicInteger steps = new AtomicInteger();
        AgentResult result = new AgentLoop(request -> steps.incrementAndGet() == 1
                ? calls(new ToolCall("probe-id", "probe", "{\"path\":\"src/config/ConfigLoader.java\"}")) : answer("final answer"),
                new ToolRegistry().register(new Probe(false)), 8, new DebugPrinter(new PrintWriter(output)))
                .run(session, "new question", none());
        String log = output.toString();
        assertTrue(log.contains("[第 1 轮] 请求模型"));
        assertTrue(log.contains("[第 2 轮] 请求模型"));
        assertTrue(log.contains("调用 probe · src/config/ConfigLoader.java"));
        assertEquals(3, log.lines().count());
        assertFalse(log.contains("base-fragment"));
        assertFalse(log.contains("old question"));
        assertFalse(log.contains("old answer"));
        assertFalse(log.contains("new question"));
        assertFalse(log.contains("fixture tool"));
        assertFalse(log.contains("probe-id"));
        assertFalse(log.contains("observation"));
        assertFalse(log.contains("final answer"));
        assertEquals("final answer", result.response().text());
        assertTrue(result.turnMessages().get(2).content().contains("observation"));
        assertEquals(3, session.messages().size());
        assertEquals(4, result.turnMessages().size());
    }

    /** 普通回答只请求一次，旧历史不重复放进本轮结果，循环本身不修改 Session。 */
    @Test
    void completesPlainAnswerWithoutMutatingSession() {
        Session session = new Session("base");
        session.completeTurn("old", Message.assistant("old answer"));
        AgentResult result = new AgentLoop(request -> {
            assertEquals(4, request.messages().size());
            assertTrue(request.tools().isEmpty());
            return answer("new answer");
        }, new ToolRegistry()).run(session, "new", none());
        assertTrue(result.completed());
        assertEquals(1, result.modelCalls());
        assertEquals(0, result.toolCalls());
        assertEquals(List.of(Message.user("new"), Message.assistant("new answer")), result.turnMessages());
        assertEquals(3, session.messages().size());
        session.completeAgentTurn(result.turnMessages());
        assertEquals(5, session.messages().size());
    }

    /** 真实读取后下一轮模型看到完整结果，工具说明每次通过 tools 发送。 */
    @Test
    void readsFileAndReturnsObservationToNextModelRequest() throws Exception {
        Files.writeString(directory.resolve("README.md"), "CodeY\nJava Agent");
        List<LlmRequest> requests = new ArrayList<>();
        LlmClient model = request -> {
            requests.add(request);
            assertEquals("read_file", request.tools().get(0).name());
            if (requests.size() == 1) return calls(new ToolCall("read-1", "read_file", "{\"path\":\"README.md\"}"));
            try {
                var messages = request.messages();
                assertNull(messages.get(2).content());
                assertEquals("read-1", messages.get(3).toolCallId());
                JsonNode result = MAPPER.readTree(messages.get(3).content());
                assertEquals("success", result.path("status").asText());
                assertEquals("Java Agent", MAPPER.readTree(result.path("output").asText()).at("/lines/1/text").asText());
            } catch (Exception error) { throw new AssertionError(error); }
            return answer("这是 Java Agent 项目");
        };
        Session session = new Session("base");
        AgentResult result = new AgentLoop(model, new ToolRegistry().register(new ReadFileTool()))
                .run(session, "读取 README", workspace());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
        assertEquals(4, result.turnMessages().size());
        assertThrows(UnsupportedOperationException.class, () -> result.turnMessages().clear());
        session.completeAgentTurn(result.turnMessages());
        assertEquals(5, session.messages().size());
    }

    /** 有结束标记却无正文的响应不是完成；工具观察不会和空回答一起提交，也不自动重放。 */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\n\t", "\u3000"})
    void emptyFinalAnswerAfterToolDoesNotCompleteOrChangeHistory(String text) {
        var session = new Session("base");
        session.completeTurn("previous", Message.assistant("previous answer"));
        var before = session.messages();
        var requests = new AtomicInteger();
        var tool = new Probe(false);
        var loop = new AgentLoop(request -> requests.incrementAndGet() == 1
                ? calls(new ToolCall("read", "probe", "{}")) : answer(text),
                new ToolRegistry().register(tool));
        var error = assertThrows(LlmException.class, () -> loop.run(session, "investigate", none()));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind());
        assertTrue(error.getMessage().contains("empty final answer"));
        assertEquals(2, requests.get());
        assertEquals(1, tool.executions);
        assertEquals(before, session.messages());
    }

    /** 最后一次收尾与 length 截断也必须有可展示内容，不保存空的预算总结。 */
    @ParameterizedTest
    @ValueSource(strings = {"stop", "length"})
    void blankBudgetSummaryIsAnInvalidResponse(String finishReason) {
        var session = new Session("base");
        var calls = new AtomicInteger();
        var loop = new AgentLoop(request -> {
            calls.incrementAndGet();
            assertFalse(request.toolCallsAllowed());
            return new LlmResponse(Message.assistant(" \n"), finishReason, null);
        }, new ToolRegistry(), 1);
        var error = assertThrows(LlmException.class, () -> loop.run(session, "q", none()));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind());
        assertTrue(error.getMessage().contains("finish_reason=" + finishReason));
        assertEquals(1, calls.get());
        assertEquals(List.of(Message.system("base")), session.messages());
    }

    /** 多个调用分别回填成功和失败，模型可修正参数继续读取。 */
    @Test
    void returnsAllResultsAndAllowsCorrection() throws Exception {
        Files.writeString(directory.resolve("file.txt"), "actual content");
        AtomicInteger steps = new AtomicInteger();
        LlmClient model = request -> {
            int step = steps.incrementAndGet();
            if (step == 1) return calls(new ToolCall("unknown", "not_registered", "{}"),
                    new ToolCall("invalid", "read_file", "{\"path\":\"file.txt\",\"startLine\":0}"));
            if (step == 2) {
                assertEquals(5, request.messages().size());
                assertTrue(request.messages().get(3).content().contains("UNKNOWN_TOOL"));
                assertTrue(request.messages().get(4).content().contains("INVALID_ARGUMENTS"));
                return calls(new ToolCall("fixed", "read_file", "{\"path\":\"file.txt\"}"));
            }
            assertTrue(request.messages().get(6).content().contains("actual content"));
            return answer("done");
        };
        AgentResult result = new AgentLoop(model, new ToolRegistry().register(new ReadFileTool()))
                .run(new Session("base"), "read", workspace());
        assertEquals(3, result.modelCalls());
        assertEquals(3, result.toolCalls());
        assertTrue(result.completed());
    }

    /** 普通聊天不向模型展示工作区工具，意外请求仍得到关联的工作区错误。 */
    @Test
    void hidesWorkspaceToolsWithoutImplicitDirectoryFallback() {
        AtomicInteger steps = new AtomicInteger();
        AgentResult result = new AgentLoop(request -> {
            assertTrue(request.tools().isEmpty());
            if (steps.incrementAndGet() == 1) return calls(new ToolCall("read", "read_file", "{\"path\":\"file.txt\"}"));
            assertTrue(request.messages().get(3).content().contains("WORKSPACE_REQUIRED"));
            return answer("请提供文件内容");
        }, new ToolRegistry().register(new ReadFileTool())).run(new Session("base"), "read", none());
        assertTrue(result.completed());
    }

    /** 次数达到上限后没有额外模型请求，中间轨迹不当作已完成会话。 */
    @Test
    void stopsExactlyAtBudgetAndLeavesHistoryUntouched() {
        AtomicInteger modelCalls = new AtomicInteger();
        Probe tool = new Probe(false);
        Session session = new Session("base");
        AgentResult result = new AgentLoop(request -> calls(new ToolCall("id-" + modelCalls.incrementAndGet(), "probe", "{}")),
                new ToolRegistry().register(tool), 2).run(session, "loop", none());
        assertEquals(AgentResult.Status.BUDGET_EXHAUSTED, result.status());
        assertNull(result.response());
        assertEquals(2, modelCalls.get());
        assertEquals(1, tool.executions); // 最后一次请求只允许回答，忽略服务端提出的工具。
        assertEquals(1, result.toolCalls());
        assertTrue(result.budgetLimited());
        assertEquals(3, result.turnMessages().size());
        assertThrows(IllegalArgumentException.class, () -> session.completeAgentTurn(result.turnMessages()));
        assertEquals(1, session.messages().size());
    }

    /** 最后一个允许的请求可以正常结束，不误判预算耗尽。 */
    @Test
    void finalAnswerOnLastAllowedStepCompletes() {
        AtomicInteger modelCalls = new AtomicInteger();
        AgentResult result = new AgentLoop(request -> modelCalls.incrementAndGet() == 1
                ? calls(new ToolCall("id-1", "probe", "{}")) : answer("done"),
                new ToolRegistry().register(new Probe(false)), 2).run(new Session("base"), "q", none());
        assertTrue(result.completed());
        assertEquals(2, result.modelCalls());
        assertTrue(result.budgetLimited());
    }

    /** 临近上限的提示仅用于请求，最后一次禁用工具；完整总结可保存，后续任务重新计数。 */
    @Test
    void reservesFinalRequestForSummaryAndKeepsBudgetHintsOutOfHistory() {
        var requests = new ArrayList<LlmRequest>();
        Probe tool = new Probe(false);
        Session session = new Session("base");
        AgentResult result = new AgentLoop(request -> {
            requests.add(request);
            if (requests.size() < 4) return calls(new ToolCall("id-" + requests.size(), "probe", "{}"));
            assertFalse(request.toolCallsAllowed());
            assertTrue(request.messages().get(0).content().contains("工具调用已禁用"));
            assertEquals("id-3", request.messages().get(7).toolCallId());
            return answer("已确认入口调用链，其他分支尚未确认。");
        }, new ToolRegistry().register(tool), 4).run(session, "investigate", none());
        assertEquals("base", requests.get(0).messages().get(0).content());
        assertTrue(requests.get(1).messages().get(0).content().contains("还剩 3 次"));
        assertTrue(requests.get(2).messages().get(0).content().contains("还剩 2 次"));
        assertTrue(requests.subList(0, 3).stream().allMatch(LlmRequest::toolCallsAllowed));
        assertEquals(3, tool.executions);
        assertTrue(result.completed());
        assertTrue(result.budgetLimited());
        assertEquals(4, result.modelCalls());
        assertEquals(8, result.turnMessages().size());
        assertTrue(result.turnMessages().stream().noneMatch(message ->
                message.content() != null && message.content().contains("运行时任务预算")));
        session.completeAgentTurn(result.turnMessages());
        new AgentLoop(request -> {
            assertTrue(request.toolCallsAllowed());
            assertEquals("base", request.messages().get(0).content());
            assertEquals(10, request.messages().size());
            return answer("continued");
        }, new ToolRegistry()).run(session, "continue", none());
    }

    /** 即使配置只有一次请求，也只接受文本，不执行服务商越界返回的工具。 */
    @Test
    void singleRequestBudgetNeverExecutesTool() {
        Probe tool = new Probe(false);
        var result = new AgentLoop(request -> {
            assertFalse(request.toolCallsAllowed());
            return calls(new ToolCall("unexpected", "probe", "{}"));
        }, new ToolRegistry().register(tool), 1).run(new Session("base"), "q", none());
        assertEquals(AgentResult.Status.BUDGET_EXHAUSTED, result.status());
        assertEquals(0, tool.executions);
        assertEquals(List.of(Message.user("q")), result.turnMessages());
    }

    /** 工具完成后网络失败也不把残缺 user/assistant/tool 提交到历史。 */
    @Test
    void modelFailureAfterToolDoesNotPolluteHistory() {
        AtomicInteger steps = new AtomicInteger();
        Probe tool = new Probe(false);
        Session session = new Session("base");
        session.completeTurn("old", Message.assistant("old answer"));
        AgentLoop loop = new AgentLoop(request -> {
            if (steps.incrementAndGet() == 1) return calls(new ToolCall("id-1", "probe", "{}"));
            throw new LlmException(LlmException.Kind.NETWORK, "fixture failure");
        }, new ToolRegistry().register(tool));
        assertThrows(LlmException.class, () -> loop.run(session, "failed", none()));
        assertEquals(1, tool.executions);
        assertEquals(3, session.messages().size());
    }

    /** 整组检查先于执行，截断的调用列表不能触发工具。 */
    @ParameterizedTest
    @ValueSource(strings = {"length", "stop", "unknown"})
    void rejectsToolGroupWithWrongFinishReason(String reason) {
        Probe tool = new Probe(false);
        AgentLoop loop = new AgentLoop(request -> new LlmResponse(
                Message.assistantToolCalls(List.of(new ToolCall("id", "probe", "{}"))), reason, null),
                new ToolRegistry().register(tool));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, assertThrows(LlmException.class,
                () -> loop.run(new Session("base"), "q", none())).kind());
        assertEquals(0, tool.executions);
    }

    /** 单响应工具数超限时整组拒绝，不执行前 16 项后才发现越界。 */
    @Test
    void rejectsOversizedGroupBeforeExecutingAnything() {
        Probe tool = new Probe(false);
        var calls = IntStream.range(0, 17).mapToObj(i -> new ToolCall("id-" + i, "probe", "{}")).toList();
        AgentLoop loop = new AgentLoop(request -> new LlmResponse(Message.assistantToolCalls(calls), "tool_calls", null),
                new ToolRegistry().register(tool));
        assertThrows(LlmException.class, () -> loop.run(new Session("base"), "q", none()));
        assertEquals(0, tool.executions);
    }

    /** 跨步骤重复 ID 会使结果关联含糊，第二组任何工具执行前就拒绝。 */
    @Test
    void rejectsDuplicateIdAcrossSteps() {
        Probe tool = new Probe(false);
        AtomicInteger steps = new AtomicInteger();
        AgentLoop loop = new AgentLoop(request -> steps.incrementAndGet() == 1
                ? calls(new ToolCall("id", "probe", "{}"))
                : calls(new ToolCall("new", "probe", "{}"), new ToolCall("id", "probe", "{}")),
                new ToolRegistry().register(tool));
        assertThrows(LlmException.class, () -> loop.run(new Session("base"), "q", none()));
        assertEquals(1, tool.executions);
    }

    /** 工具中断后不执行同组下一工具，也不请求下一轮模型；线程标志保留。 */
    @Test
    void toolInterruptionStopsImmediately() {
        Probe tool = new Probe(true);
        AtomicInteger requests = new AtomicInteger();
        try {
            AgentLoop loop = new AgentLoop(request -> {
                requests.incrementAndGet();
                return calls(new ToolCall("one", "probe", "{}"), new ToolCall("two", "probe", "{}"));
            }, new ToolRegistry().register(tool));
            assertEquals(LlmException.Kind.INTERRUPTED, assertThrows(LlmException.class,
                    () -> loop.run(new Session("base"), "q", none())).kind());
            assertEquals(1, tool.executions);
            assertEquals(1, requests.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    /** 已中断任务不发请求，模型调用期间到达的中断也不提交最终回答。 */
    @Test
    void respectsInterruptionBeforeAndAfterModelCall() {
        try {
            Thread.currentThread().interrupt();
            AgentLoop loop = new AgentLoop(request -> { fail("Interrupted task must not request model"); return answer("unused"); },
                    new ToolRegistry());
            assertThrows(LlmException.class, () -> loop.run(new Session("base"), "q", none()));
            Thread.interrupted();
            Session session = new Session("base");
            AgentLoop interrupted = new AgentLoop(request -> {
                Thread.currentThread().interrupt(); return answer("late");
            }, new ToolRegistry());
            assertThrows(LlmException.class, () -> interrupted.run(session, "q", none()));
            assertEquals(1, session.messages().size());
        } finally { Thread.interrupted(); }
    }

    /** 不支持的结束原因不是正常完成，长度截断文本保留原始标记。 */
    @Test
    void distinguishesUnsupportedFinishFromTruncatedText() {
        assertThrows(LlmException.class, () -> new AgentLoop(request ->
                new LlmResponse(Message.assistant("partial"), "unknown", null), new ToolRegistry())
                .run(new Session("base"), "q", none()));
        AgentResult result = new AgentLoop(request -> new LlmResponse(Message.assistant("partial"), "length", null),
                new ToolRegistry()).run(new Session("base"), "q", none());
        assertTrue(result.response().isTruncated());
    }

    /** 创建最终回答替身。 */
    private static LlmResponse answer(String text) { return new LlmResponse(Message.assistant(text), "stop", null); }
    /** 创建函数调用响应替身。 */
    private static LlmResponse calls(ToolCall... calls) {
        return new LlmResponse(Message.assistantToolCalls(List.of(calls)), "tool_calls", null);
    }
    /** 明确普通聊天不使用任何目录。 */
    private static ToolContext none() { return new ToolContext(WorkspaceBinding.none()); }
    /** 临时目录绑定真实路径。 */
    private ToolContext workspace() throws Exception {
        return new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, directory.toRealPath().toString()));
    }

    /** 计数测试工具，用于观察调用次数及中断传播。 */
    private static final class Probe implements Tool {
        /** 工具实际执行次数。 */
        private int executions;
        /** 是否在 execute 内模拟取消。 */
        private final boolean interrupt;
        /** 为本测试选择正常执行或取消行为。 */
        private Probe(boolean interrupt) { this.interrupt = interrupt; }
        /** 固定说明用于注册。 */
        public ToolDefinition definition() {
            return new ToolDefinition("probe", "fixture tool", MAPPER.createObjectNode().put("type", "object"));
        }
        /** 参数对象由执行器校验，该测试工具无业务参数。 */
        public void validateArguments(JsonNode arguments, ToolContext context) { }
        /** 记录执行并按测试选择中断，结果不依赖真实模型。 */
        public String execute(JsonNode arguments, ToolContext context) throws InterruptedException {
            executions++;
            if (interrupt) throw new InterruptedException();
            return "observation";
        }
    }
}
