package io.github.jackfeicoder.codey.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jackfeicoder.codey.tool.*;

/** 读取已激活技能的文本资料；与 read_file 分离，限定在该技能目录。 */
public final class ReadSkillResourceTool implements Tool {
    private final SkillService skills;
    public ReadSkillResourceTool(SkillService skills){this.skills=skills;}
    @Override public ToolAccess access(){return ToolAccess.READ_ONLY;}
    @Override public ToolDefinition definition(){
        var schema=JsonNodeFactory.instance.objectNode().put("type","object").put("additionalProperties",false);var props=schema.putObject("properties");
        props.putObject("skill").put("type","string").put("description","已激活技能的调用标识，不是显示名称");props.putObject("path").put("type","string");schema.putArray("required").add("skill").add("path");
        return new ToolDefinition("read_skill_resource","按需读取已激活技能中的参考文档、模板或脚本源码；path为技能内相对路径。只支持有界UTF-8文本，不执行脚本。",schema);
    }
    @Override public void validateArguments(JsonNode args,ToolContext context){if(!args.isObject()||args.size()!=2||!args.path("skill").isTextual()||!SkillDefinition.validName(args.path("skill").asText())||!args.path("path").isTextual())throw SkillParser.invalid("Expected {skill: exact-skill-name, path: relative-text-file}.");}
    @Override public String execute(JsonNode args,ToolContext context){validateArguments(args,context);return skills.readResource(args.path("skill").asText(),args.path("path").asText());}
}
