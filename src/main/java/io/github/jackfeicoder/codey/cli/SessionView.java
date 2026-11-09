package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.session.*;
import org.jline.utils.AttributedString;
import java.io.PrintWriter;
import java.util.List;

/** 会话展示只处理终端样式：选择/分页/持久化仍归SessionLauncher，工具JSON不进入历史预览。 */
final class SessionView {
    private final ConsoleTheme theme;
    private final PrintWriter out;
    SessionView(ConsoleTheme theme,PrintWriter out){this.theme=theme;this.out=out;}
    void welcome(String directory,int total) {
        if(!theme.interactive()) {
            out.println("CodeY · 所有工作区的最近会话");out.println("序号 | ID | 标题 | 工作区 | 更新时间");return;
        }
        int width=Math.max(16,Math.min(92,theme.columns()-1));
        out.println();
        out.println(theme.text(ConsoleTheme.Tone.MUTED,"╭"+"─".repeat(width-2)+"╮"));
        panel("CodeY",width,ConsoleTheme.Tone.USER);
        panel("从这里继续，或开始一个新任务",width,ConsoleTheme.Tone.MUTED);
        panel(directory,width,ConsoleTheme.Tone.MUTED);
        out.println(theme.text(ConsoleTheme.Tone.MUTED,"╰"+"─".repeat(width-2)+"╯"));
        String caption=theme.columns()<48?" · "+total+" 个":"  · 所有工作区 · "+total+" 个";
        out.println();out.println("  "+theme.text(ConsoleTheme.Tone.USER,"最近会话")+theme.text(ConsoleTheme.Tone.MUTED,caption));out.println();
    }
    private void panel(String text,int width,ConsoleTheme.Tone tone) {
        String value=fit(text,width-6);int used=columns(value);
        out.println(theme.text(ConsoleTheme.Tone.MUTED,"│  ")+theme.text(tone,value)+" ".repeat(Math.max(0,width-4-used))+theme.text(ConsoleTheme.Tone.MUTED,"│"));
    }
    void row(int number,SessionSnapshot snapshot,String shortId,String time) {
        if(!theme.interactive()) {
            out.println(number+" | "+shortId+" | "+snapshot.title()+" | "+snapshot.workspace().display()+" | "+time);return;
        }
        int width=Math.max(12,theme.columns()-5);String prefix=String.format("%2d  ",number);String when=time.substring(5);
        int titleWidth=width-columns(prefix)-columns(when)-2;
        if(titleWidth>=10) {
            String title=fit(snapshot.title(),titleWidth);
            out.println("  "+theme.text(ConsoleTheme.Tone.COMMAND,prefix)+theme.text(ConsoleTheme.Tone.USER,title)
                    +" ".repeat(Math.max(2,titleWidth-columns(title)+2))+theme.text(ConsoleTheme.Tone.MUTED,when));
        }else out.println("  "+theme.text(ConsoleTheme.Tone.COMMAND,prefix)+theme.text(ConsoleTheme.Tone.USER,fit(snapshot.title(),width-columns(prefix))));
        String path=snapshot.workspace().display();
        int pathWidth=Math.max(2,theme.columns()-8-columns(shortId+" · "));
        out.println("      "+theme.text(ConsoleTheme.Tone.MUTED,shortId+" · "+tail(path,pathWidth)));
    }
    void footer(boolean active) {
        if(!theme.interactive()) {
            out.println("输入序号、完整标题或 ID；/new 新建，/more_session 下一批，/sessions 刷新，/help 查看命令，exit 或 /exit 退出。");
            if(active)out.println("/back 返回当前会话。");return;
        }
        out.println();
        out.println(theme.text(ConsoleTheme.Tone.MUTED,theme.columns()<48?"  输入序号打开会话":"  输入序号、标题或 ID 打开会话"));
        var hints=theme.columns()<48?new java.util.ArrayList<>(List.of("/new 新对话","/more_session 更多","/help 帮助 · exit 退出")):
                new java.util.ArrayList<>(List.of("/new 新对话  /more_session 更多","/help 帮助  exit 退出"+(active?"  /back 返回":"")));
        if(active&&theme.columns()<48)hints.add("/back 返回当前会话");
        for(String hint:hints)
            out.println("  "+theme.text(ConsoleTheme.Tone.COMMAND,hint));
        out.println();
    }
    void selected(SessionSnapshot snapshot,String model) {
        if(!theme.interactive()) {out.println("会话："+snapshot.title()+" | ID "+snapshot.id()+" | "+snapshot.workspace().display()+" | Profile "+model);return;}
        out.println();out.println("  "+theme.text(ConsoleTheme.Tone.USER,fit(snapshot.title(),Math.max(8,theme.columns()-5))));
        out.println("  "+theme.text(ConsoleTheme.Tone.MUTED,fit(snapshot.workspace().display(),Math.max(8,theme.columns()-5))));
        out.println("  "+theme.text(ConsoleTheme.Tone.COMMAND,fit(model,Math.max(8,theme.columns()-5))));
        out.println(theme.text(ConsoleTheme.Tone.MUTED,theme.columns()<48?"  /help 命令 · exit 返回":"  /help 命令 · exit 返回会话列表"));out.println();
    }
    /** 最近一个成功用户轮次的对话摘要，不输出中间assistant调用和tool正文。 */
    void preview(SessionSnapshot snapshot) {
        List<Message> messages=snapshot.messages();int start=-1;
        for(int i=messages.size()-1;i>=0;i--)if(messages.get(i).role()==MessageRole.USER){start=i;break;}
        if(start<0)return;
        out.println();theme.line(out,ConsoleTheme.Tone.MUTED,"  上次对话");
        var question=messages.get(start);out.println(theme.text(ConsoleTheme.Tone.USER,"  ❯ ")+fit(question.content().replaceAll("\\s+"," "),Math.max(12,theme.columns()-7)));
        for(int i=messages.size()-1;i>start;i--) {
            var message=messages.get(i);
            if(message.role()!=MessageRole.ASSISTANT||!message.toolCalls().isEmpty()||message.content().isBlank())continue;
            String content=message.content();int limit=content.offsetByCodePoints(0,Math.min(600,content.codePointCount(0,content.length())));
            boolean more=limit<content.length();content=content.substring(0,limit);
            var lines=content.split("\\R",-1);if(lines.length>8){content=String.join("\n",java.util.Arrays.copyOf(lines,8));more=true;}
            if(theme.interactive()){var renderer=new TerminalMarkdown(theme,out);renderer.accept(ConsoleTheme.safeText(content));renderer.finish();}
            else out.println("CodeY> "+ConsoleTheme.safeText(content));
            if(more)theme.line(out,ConsoleTheme.Tone.MUTED,"  …（仅预览，完整历史已保留）");break;
        }
        out.println();
    }
    static String fit(String text,int width) {
        var clean=new AttributedString(ConsoleApprovalHandler.clean(text));width=Math.max(1,width);
        return clean.columnLength()<=width?clean.toString():clean.columnSubSequence(0,Math.max(0,width-1))+"…";
    }
    private static String tail(String text,int width) {
        var clean=new AttributedString(ConsoleApprovalHandler.clean(text));
        return clean.columnLength()<=width?clean.toString():"…"+clean.columnSubSequence(Math.max(0,clean.columnLength()-width+1),clean.columnLength());
    }
    private static int columns(String text){return new AttributedString(text).columnLength();}
}
