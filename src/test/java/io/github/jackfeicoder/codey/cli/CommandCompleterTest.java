package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.ConfigException;
import io.github.jackfeicoder.codey.config.ModelProfiles;
import org.jline.reader.Candidate;
import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证补全位置、供应商隔离、配置失败与普通聊天边界，不依赖原生终端。 */
class CommandCompleterTest {
    private final List<ModelProfiles.ProfileInfo> catalog = List.of(
            new ModelProfiles.ProfileInfo("qwen", "Model-A", true),
            new ModelProfiles.ProfileInfo("qwen", "Model-B", true),
            new ModelProfiles.ProfileInfo("sensenova", "nova-model", true));
    private final CommandCompleter completer = new CommandCompleter(() -> catalog, () -> true);

    @Test
    void completesCommandsAndListArgumentWithoutLoadingModels() {
        var commandsOnly = new CommandCompleter(() -> { throw new AssertionError("No config lookup expected"); }, () -> true);
        assertEquals(List.of("/help"), values(commandsOnly, "/h"));
        assertEquals(List.of("/context"), values(commandsOnly, "/con"));
        assertEquals(List.of("/mode", "/memory", "/model", "/more_session"), values(commandsOnly, "/m"));
        assertEquals(List.of("ask", "auto"), values(commandsOnly, "/permissions a"));
        assertEquals(List.of("react", "plan"), values(commandsOnly, "/mode "));
        assertEquals(List.of("revoke"), values(commandsOnly, "/permissions r"));
        assertEquals(List.of("model"), values(commandsOnly, "/list m"));
        assertTrue(values(commandsOnly, "你好").isEmpty());
        assertTrue(values(commandsOnly, "/rename /h").isEmpty());
    }

    @Test
    void completesProviderThenOnlyItsExactCaseModelIds() {
        assertEquals(List.of("qwen", "sensenova"), values(completer, "/model "));
        assertEquals(List.of("qwen"), values(completer, "/model q"));
        assertEquals(List.of("Model-A", "Model-B"), values(completer, "/model qwen M"));
        assertEquals(List.of("nova-model"), values(completer, "/model sensenova "));
        assertTrue(values(completer, "/model qwen m").isEmpty());
        assertTrue(values(completer, "/model unknown ").isEmpty());
        assertTrue(values(completer, "/model qwen Model-A ").isEmpty());
    }

    @Test
    void disabledAuxiliaryInputAndBadConfigDoNotInterruptEditing() {
        assertTrue(values(new CommandCompleter(() -> catalog, () -> false), "/h").isEmpty());
        var broken = new CommandCompleter(() -> { throw new ConfigException("fixture config error"); }, () -> true);
        assertTrue(values(broken, "/model ").isEmpty());
        assertEquals(List.of("/help"), values(broken, "/h"));
    }

    @Test void memoryCommandsAndFlagsCompleteWithoutModelLookup() {
        var local = new CommandCompleter(() -> { throw new AssertionError("no model lookup"); }, () -> true);
        assertEquals(List.of("/remember"), values(local, "/rem"));
        assertEquals(List.of("/forget"), values(local, "/fo"));
        assertEquals(List.of("--user", "--update"), values(local, "/remember --u"));
        assertEquals(List.of("--always"), values(local, "/remember --user --a"));
    }

    @Test void memoryMaintenanceCommandsAndScopesCompleteLocally() {
        assertEquals(List.of("tidy"),values(completer,"/memory t"));
        assertEquals(List.of("--user"),values(completer,"/memory tidy --u"));
        assertEquals(List.of("archives"),values(completer,"/memory ar"));
    }

    /** 使用生产解析器对应的词位置，检查替换的是当前词而非完整输入。 */
    private List<String> values(CommandCompleter target, String text) {
        var parsed = new DefaultParser().escapeChars(null).quoteChars(null)
                .parse(text, text.length(), Parser.ParseContext.COMPLETE);
        var candidates = new ArrayList<Candidate>();
        target.complete(null, parsed, candidates);
        return candidates.stream().map(Candidate::value).toList();
    }
}
