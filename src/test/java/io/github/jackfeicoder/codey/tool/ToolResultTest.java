package io.github.jackfeicoder.codey.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证结果状态契约，避免将错误信息误记为成功。 */
class ToolResultTest {
    /** 成功不能带错误码，失败必须带错误码，输出与耗时必须有效。 */
    @Test
    void rejectsInconsistentResults() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("id", "tool", ToolResult.Status.SUCCESS,
                "output", ToolResult.ErrorCode.EXECUTION_FAILED, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("id", "tool", ToolResult.Status.ERROR,
                "output", null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("id", "tool", ToolResult.Status.SUCCESS,
                null, null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("id", "tool", ToolResult.Status.SUCCESS,
                "", null, -1, false));
    }

    /** 空输出可代表空文件等有效结果，转换消息后仍有调用 ID。 */
    @Test
    void permitsEmptyOutputAndValidatesContext() {
        ToolResult result = new ToolResult("id", "tool", ToolResult.Status.SUCCESS, "", null, 0, false);
        assertEquals("id", result.toMessage().toolCallId());
        assertThrows(IllegalArgumentException.class, () -> new ToolContext(null));
        assertThrows(IllegalArgumentException.class, () -> new ToolContext(
                io.github.jackfeicoder.codey.workspace.WorkspaceBinding.none(), 0));
    }
}
