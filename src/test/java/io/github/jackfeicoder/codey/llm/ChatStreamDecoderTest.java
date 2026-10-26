package io.github.jackfeicoder.codey.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.io.StringReader;
import static org.junit.jupiter.api.Assertions.*;

/** SSE 帧和内存边界的独立检查，不需要网络或厂商服务。 */
class ChatStreamDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    @Test void multilineDataCommentsAndUnknownSseMetadataAreHandled() throws Exception {
        String text = ": heartbeat\nretry: 500\nid: event-1\nevent: message\n"
                + "data: {\"choices\":[{\"index\":0,\n"
                + "data: \"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        var parts = new StringBuilder();
        var result = new ChatStreamDecoder(mapper, parts::append).read(new StringReader(text));
        assertEquals("hello", parts.toString()); assertEquals("hello", result.at("/choices/0/message/content").asText());
    }
    @Test void oversizedLinesAreRejectedBeforeParsingOrTextEmission() {
        String text = "data: " + "x".repeat(ChatStreamDecoder.MAX_EVENT_CHARS + 1);
        var error = assertThrows(LlmException.class, () -> new ChatStreamDecoder(mapper, part -> fail("No text expected"))
                .read(new StringReader(text)));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind()); assertTrue(error.getMessage().contains("size limit"));
    }
    @Test void malformedUnicodeIsNotReturnedAsACompleteResponse() {
        String data = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\\ud83d\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        var error = assertThrows(LlmException.class, () -> new ChatStreamDecoder(mapper, part -> { }).read(new StringReader(data)));
        assertEquals(LlmException.Kind.INVALID_RESPONSE, error.kind());
    }
}
