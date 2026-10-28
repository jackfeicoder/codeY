package io.github.jackfeicoder.codey.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.memory.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolRegistry;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** 真实启动器和会话JSON；模型替身验证主任务先落盘，自动提取另走非流式。 */
class AutoMemoryCliTest {
    @TempDir Path root;
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final StringWriter output = new StringWriter(), errors = new StringWriter();
    private boolean failExtraction, failMain, interruptExtraction;
    private final JsonSessionStore store() { return new JsonSessionStore(root.resolve("data")); }
    private MemoryService memory() { return new MemoryService(new JsonMemoryStore(root.resolve("data")), new MemorySettings()); }
    private void run(String commands) throws Exception {
        LlmClient client = request -> {
            requests.add(request);
            if (request.messages().get(0).content().contains("# 长期记忆提取")) {
                var snapshots = store().list().sessions();
                assertFalse(snapshots.isEmpty()); assertEquals(2, snapshots.get(0).messages().size());
                if (interruptExtraction) throw new LlmException(LlmException.Kind.INTERRUPTED, "fixture");
                if (failExtraction) throw new LlmException(LlmException.Kind.HTTP, "PRIVATE_RAW_ERROR");
                String evidence;
                try { evidence = new ObjectMapper().readTree(request.messages().get(1).content()).path("userInput").asText(); }
                catch (Exception invalid) { throw new AssertionError(invalid); }
                var response = new ObjectMapper().createObjectNode();
                var entry = response.putArray("memories").addObject().put("action", "ADD").put("scope", "USER")
                        .put("title", "解释偏好").put("content", "讲Java时附带面试示例").put("evidence", evidence).putNull("relatedId");
                entry.putArray("tags").add("Java").add("面试");
                return new LlmResponse(Message.assistant(response.toString()), "stop", null);
            }
            if (failMain) throw new LlmException(LlmException.Kind.HTTP, "fixture main failure");
            return new LlmResponse(Message.assistant("主任务回答，记住这句话只是模型输出"), "stop", null);
        };
        int status = new SessionLauncher(root, new SessionSettings(root.resolve("data"), null, false), store(),
                selected -> new SessionLauncher.ModelChoice("fixture demo", client, client), () -> "base",
                new BufferedReader(new StringReader(commands)), new PrintWriter(output), new PrintWriter(errors),
                new SessionLauncher.Options(null, true, "fixture", null, null), new ToolRegistry()).run();
        assertEquals(0, status);
    }
    @Test void successfulTaskSavesBeforeExtractionAndRecallWorksOnNextQuestion() throws Exception {
        run("以后解释Java时附带面试示例\nJava线程池\n/memory\n/exit\n");
        assertEquals(3, requests.size());
        assertNull(requests.get(0).maxOutputTokens()); assertNotNull(requests.get(1).maxOutputTokens());
        assertTrue(requests.get(2).messages().get(3).content().contains("讲Java时附带面试示例"));
        var saved = store().list().sessions().get(0);
        assertEquals(4, saved.messages().size());
        assertTrue(saved.messages().stream().noneMatch(m -> m.content() != null && m.content().contains("# 长期记忆提取")));
        assertEquals(1, memory().list(WorkspaceBinding.none()).size());
        assertTrue(output.toString().contains("新增 1 条按需记忆")); assertEquals("", errors.toString());
    }
    @Test void ordinaryNegatedQuotedQuestionsAndRememberCommandDoNotTriggerExtraModelRequests() throws Exception {
        run("你好\n不要记住这次信息\n你能记住吗？\n“以后都用中文回答”是什么意思\n/remember --user 手动记忆\n/exit\n");
        assertEquals(4, requests.size()); assertTrue(requests.stream().allMatch(r -> r.maxOutputTokens() == null));
        assertEquals(1, memory().list(WorkspaceBinding.none()).size());
    }
    @Test void extractionFailureKeepsSuccessfulTurnAndAllowsNextInput() throws Exception {
        failExtraction = true;
        run("以后解释Java时附带面试示例\nhello\n/exit\n");
        assertEquals(3, requests.size()); assertEquals(4, store().list().sessions().get(0).messages().size());
        assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
        assertTrue(output.toString().contains("主任务已保存"));
        assertFalse(output.toString().contains("PRIVATE_RAW_ERROR")); assertFalse(errors.toString().contains("This turn was not saved"));
    }
    @Test void failedMainNeverStartsExtractionOrCreatesMemory() throws Exception {
        failMain = true;
        run("以后解释Java时附带面试示例\n/exit\n");
        assertEquals(1, requests.size()); assertTrue(memory().list(WorkspaceBinding.none()).isEmpty());
        assertTrue(store().list().sessions().isEmpty()); assertFalse(Files.exists(root.resolve("data/memory/extractions")));
    }
    @Test void restoringHistoryDoesNotReplayOldExtraction() throws Exception {
        run("以后解释Java时附带面试示例\n/exit\n");
        var snapshot = store().list().sessions().get(0);
        var count = requests.size();
        LlmClient unexpected = request -> { throw new AssertionError("resume must not request model"); };
        new SessionLauncher(root, new SessionSettings(root.resolve("data"), null, false), store(),
                selected -> new SessionLauncher.ModelChoice("fixture demo", unexpected, unexpected), () -> "base",
                new BufferedReader(new StringReader("/context\n/exit\n")), new PrintWriter(output), new PrintWriter(errors),
                new SessionLauncher.Options(null, false, null, snapshot.id(), null), new ToolRegistry()).run();
        assertEquals(count, requests.size()); assertEquals(1, memory().list(WorkspaceBinding.none()).size());
    }
    @Test void interruptDuringExtractionPreservesMainAndEndsWithoutReadingAnotherTask() throws Exception {
        interruptExtraction = true;
        try {
            run("以后解释Java时附带面试示例\nhello\n/exit\n");
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(2, requests.size());
            assertEquals(2, store().list().sessions().get(0).messages().size());
        } finally { Thread.interrupted(); }
    }
}
