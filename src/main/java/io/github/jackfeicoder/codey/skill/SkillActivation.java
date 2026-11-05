package io.github.jackfeicoder.codey.skill;

/** 激活后正文固定到本任务；不把激活状态写回技能文件或会话权限。 */
public record SkillActivation(SkillDefinition definition,String renderedInstructions) { }
