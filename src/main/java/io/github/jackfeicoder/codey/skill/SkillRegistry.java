package io.github.jackfeicoder.codey.skill;

import java.util.*;

/** 合并技能元数据；provider 按用户→项目提供，后者覆盖同名项，正文不在目录中发布。 */
public final class SkillRegistry {
    private final Map<String,SkillDefinition> definitions=new TreeMap<>();
    public void replace(List<SkillDefinition> skills) {definitions.clear();for(var skill:skills)definitions.put(skill.name(),skill);}
    public List<SkillDefinition> all() {return List.copyOf(definitions.values());}
    public Optional<SkillDefinition> find(String name) {return Optional.ofNullable(definitions.get(name));}
}
