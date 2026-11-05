package io.github.jackfeicoder.codey.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.jackfeicoder.codey.tool.*;

/** 模型只能按名称激活目录内允许自动调用的技能；不能提交路径或 explicit 标志。 */
public final class LoadSkillTool implements Tool {
    private final SkillService skills;
    public LoadSkillTool(SkillService skills){this.skills=skills;}
    @Override public ToolAccess access(){return ToolAccess.READ_ONLY;}
    @Override public ToolDefinition definition(){
        var schema=JsonNodeFactory.instance.objectNode().put("type","object").put("additionalProperties",false);
        schema.putObject("properties").putObject("name").put("type","string");schema.putArray("required").add("name");
        return new ToolDefinition("load_skill","按当前技能目录中的准确名称加载操作手册。正文在下一次模型请求注入，不执行脚本，不授予权限；相同任务无需重复加载。",schema);
    }
    @Override public void validateArguments(JsonNode args,ToolContext context){if(!args.isObject()||args.size()!=1||!args.path("name").isTextual()||!SkillDefinition.validName(args.path("name").asText()))throw SkillParser.invalid("Expected {name: exact-skill-name}.");}
    @Override public String execute(JsonNode args,ToolContext context){validateArguments(args,context);return skills.activate(args.path("name").asText(),false);}
}
