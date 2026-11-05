package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.skill.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import java.io.*;
import java.util.*;

/** 技能用户入口；只解析命令和展示目录，实际任务执行交还 SessionLauncher。 */
public final class SkillCommands {
    public record Invocation(String name,String task) { }
    private final SkillService skills;
    private final ConsoleInput input;
    private final PrintWriter output;
    public SkillCommands(SkillService skills,ConsoleInput input,PrintWriter output){this.skills=skills;this.input=input;this.output=output;}
    public static Invocation parse(String arguments) {
        String[] parts=arguments.strip().split("\\s+",2);
        if(!SkillDefinition.validName(parts[0]))throw new IllegalArgumentException("用法：/skill 技能名称 [任务描述]");
        return new Invocation(parts[0],parts.length>1?parts[1]:"请按照该技能完成其定义的任务；缺少必要输入时先说明。");
    }
    /** 列表中的选择不会创建新会话或调用模型；返回选择后由宿主执行。 */
    public Invocation menu(WorkspaceBinding workspace,boolean canRun)throws IOException {
        var available=skills.refresh(workspace);
        warnings();
        if(available.isEmpty()){output.println("暂无技能。将 <名称>/SKILL.md 放入用户技能目录或工作区 .agents/skills 后重试。");return null;}
        for(int i=0;i<available.size();i++) {
            var skill=available.get(i);
            output.println((i+1)+". "+skill.name()+" ["+skill.source()+"]"
                    +(skill.displayName().equals(skill.name())?"":" · 显示名称："+SkillDefinition.preview(skill.displayName(),80))
                    +" · "+SkillDefinition.preview(skill.description(),160)
                    +" · "+(skill.modelInvocable()?"模型可调用":"仅显式调用")+(skill.userInvocable()?"":" · 用户调用已禁用"));
        }
        if(!canRun){output.println("进入会话后可 /skill 名称 任务描述。");return null;}
        String choice=input.readSetup("选择技能序号/名称（回车返回）> ",false);
        if(choice==null||choice.isBlank())return null;
        String name=choice.strip();
        if(name.matches("[0-9]+")) {
            try {int index=Integer.parseInt(name)-1;if(index<0||index>=available.size())throw new IllegalArgumentException("技能序号不存在。");name=available.get(index).name();}
            catch(NumberFormatException invalid){throw new IllegalArgumentException("技能序号不存在。");}
        }
        String task=input.readSetup("任务描述（回车使用技能默认任务）> ",false);
        if(task==null)return null;
        return parse(name+" "+task);
    }
    public void warnings(){for(String warning:skills.warnings())output.println("[技能] "+warning);}
}
