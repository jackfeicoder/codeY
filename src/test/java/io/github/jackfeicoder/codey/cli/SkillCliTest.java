package io.github.jackfeicoder.codey.cli;

import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.observability.DebugPrinter;
import io.github.jackfeicoder.codey.policy.*;
import io.github.jackfeicoder.codey.session.*;
import io.github.jackfeicoder.codey.skill.*;
import io.github.jackfeicoder.codey.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jline.reader.Candidate;
import org.jline.reader.impl.DefaultParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实会话入口、输入解析和存储；模型替身只观察请求，不访问外部账号。 */
class SkillCliTest {
    @TempDir Path root;
    final StringWriter out=new StringWriter(),errors=new StringWriter();
    Path skill()throws Exception {
        Path file=root.resolve("skills/review/SKILL.md");Files.createDirectories(file.getParent());
        Files.writeString(file,"---\nname: review\ndescription: Review code\ndisable-model-invocation: true\n---\nCLI_SKILL_BODY_SENTINEL");return file;
    }
    void run(String script,LlmClient model)throws Exception {
        var tools=new ToolRegistry();var output=new PrintWriter(out);
        var input=new ConsoleInput(new BufferedReader(new StringReader(script)),output);
        try(var skills=new SkillService(new SkillSettings(root.resolve("skills"),2048,8192,8192),tools)) {
            var launcher=new SessionLauncher(root,new SessionSettings(root.resolve("data"),null,false),new JsonSessionStore(root.resolve("data")),
                    p->new SessionLauncher.ModelChoice("fixture model",model,model),()->"base",input,output,new PrintWriter(errors),
                    new SessionLauncher.Options(null,true,"fixture",null,null),tools,DebugPrinter.NONE,List::of,20,new PermissionSettings(PermissionMode.ASK,true)).withSkills(skills);
            assertEquals(0,launcher.run());assertEquals(SkillContext.EMPTY,skills.context());
        }
    }
    @Test void explicitInvocationLoadsBeforeFirstRequestAndDoesNotLeakIntoNextTask()throws Exception {
        skill();var requests=new ArrayList<LlmRequest>();
        run("/skill review check code\nnext task\n/exit\n",request->{requests.add(request);return new LlmResponse(Message.assistant("done"),"stop",null);});
        assertEquals(2,requests.size());assertTrue(requests.get(0).messages().toString().contains("CLI_SKILL_BODY_SENTINEL"));
        assertFalse(requests.get(1).messages().toString().contains("CLI_SKILL_BODY_SENTINEL"));assertEquals("",errors.toString());
        var saved=new JsonSessionStore(root.resolve("data")).list().sessions().get(0);
        assertTrue(saved.messages().stream().anyMatch(m->m.content()!=null&&m.content().contains("使用技能 review")));
        assertFalse(saved.messages().toString().contains("CLI_SKILL_BODY_SENTINEL"));
    }
    @Test void menuSelectionRunsSameActivationPathAndHelpListsBothCommands()throws Exception {
        skill();var requests=new ArrayList<LlmRequest>();
        run("/help\n/skills\n1\ncheck current code\n/exit\n",r->{requests.add(r);return new LlmResponse(Message.assistant("done"),"stop",null);});
        assertEquals(1,requests.size());assertTrue(requests.get(0).messages().toString().contains("CLI_SKILL_BODY_SENTINEL"));assertTrue(out.toString().contains("/skill 名称"));
    }
    @Test void badSkillAndMissingArgumentsDoNotCallModel()throws Exception {
        run("/skill missing\n/skill\n/exit\n",r->{throw new AssertionError("No model call expected");});
        assertTrue(errors.toString().contains("SKILL_NOT_FOUND"));assertTrue(errors.toString().contains("用法"));
    }
    @Test void nameCompletionUsesSkillCatalogAndNeverLoadsModels() {
        var completer=new CommandCompleter(()->{throw new AssertionError("No model config lookup");},()->true,()->List.of("code-review","fix-test"));
        var line=new DefaultParser().parse("/skill co",9);var candidates=new ArrayList<Candidate>();completer.complete(null,line,candidates);
        assertEquals(List.of("code-review"),candidates.stream().map(Candidate::value).toList());
    }
    @Test void settingsResolveConfigDirectoryAndEnvironmentOverridesDotenv()throws Exception {
        Files.writeString(root.resolve(".env"),"CODEY_SKILLS_DIR=local-skills\nCODEY_SKILL_CATALOG_TOKENS=512\n");
        var settings=SkillSettings.load(root,Map.of("CODEY_SKILL_CATALOG_TOKENS","1024"));assertEquals(root.resolve("local-skills"),settings.userDirectory());assertEquals(1024,settings.catalogTokens());
        assertEquals(0,settings.instructionTokens());
        assertEquals(0,SkillSettings.load(root,Map.of("CODEY_SKILL_INSTRUCTION_TOKENS","8192")).instructionTokens());
        assertThrows(ConfigException.class,()->SkillSettings.load(root,Map.of("CODEY_SKILL_RESOURCE_TOKENS","not-a-number")));
    }
    @Test void menuUsesStableDirectoryIdAndDisplaysAuthorNameWithSingleLineSummary()throws Exception {
        Path file=skill();Files.writeString(file,"---\nname: Review Workflow\ndescription: |\n  Review current changes\n  using evidence.\n---\nCLI_SKILL_BODY_SENTINEL");
        var requests=new ArrayList<LlmRequest>();
        run("/skills\n1\ncheck changes\n/exit\n",request->{requests.add(request);return new LlmResponse(Message.assistant("done"),"stop",null);});
        assertTrue(out.toString().contains("review [user] · 显示名称：Review Workflow · Review current changes using evidence."));
        assertEquals(1,requests.size());assertTrue(requests.get(0).messages().toString().contains("CLI_SKILL_BODY_SENTINEL"));
    }
}
