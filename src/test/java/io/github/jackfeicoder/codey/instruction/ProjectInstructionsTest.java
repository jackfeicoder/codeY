package io.github.jackfeicoder.codey.instruction;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.context.*;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 文件边界、刷新、隔离与真实组装预算；不请求网络。 */
class ProjectInstructionsTest {
    @TempDir Path root;
    private WorkspaceBinding workspace(Path path) {
        return new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, path.toString());
    }
    @Test void missingAndNoWorkspaceAreExplicitAndDoNotCreateFiles() {
        var loader = new ProjectInstructionLoader();
        assertEquals(ProjectInstructions.Status.NONE, loader.load(WorkspaceBinding.none()).status());
        assertEquals(ProjectInstructions.Status.MISSING, loader.load(workspace(root)).status());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
        assertThrows(IllegalArgumentException.class, () -> new ProjectInitializer().task(WorkspaceBinding.none()));
    }
    @Test void reloadsEvenSameLengthChangesAndSeparatesWorkspaces() throws Exception {
        Path a = Files.createDirectory(root.resolve("a")), b = Files.createDirectory(root.resolve("b"));
        Files.writeString(a.resolve("AGENTS.md"), "rule A");
        Files.writeString(b.resolve("AGENTS.md"), "rule B");
        var loader = new ProjectInstructionLoader();
        assertEquals("rule A", loader.load(workspace(a)).content());
        assertEquals("rule B", loader.load(workspace(b)).content());
        Files.writeString(a.resolve("AGENTS.md"), "rule C");
        assertEquals("rule C", loader.load(workspace(a)).content());
        Files.delete(a.resolve("AGENTS.md"));
        assertEquals(ProjectInstructions.Status.MISSING, loader.load(workspace(a)).status());
    }
    @Test void rejectsBytesTokensMalformedUtf8BlankAndDirectoriesWithoutPartialContent() throws Exception {
        Path file = root.resolve("AGENTS.md");
        Files.writeString(file, "a".repeat(33));
        var result = new ProjectInstructionLoader(32, 100).load(workspace(root));
        assertEquals(ProjectInstructions.Status.INVALID, result.status()); assertEquals("", result.requestText());
        Files.writeString(file, "中".repeat(20));
        assertEquals(ProjectInstructions.Status.INVALID, new ProjectInstructionLoader(100, 10).load(workspace(root)).status());
        Files.write(file, new byte[]{(byte)0xc3, 0x28});
        assertEquals(ProjectInstructions.Status.INVALID, new ProjectInstructionLoader().load(workspace(root)).status());
        Files.writeString(file, " ");
        assertEquals(ProjectInstructions.Status.INVALID, new ProjectInstructionLoader().load(workspace(root)).status());
        Files.delete(file); Files.createDirectory(file);
        assertEquals(ProjectInstructions.Status.INVALID, new ProjectInstructionLoader().load(workspace(root)).status());
    }
    @Test void bomIsAcceptedAndLinkedDocumentsAreNotLoaded() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "\uFEFFSee docs/private.md");
        Files.createDirectory(root.resolve("docs"));
        Files.writeString(root.resolve("docs/private.md"), "DETAIL_NOT_AUTO_LOADED");
        var rules = new ProjectInstructionLoader().load(workspace(root));
        assertEquals(ProjectInstructions.Status.LOADED, rules.status());
        assertEquals("See docs/private.md", rules.content());
        assertFalse(rules.requestText().contains("DETAIL_NOT_AUTO_LOADED"));
    }
    @Test void requestsContainOneCopyAndDoNotChangeRawHistoryOrLeakInitToLaterTask() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "UNIQUE_PROJECT_RULE");
        var loader = new ProjectInstructionLoader();
        var builder = new ContextBuilder(() -> loader.load(workspace(root)).requestText());
        var history = List.of(Message.system("base"), Message.user("old"), Message.assistant("answer"));
        var current = List.of(Message.user("new"));
        for (int step = 1; step <= 3; step++) {
            var snapshot = builder.build(history, current, List.of(), WorkMode.REACT, step, 20);
            String system = snapshot.request().messages().get(0).content();
            assertEquals(1, system.split("UNIQUE_PROJECT_RULE", -1).length - 1);
            assertEquals(new TokenEstimator().textTokens(loader.load(workspace(root)).requestText()), snapshot.tokens().projectInstructions());
            assertEquals(3, history.size()); assertEquals("base", history.get(0).content());
            assertEquals(4, snapshot.request().messages().size());
        }
        String init = new ProjectInitializer().instructions();
        assertTrue(builder.withTaskInstructions(init).build(history, current, List.of(), WorkMode.PLAN, 1, 20)
                .request().messages().get(0).content().contains("项目规则初始化任务"));
        assertFalse(builder.build(history, current, List.of(), WorkMode.REACT, 1, 20)
                .request().messages().get(0).content().contains("项目规则初始化任务"));
        var plain = new ContextBuilder().build(history, current, List.of(), WorkMode.REACT, 1, 20);
        var ruled = builder.build(history, current, List.of(), WorkMode.REACT, 1, 20);
        assertEquals(plain.tokens().total() + ruled.tokens().projectInstructions(), ruled.tokens().total());
    }
    @Test void summaryDoesNotCoverRulesAndStillCountsTheirBudget() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "ALWAYS_RULE");
        var loader = new ProjectInstructionLoader();
        var builder = new ContextBuilder(() -> loader.load(workspace(root)).requestText());
        var raw = List.of(Message.user("old"), Message.assistant("old answer"), Message.user("recent"), Message.assistant("done"));
        var summary = new SummaryDocument(List.of("goal"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        var state = new CompactionState(2, CompactionState.fingerprint(raw.subList(0, 2)), summary, java.time.Instant.now().toString());
        var history = new java.util.ArrayList<Message>(); history.add(Message.system("base")); history.addAll(raw);
        var snapshot = builder.build(history, List.of(), List.of(), WorkMode.REACT, 1, 20, state);
        assertTrue(snapshot.request().messages().get(0).content().contains("ALWAYS_RULE"));
        assertTrue(snapshot.tokens().projectInstructions() > 0);
        assertTrue(snapshot.tokens().summary() > 0);
        assertEquals(5, history.size());
    }
}
