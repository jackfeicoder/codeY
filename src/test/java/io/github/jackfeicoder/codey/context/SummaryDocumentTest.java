package io.github.jackfeicoder.codey.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 摘要是严格、有界的数据对象；拒绝模型附带执行指令式尾文和不完整结构。 */
class SummaryDocumentTest {
    private final SummaryDocument valid = new SummaryDocument(List.of("goal"), List.of("constraint"), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of());

    @Test void immutableRoundTripAcceptsOnlyEntireJsonFence() {
        assertEquals(valid, SummaryDocument.parse(valid.json()));
        assertEquals(valid, SummaryDocument.parse("```json\n" + valid.json() + "\n```"));
        assertThrows(UnsupportedOperationException.class, () -> valid.goals().add("changed"));
        assertThrows(IllegalArgumentException.class, () -> SummaryDocument.parse("instructions\n" + valid.json()));
        assertThrows(IllegalArgumentException.class, () -> SummaryDocument.parse(valid.json() + "\n{}"));
    }

    @ParameterizedTest @ValueSource(strings={"duplicate", "unknown", "missing", "scalar", "number", "empty", "blank", "tooMany", "tooLong"})
    void rejectsMalformedOrUnboundedModelResponses(String kind) {
        String json = switch (kind) {
            case "duplicate" -> valid.json().replace("\"goals\":", "\"goals\":[],\"goals\":");
            case "unknown" -> valid.json().replace("\"goals\"", "\"extra\"");
            case "missing" -> valid.json().replace("\"constraints\":[\"constraint\"],", "");
            case "scalar" -> valid.json().replace("[\"goal\"]", "\"goal\"");
            case "number" -> valid.json().replace("[\"goal\"]", "[123]");
            case "empty" -> valid.json().replace("[\"goal\"]", "[]").replace("[\"constraint\"]", "[]");
            case "blank" -> valid.json().replace("[\"goal\"]", "[\" \" ]");
            case "tooMany" -> valid.json().replace("[\"goal\"]", "[" + String.join(",", Collections.nCopies(65, "\"goal\"")) + "]");
            default -> valid.json().replace("goal\"", "x".repeat(8193) + "\"");
        };
        assertThrows(IllegalArgumentException.class, () -> SummaryDocument.parse(json), kind);
    }
}
