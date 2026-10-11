package io.github.jackfeicoder.codey.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallTest {
    @Test
    void keepsOriginalArgumentsForLaterJsonParsing() {
        String arguments = "{ \"path\": \"src/Main.java\", \"limit\": 20 }";
        assertEquals(arguments, new ToolCall("call_1", "read_file", arguments).arguments());
        assertEquals("{}", new ToolCall("call_2", "list_files", "{}").arguments());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingId(String id) {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall(id, "read_file", "{}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingName(String name) {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("call_1", name, "{}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingArguments(String arguments) {
        assertThrows(IllegalArgumentException.class, () -> new ToolCall("call_1", "read_file", arguments));
    }
}
