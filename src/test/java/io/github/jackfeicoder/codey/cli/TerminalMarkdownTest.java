package io.github.jackfeicoder.codey.cli;

import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实展示契约：分片无关、保留代码/链接/Unicode、表格宽度与内存边界。 */
@Timeout(10)
class TerminalMarkdownTest {
    String render(String text,int width,int chunk) {
        var buffer=new StringWriter();var stream=new ConsoleStreamOutput(new ConsoleTheme(true,false,width),new PrintWriter(buffer),()->{},false);
        stream.onResponseStart(1);for(int i=0;i<text.length();i+=chunk)stream.onText(text.substring(i,Math.min(text.length(),i+chunk)));stream.onResponseEnd(1);
        return buffer.toString().replace("\r\n","\n");
    }
    @Test void formattingIsIndependentOfSseFragmentBoundaries() {
        String text="## 中文😀标题\r\n**完成**：使用 `write_file`。\n- *说明*与snake_case\n> 引用\n[来源](https://example.com/a_(b))\n\n| 文件 | 大小 |\n|---|---:|\n| `index.html` | 7,705 B |\n| 中文😀 | 12 B |\n\n```java\nString s = \"**literal** | x\";\n```";
        String whole=render(text,88,text.length());
        for(int chunk:new int[]{1,2,3,7,17,127})assertEquals(whole,render(text,88,chunk));
        assertTrue(whole.contains("中文😀标题"));assertTrue(whole.contains("完成：使用 write_file"));assertFalse(whole.contains("**完成**"));assertFalse(whole.contains("## 中文"));
        assertTrue(whole.contains("snake_case"));assertTrue(whole.contains("String s = \"**literal** | x\";"));assertTrue(whole.contains("https://example.com/a_(b)"));assertFalse(whole.contains("|---"));
    }
    @Test void narrowTablesStackCellsWithoutTruncatingTheirContent() {
        String file="src/main/java/io/github/example/authentication/LongLoginController.java";
        String rendered=render("| 文件 | 大小 | sha256 |\n|---|---|---|\n| `"+file+"` | 7,705 B | f3e94d1b |",38,1);
        assertTrue(rendered.contains("── 1 ──"));assertTrue(rendered.contains("7,705 B"));assertTrue(rendered.contains("f3e94d1b"));
        assertTrue(rendered.replaceAll("\\s+","").contains(file));
        rendered.lines().forEach(line->assertTrue(new AttributedString(line).columnLength()<=37,line));
    }
    @Test void alignedTableHandlesChineseAndEmojiByDisplayWidth() {
        String rendered=render("| 名称 | 值 |\n|:---|---:|\n| 中文😀 | hello |\n| 第二项 | world |",58,2);
        assertFalse(rendered.contains("── 1 ──"));assertTrue(rendered.contains("中文😀"));
        var rows=rendered.lines().filter(l->l.contains("hello")||l.contains("world")).toList();
        assertEquals(2,rows.size());assertEquals(new AttributedString(rows.get(0).substring(0,rows.get(0).indexOf("hello"))).columnLength(),new AttributedString(rows.get(1).substring(0,rows.get(1).indexOf("world"))).columnLength());
    }
    @Test void pipeInsideCodeAndEscapedPipeAreNotExtraColumns() {
        String rendered=render("| 项 | 值 |\n|---|---|\n| `a|b` | x\\|y |",80,3);
        assertTrue(rendered.contains("a|b"));assertTrue(rendered.contains("x|y"));assertFalse(rendered.contains("|---|"));
    }
    @Test void nonTablePipeTextAndUnclosedMarkdownRemainReadable() {
        String text="one | two\nThis is a paragraph\n**unfinished\n`unfinished\n[unfinished](url\n";
        String rendered=render(text,80,1);
        assertTrue(rendered.contains("one | two"));assertTrue(rendered.contains("**unfinished"));assertTrue(rendered.contains("`unfinished"));assertTrue(rendered.contains("[unfinished](url"));
    }
    @Test void tableCanEndAtEndOfStreamAndLargeTablesFlushInBoundedBatches() {
        StringBuilder text=new StringBuilder("| Key | Value |\n|---|---|\n");for(int i=0;i<105;i++)text.append("| row-").append(i).append(" | value-").append(i).append(" |\n");
        String rendered=render(text.toString(),80,5);
        for(int i=0;i<105;i++)assertTrue(rendered.contains("row-"+i+" "),"missing row "+i);
        assertTrue(rendered.contains("value-104"));
    }
    @Test void longProseAppearsBeforeResponseFinishesAndLongLinesKeepAllText() {
        var writer=new StringWriter();var stream=new ConsoleStreamOutput(new ConsoleTheme(true,false),new PrintWriter(writer),()->{},false);
        stream.onResponseStart(1);stream.onText("正文".repeat(110));assertTrue(writer.toString().contains("正文".repeat(90)));
        stream.onText("结尾");stream.onResponseEnd(1);assertTrue(writer.toString().replaceAll("\\s+","").contains("正文".repeat(110)+"结尾"));
        String longCode="x".repeat(20000);assertTrue(render("```txt\n"+longCode+"\n```",80,1).contains(longCode));
    }
    @Test void hostileControlSequencesAreEscapedAndNoColorHasNoAnsi() {
        String rendered=render("**bold**\n| A | B |\n|---|---|\n| \u001b[2J | 中😀 |\n",60,1);
        assertFalse(rendered.contains("\u001b"));assertTrue(rendered.contains("\\u001b[2J"));assertTrue(rendered.contains("中😀"));
    }
    @Test void consecutiveResponsesResetFencesAndPartialMarkup() {
        var text=new StringWriter();var stream=new ConsoleStreamOutput(new ConsoleTheme(true,false),new PrintWriter(text),()->{},true);
        stream.onResponseStart(1);stream.onText("```java\nunfinished");stream.onResponseEnd(1);
        stream.onResponseStart(2);stream.onText("**final**");stream.onResponseEnd(2);
        assertTrue(text.toString().contains("final"));assertFalse(text.toString().contains("**final**"));assertEquals(2,text.toString().split("● CodeY",-1).length-1);
    }
    @Test void styledDisplayChangesOnlyPresentationAndKeepsLinkTargets() {
        var text=new StringWriter();new ConsoleTheme(true).assistant(new PrintWriter(text),"# Heading\n**bold** and `code` [docs](https://example.com)");
        assertTrue(text.toString().contains("\u001b["));String plain=AttributedString.fromAnsi(text.toString()).toString();
        assertFalse(plain.contains("**bold**"));assertTrue(plain.contains("docs (https://example.com)"));
    }
}
