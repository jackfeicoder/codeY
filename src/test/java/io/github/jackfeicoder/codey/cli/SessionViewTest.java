package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.tool.ToolCall;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionViewTest {
    @Test void welcomeAndSessionRowsFitNarrowAndWideTerminals() {
        for(int width:new int[]{32,60,100}) {
            var text=new StringWriter();var view=new SessionView(new ConsoleTheme(true,false,width),new PrintWriter(text));
            var session=SessionSnapshot.create(WorkspaceBinding.none(),"fixture","base","中文😀的长标题".repeat(4));
            view.welcome("E:\\Desktop\\github\\a-long-workspace",15);view.row(1,session,"12345678","2026-11-09 10:06");
            String result=text.toString();assertTrue(result.contains("CodeY"));assertTrue(result.contains("最近会话"));assertTrue(result.contains("12345678"));
            result.lines().forEach(line->assertTrue(new AttributedString(line).columnLength()<width,line));
            assertFalse(result.contains("\u001b"));assertFalse(result.contains("序号 | ID"));
        }
    }
    @Test void historyPreviewContainsConversationButNeverToolPayloadOrIds() {
        var snapshot=SessionSnapshot.create(WorkspaceBinding.none(),"fixture","base","test");
        snapshot=snapshot.update("test",SessionSnapshot.TitleSource.MANUAL,false,List.of(Message.user("question"),Message.assistantToolCalls(List.of(new ToolCall("private-call","read_file","{}"))),Message.tool("private-call","{\"payload\":\"private-tool-json\"}"),Message.assistant("**最终回答**")));
        for(boolean interactive:new boolean[]{true,false}) {
            var text=new StringWriter();new SessionView(new ConsoleTheme(interactive,false),new PrintWriter(text)).preview(snapshot);
            assertTrue(text.toString().contains("question"));assertTrue(text.toString().contains("最终回答"));assertFalse(text.toString().contains("private-tool-json"));assertFalse(text.toString().contains("private-call"));assertFalse(text.toString().contains("TOOL:"));
        }
        assertEquals(4,snapshot.messages().size());assertTrue(snapshot.messages().get(3).content().contains("**"));
    }
    @Test void previewIsBoundedAndUserControlCharactersAreEscaped() {
        var snapshot=SessionSnapshot.create(WorkspaceBinding.none(),"fixture","base","test");
        snapshot=snapshot.update("test",SessionSnapshot.TitleSource.MANUAL,false,List.of(Message.user("hello\u001b[2J"),Message.assistant("line\n".repeat(2000))));
        var text=new StringWriter();new SessionView(new ConsoleTheme(true,false),new PrintWriter(text)).preview(snapshot);
        assertTrue(text.toString().contains("\\u001b[2J"));assertFalse(text.toString().contains("\u001b"));assertTrue(text.toString().contains("完整历史已保留"));assertTrue(text.toString().length()<1000);
    }
}
