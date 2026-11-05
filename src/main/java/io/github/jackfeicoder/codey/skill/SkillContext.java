package io.github.jackfeicoder.codey.skill;

/** 交给 ContextBuilder 的两份互不重复的文本；资源正文仍属于工具结果。 */
public record SkillContext(String catalog,String instructions) {
    public static final SkillContext EMPTY=new SkillContext("","");
}
