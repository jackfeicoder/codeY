package io.github.jackfeicoder.codey.plan;

import com.fasterxml.jackson.databind.*;
import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.tool.*;
import java.util.*;

/** 两个串行计划控制入口，只修改会话内部计划状态，不复用工作区写工具或授予执行权限。 */
public final class PlanControlTool implements Tool {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final boolean submit;
    public PlanControlTool(boolean submit){this.submit=submit;}
    public ToolAccess access(){return ToolAccess.PLAN_CONTROL;}
    public boolean available(ToolContext context){return context.plan()!=null&&!context.plan().terminal()
            &&(submit?context.workMode()==WorkMode.PLAN&&context.plan().planning:context.workMode()==WorkMode.REACT&&!context.plan().planning);}
    public ToolDefinition definition() {
        var schema=JSON.createObjectNode().put("type","object").put("additionalProperties",false);
        var p=schema.putObject("properties");
        if(submit) {
            p.putObject("title").put("type","string").put("minLength",1).put("maxLength",200);
            var steps=p.putObject("steps").put("type","array").put("minItems",1).put("maxItems",20);
            var item=steps.putObject("items").put("type","object").put("additionalProperties",false);var fields=item.putObject("properties");
            fields.putObject("id").put("type","string").put("pattern","^[A-Za-z][A-Za-z0-9_-]{0,47}$");
            fields.putObject("description").put("type","string").put("minLength",1).put("maxLength",1200);
            fields.putObject("verification").put("type","string").put("minLength",1).put("maxLength",800);
            fields.putObject("kind").put("type","string").putArray("enum").add("ANALYSIS").add("IMPLEMENTATION").add("VERIFICATION");
            fields.putObject("files").put("type","array").put("maxItems",20).putObject("items").put("type","string").put("maxLength",512);
            fields.putObject("dependsOn").put("type","array").put("maxItems",20).put("uniqueItems",true).putObject("items").put("type","string");
            item.putArray("required").add("id").add("description").add("verification").add("kind").add("dependsOn");
            schema.putArray("required").add("title").add("steps");
        }else {
            p.putObject("stepId").put("type","string");
            p.putObject("status").put("type","string").putArray("enum").add("IN_PROGRESS").add("COMPLETED").add("BLOCKED");
            p.putObject("note").put("type","string").put("minLength",1).put("maxLength",1200);
            p.putObject("evidence").put("type","array").put("maxItems",16).put("uniqueItems",true).putObject("items").put("type","string");
            schema.putArray("required").add("stepId").add("status").add("note").add("evidence");
        }
        return new ToolDefinition(submit?"submit_plan":"update_plan",submit
                ?"只读调查充分后提交带依赖和验收条件的计划。宿主校验拓扑、保存新版本并结束本次规划，等待用户审阅；不会自动批准或实施。"
                :"更新宿主指定的当前步骤。完成需引用本执行任务真实成功工具call ID；VERIFICATION还需退出码0且输出完整的命令。COMPLETED/BLOCKED结束本步骤，不再调用业务工具。模型声明不自动证明业务验收。",schema);
    }
    public void validateArguments(JsonNode args,ToolContext context){check(context);parse(args);}
    public PreparedToolOperation prepare(JsonNode args,ToolContext context) {
        check(context);var value=parse(args);var binding=context.plan();
        return new PreparedToolOperation(null,()->{
            try {
                check(context);
                if(submit)binding.submit(value.title(),value.steps());
                else {if(!binding.stepId.equals(value.stepId()))throw new IllegalArgumentException("仅可更新当前步骤。");binding.update(value.status(),value.note(),value.evidence());}
                return "{\"status\":\"recorded\",\"next\":\"host_controls_review_and_execution\"}";
            }catch(IllegalArgumentException invalid){throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS,invalid.getMessage());}
        });
    }
    public String execute(JsonNode args,ToolContext context)throws InterruptedException{return prepare(args,context).execute(context);}
    private void check(ToolContext context){if(!available(context))throw new ToolException(ToolResult.ErrorCode.PERMISSION_DENIED,"Plan control is not available in this host scope.");}
    private record Options(String title,List<PlanStep> steps,String stepId,PlanStep.Status status,String note,List<String> evidence) { }
    private Options parse(JsonNode args) {
        try {
            keys(args,submit?Set.of("title","steps"):Set.of("stepId","status","note","evidence"));
            if(submit) {
                String title=text(args,"title");var nodes=args.get("steps");if(nodes==null||!nodes.isArray()||nodes.isEmpty()||nodes.size()>20)throw new IllegalArgumentException();
                var steps=new ArrayList<PlanStep>();for(var n:nodes){keys(n,Set.of("id","description","verification","kind","files","dependsOn"));steps.add(new PlanStep(text(n,"id"),text(n,"description"),text(n,"verification"),PlanStep.Kind.valueOf(text(n,"kind")),strings(n,"files",true),strings(n,"dependsOn",false)));}
                return new Options(title,steps,null,null,null,List.of());
            }
            return new Options(null,List.of(),text(args,"stepId"),PlanStep.Status.valueOf(text(args,"status")),text(args,"note"),strings(args,"evidence",false));
        }catch(Exception invalid){throw new ToolException(ToolResult.ErrorCode.INVALID_ARGUMENTS,"Invalid plan parameters; check required fields, kinds, dependencies, status and evidence IDs.");}
    }
    private static void keys(JsonNode n,Set<String> allowed){if(n==null||!n.isObject())throw new IllegalArgumentException();n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException();});}
    private static String text(JsonNode n,String key){if(!n.path(key).isTextual())throw new IllegalArgumentException();return n.path(key).asText();}
    private static List<String> strings(JsonNode n,String key,boolean optional){var a=n.get(key);if(a==null&&optional)return List.of();if(a==null||!a.isArray()||a.size()>20)throw new IllegalArgumentException();var out=new ArrayList<String>();for(var s:a){if(!s.isTextual())throw new IllegalArgumentException();out.add(s.asText());}return List.copyOf(out);}
}
