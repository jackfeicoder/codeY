package io.github.jackfeicoder.codey.session;

import io.github.jackfeicoder.codey.llm.LlmException;
import io.github.jackfeicoder.codey.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 标题请求独立、有界、可回退，不依赖模型正常返回格式。 */
class SessionTitleGeneratorTest {
    @Test
    void usesOnlyBoundedFirstQuestion() {
        String question = "😀".repeat(2500);
        var result = new SessionTitleGenerator().generate(request -> {
            assertEquals(2, request.messages().size());
            String text = request.messages().get(1).content();
            assertEquals(2000, text.codePointCount(0, text.length()));
            return new LlmResponse(Message.assistant("学习 Java"), "stop", null);
        }, question);
        assertTrue(result.generated());
        assertEquals("学习 Java", result.title());
    }

    @Test
    void errorsAndInvalidTitlesRetainLocalSummary() {
        String question = "第一问\n第二行";
        var failed = new SessionTitleGenerator().generate(request -> {
            throw new LlmException(LlmException.Kind.TIMEOUT, "fixture timeout");
        }, question);
        assertFalse(failed.generated());
        assertEquals("第一问 第二行", failed.title());
        for (String invalid : new String[] {"", "two\nlines", "x".repeat(41)}) {
            var result = new SessionTitleGenerator().generate(request ->
                    new LlmResponse(Message.assistant(invalid), "stop", null), question);
            assertEquals(failed, result);
        }
    }

    @Test
    void titleValidationCountsUnicodeAndRejectsControlCharacters() {
        assertTrue(SessionSnapshot.validTitle("😀".repeat(40)));
        assertFalse(SessionSnapshot.validTitle("😀".repeat(41)));
        assertFalse(SessionSnapshot.validTitle("title\u001b[0m"));
    }
}
