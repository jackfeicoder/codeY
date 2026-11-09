package io.github.jackfeicoder.codey.cli;

import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Pattern;

/** 有界、只追加的终端Markdown展示：按行识别块，表格暂存后按显示列宽排版，不改写消息。 */
final class TerminalMarkdown {
    private static final int MAX_LINE = 8192, MAX_TABLE_ROWS = 40, MAX_TABLE_CHARS = 32768;
    private static final Pattern SEPARATOR = Pattern.compile(":?-{3,}:?");
    private final ConsoleTheme theme;
    private final PrintWriter out;
    private final StringBuilder pending = new StringBuilder();
    private String tableCandidate;
    private List<String> headers;
    private final List<List<String>> rows = new ArrayList<>();
    private int tableChars;
    private boolean tableContinuation;
    private char fence;
    private int fenceLength;
    private boolean continuedLine;

    TerminalMarkdown(ConsoleTheme theme, PrintWriter out) { this.theme=theme; this.out=out; }

    /** 分片只进入展示缓冲；普通长正文提前输出，行和表格均有硬内存上限。 */
    void accept(String text) {
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(ch=='\n') { line(pending.toString());pending.setLength(0); }
            else {
                pending.append(ch);
                if(pending.length()>=MAX_LINE && !Character.isHighSurrogate(ch)) {
                    flushTable();flushCandidate();out.print(pending);pending.setLength(0);continuedLine=true;
                } else if(pending.length()>=192 && headers==null&&tableCandidate==null&&fence==0
                        && plainProse(pending.toString()) && !Character.isHighSurrogate(ch)) {
                    out.print(pending);pending.setLength(0);continuedLine=true;
                }
            }
        }
        out.flush();
    }
    void finish() {
        if(!pending.isEmpty()||continuedLine)line(pending.toString());
        pending.setLength(0);flushTable();flushCandidate();out.flush();
    }
    private static boolean plainProse(String line) {
        String s=line.stripLeading();
        return !s.isEmpty()&&!s.matches("^[#>*_`~|+\\-0-9].*")
                &&line.chars().noneMatch(c->"*_`[|<\\".indexOf(c)>=0);
    }
    private void line(String text) {
        if(continuedLine){out.println(theme.styled(inline(text)));continuedLine=false;return;}
        if(headers!=null) {
            var cells=cells(text);
            if(cells.size()==headers.size()) {
                rows.add(cells);tableChars+=text.length();
                if(rows.size()>=MAX_TABLE_ROWS||tableChars>=MAX_TABLE_CHARS) {
                    var keep=headers;flushTable();headers=keep;tableContinuation=true;
                }
                return;
            }
            flushTable();
        }
        if(tableCandidate!=null) {
            var head=cells(tableCandidate);var divider=cells(text);
            if(head.size()>=2&&head.size()<=12&&head.size()==divider.size()
                    &&divider.stream().allMatch(c->SEPARATOR.matcher(c.strip()).matches())) {
                headers=head;tableCandidate=null;tableChars=0;tableContinuation=false;return;
            }
            flushCandidate();
        }
        String stripped=text.stripLeading();
        if(fence!=0) {
            int run=run(stripped,0,fence);
            if(run>=fenceLength&&stripped.substring(run).isBlank()){fence=0;out.println(theme.text(ConsoleTheme.Tone.MUTED,"  └─"));}
            else out.println(theme.text(ConsoleTheme.Tone.THINKING,"  "+text));
            return;
        }
        if(stripped.startsWith("```")||stripped.startsWith("~~~")) {
            fence=stripped.charAt(0);fenceLength=run(stripped,0,fence);
            out.println(theme.text(ConsoleTheme.Tone.MUTED,"  ┌─ "+stripped.substring(fenceLength).strip()));return;
        }
        if(cells(text).size()>=2){tableCandidate=text;return;}
        block(text);
    }
    private void flushCandidate(){if(tableCandidate!=null){block(tableCandidate);tableCandidate=null;}}
    private void block(String line) {
        String trimmed=line.strip();
        if(trimmed.matches("(?:-{3,}|\\*{3,}|_{3,})")) {
            out.println(theme.text(ConsoleTheme.Tone.MUTED,"─".repeat(Math.max(1,Math.min(60,theme.columns()-1)))));return;
        }
        var heading=java.util.regex.Pattern.compile("^ {0,3}#{1,6}\\s+(.+?)(?:\\s+#+)?$").matcher(line);
        if(heading.matches()) {
            out.println(theme.styled(inline(heading.group(1),ConsoleTheme.style(ConsoleTheme.Tone.USER),0)));return;
        }
        var quote=java.util.regex.Pattern.compile("^\\s*>\\s?(.*)$").matcher(line);
        if(quote.matches()){out.println(theme.text(ConsoleTheme.Tone.MUTED,"  │ ")+theme.styled(inline(quote.group(1))));return;}
        var bullet=java.util.regex.Pattern.compile("^(\\s*)[-+*]\\s+(.*)$").matcher(line);
        if(bullet.matches())line=bullet.group(1)+"• "+bullet.group(2);
        out.println(theme.styled(inline(line)));
    }

    /** 区分转义分隔符及代码里的竖线；未形成合法分隔行的文本仍按原文展示。 */
    private static List<String> cells(String text) {
        String s=text.strip();var result=new ArrayList<String>();var cell=new StringBuilder();int code=0;boolean separator=false;
        for(int i=0;i<s.length();i++) {
            char ch=s.charAt(i);
            if(ch=='\\'&&i+1<s.length()){cell.append(ch).append(s.charAt(++i));continue;}
            if(ch=='`') {int n=run(s,i,'`');if(code==0)code=n;else if(code==n)code=0;cell.append("`".repeat(n));i+=n-1;continue;}
            if(ch=='|'&&code==0){result.add(cell.toString().strip());cell.setLength(0);separator=true;}
            else cell.append(ch);
        }
        result.add(cell.toString().strip());
        if(!separator)return List.of();
        if(s.startsWith("|"))result.remove(0);
        if(s.endsWith("|")&&!s.endsWith("\\|")&&!result.isEmpty())result.remove(result.size()-1);
        return result;
    }
    private void flushTable() {
        if(headers==null)return;
        if(rows.isEmpty()) {
            if(!tableContinuation)out.println("  "+theme.styled(inline(String.join("   ",headers))));
            headers=null;tableChars=0;tableContinuation=false;return;
        }
        int count=headers.size(), available=Math.max(1,theme.columns()-3), gaps=3*(count-1);
        int[] widths=new int[count];
        var head=headers.stream().map(TerminalMarkdown::inline).toList();
        var body=rows.stream().map(r->r.stream().map(TerminalMarkdown::inline).toList()).toList();
        for(int c=0;c<count;c++) {widths[c]=Math.max(4,head.get(c).columnLength());for(var r:body)widths[c]=Math.max(widths[c],r.get(c).columnLength());}
        int preferred=Arrays.stream(widths).sum()+gaps;
        if(available<count*8+gaps || preferred>available*2) {
            // 窄屏或超长文件路径采用键值卡片，不截掉数据、不让多列挤成一团。
            for(int i=0;i<body.size();i++) {
                out.println(theme.text(ConsoleTheme.Tone.MUTED,"  ── "+(i+1)+" ──"));
                for(int c=0;c<count;c++) {
                    var value=new AttributedStringBuilder().style(AttributedStyle.DEFAULT.bold()).append(head.get(c)).append(": ")
                            .style(AttributedStyle.DEFAULT).append(body.get(i).get(c)).toAttributedString();
                    for(var part:wrap(value,available))out.println("  "+theme.styled(part));
                }
            }
        } else {
            while(Arrays.stream(widths).sum()+gaps>available) {
                int largest=0;for(int c=1;c<count;c++)if(widths[c]>widths[largest])largest=c;
                if(widths[largest]<=4)break;widths[largest]--;
            }
            tableRow(head,widths,true);
            var divider=new StringJoiner("   ","  ","");for(int w:widths)divider.add("─".repeat(w));out.println(theme.text(ConsoleTheme.Tone.MUTED,divider.toString()));
            for(var r:body)tableRow(r,widths,false);
        }
        headers=null;rows.clear();tableChars=0;tableContinuation=false;
    }
    private void tableRow(List<AttributedString> cells,int[] widths,boolean header) {
        var wrapped=new ArrayList<List<AttributedString>>();int height=1;
        for(int c=0;c<widths.length;c++){var parts=wrap(cells.get(c),widths[c]);wrapped.add(parts);height=Math.max(height,parts.size());}
        for(int row=0;row<height;row++) {
            var line=new StringBuilder("  ");
            for(int c=0;c<widths.length;c++) {
                var part=row<wrapped.get(c).size()?wrapped.get(c).get(row):AttributedString.EMPTY;
                line.append(header?theme.text(ConsoleTheme.Tone.USER,part.toString()):theme.styled(part));
                if(c<widths.length-1)line.append(" ".repeat(Math.max(0,widths[c]-part.columnLength())+3));
            }
            out.println(line);
        }
    }
    private static List<AttributedString> wrap(AttributedString value,int width) {
        var parts=new ArrayList<AttributedString>();int total=value.columnLength();
        // 使用JLine显示列处理中文/emoji，不按UTF-16字符数裁剪。
        for(int at=0;at<total;) {
            int end=Math.min(total,at+Math.max(2,width));var part=value.columnSubSequence(at,end);
            if(part.isEmpty()){at=end;continue;}parts.add(part);at+=part.columnLength();
        }
        return parts.isEmpty()?List.of(AttributedString.EMPTY):parts;
    }

    static AttributedString inline(String text){return inline(text.replace("\t","    "),AttributedStyle.DEFAULT,0);}
    private static AttributedString inline(String text,AttributedStyle base,int depth) {
        var out=new AttributedStringBuilder();if(depth>8)return new AttributedString(text,base);
        for(int i=0;i<text.length();) {
            out.style(base);char ch=text.charAt(i);
            if(ch=='\\'&&i+1<text.length()&&"\\`*_{}[]()#+-.!|>".indexOf(text.charAt(i+1))>=0){out.append(text.charAt(i+1));i+=2;continue;}
            if(ch=='`') {
                int n=run(text,i,'`'),end=text.indexOf("`".repeat(n),i+n);
                if(end>=0){out.style(base.foreground(AttributedStyle.CYAN)).append(text.substring(i+n,end));i=end+n;continue;}
            }
            if(ch=='*'||ch=='_') {
                int n=Math.min(2,run(text,i,ch));String mark=String.valueOf(ch).repeat(n);int end=text.indexOf(mark,i+n);
                boolean word=ch=='_'&&i>0&&Character.isLetterOrDigit(text.charAt(i-1));
                if(!word&&end>i+n&&!Character.isWhitespace(text.charAt(i+n))) {
                    out.append(inline(text.substring(i+n,end),n==2?base.bold():base.italic(),depth+1));i=end+n;continue;
                }
            }
            if(ch=='[' || ch=='!'&&i+1<text.length()&&text.charAt(i+1)=='[') {
                int start=ch=='!'?i+1:i;int close=text.indexOf("](",start+1);
                if(close>=0) {
                    int end=close+2,balance=1;for(;end<text.length();end++){char c=text.charAt(end);if(c=='\\'){end++;continue;}if(c=='(')balance++;if(c==')'&&--balance==0)break;}
                    if(end<text.length()) {
                        String label=text.substring(start+1,close),url=text.substring(close+2,end);
                        if(ch=='!')out.append("[图片: ");out.append(inline(label,base.underline(),depth+1));if(ch=='!')out.append(']');
                        if(!label.equals(url))out.style(base).append(" (").append(url).append(')');i=end+1;continue;
                    }
                }
            }
            int cp=text.codePointAt(i);out.append(new String(Character.toChars(cp)));i+=Character.charCount(cp);
        }
        return out.toAttributedString();
    }
    private static int run(String s,int at,char ch){int end=at;while(end<s.length()&&s.charAt(end)==ch)end++;return end-at;}
}
