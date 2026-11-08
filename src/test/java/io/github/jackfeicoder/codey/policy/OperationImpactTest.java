package io.github.jackfeicoder.codey.policy;

import io.github.jackfeicoder.codey.agent.WorkMode;
import io.github.jackfeicoder.codey.tool.*;
import io.github.jackfeicoder.codey.tool.builtin.*;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 权限边界回归：普通编辑放行不等于任意命令、清空文件或跨范围授权。 */
class OperationImpactTest {
    @TempDir Path temp;
    private Path project() throws Exception { return Files.createDirectories(temp.resolve("project")).toRealPath(); }
    private Path executable(String name) throws Exception { return Files.writeString(Files.createDirectories(temp.resolve("installed")).resolve(name), "fixture"); }
    private PermissionService service(PermissionMode mode, ApprovalHandler handler) throws Exception {
        return new PermissionService(mode,new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,project().toString()),handler);
    }
    private ApprovalRequest request(OperationImpact impact) {
        return new ApprovalRequest("execute_command",null,null,"execute_command",null,null,0,0,false,false,"fixture",false,"action").withImpact(impact);
    }
    @ParameterizedTest @ValueSource(strings={"status","diff","log"})
    void restrictedGitQueriesDisableExecutableHelpers(String subcommand) throws Exception {
        var c = CommandImpactClassifier.classify(executable("git.exe"),List.of(subcommand),project(),project());
        assertEquals(OperationImpact.Kind.LOCAL_QUERY,c.impact().kind());
        assertTrue(c.args().contains("--no-pager")); assertTrue(c.args().contains("core.fsmonitor=false"));
        assertTrue(c.args().contains("log.showSignature=false"));
        if (subcommand.equals("diff")) { assertTrue(c.args().contains("--no-ext-diff")); assertTrue(c.args().contains("--no-textconv")); }
    }
    @ParameterizedTest @ValueSource(strings={"--ext-diff","--textconv","--output=outside","--no-index","--config=evil"})
    void unknownGitArgumentsNeverAcquireQueryPrivilege(String flag) throws Exception {
        assertEquals(OperationImpact.Kind.UNKNOWN,CommandImpactClassifier.classify(executable("git.exe"),List.of("diff",flag),project(),project()).impact().kind());
    }
    @Test void projectLocalLookalikeExecutableIsNotTrustedQuery() throws Exception {
        var local=Files.writeString(project().resolve("git.exe"),"fixture");
        assertEquals(OperationImpact.Kind.UNKNOWN,CommandImpactClassifier.classify(local,List.of("status"),project(),project()).impact().kind());
    }
    @Test void buildNeedsConfirmationAndOnlyReusesSameTaskSameConfiguration() throws Exception {
        var root=project(); Files.writeString(root.resolve("pom.xml"),"<project/>"); var exe=executable("mvn.cmd");
        var count=new AtomicInteger(); var service=service(PermissionMode.ASK,r->{count.incrementAndGet();return ApprovalHandler.Answer.APPROVE;});
        var first=request(CommandImpactClassifier.classify(exe,List.of("test"),root,root).impact());
        service.beginTask(); service.authorize(first); service.completed(first); service.authorize(first); assertEquals(1,count.get());
        Files.writeString(root.resolve("pom.xml"),"<project><name>changed</name></project>");
        var changed=request(CommandImpactClassifier.classify(exe,List.of("test"),root,root).impact());
        assertNotEquals(first.impact().reuseKey(),changed.impact().reuseKey());
        service.authorize(changed);service.completed(changed);assertEquals(2,count.get());
        service.endTask();service.beginTask();service.authorize(changed);assertEquals(3,count.get());
    }
    @Test void autoBuildRequiresExplicitProjectTrustAndInvalidatesAfterScriptChange() throws Exception {
        var root=project(); Files.writeString(root.resolve("package.json"),"{\"scripts\":{\"test\":\"fixture\"}}");
        var exe=executable("npm.cmd"); var count=new AtomicInteger();
        var service=service(PermissionMode.AUTO,r->{count.incrementAndGet();return ApprovalHandler.Answer.UNAVAILABLE;});
        var build=request(CommandImpactClassifier.classify(exe,List.of("test"),root,root).impact());
        assertThrows(ToolException.class,()->service.authorize(build)); assertEquals(1,count.get());
        service.trustProject();service.authorize(build);assertEquals(1,count.get());
        Files.writeString(root.resolve("build.js"),"new workflow");assertFalse(service.projectTrusted());
        var changed=request(CommandImpactClassifier.classify(exe,List.of("test"),root,root).impact());
        assertThrows(ToolException.class,()->service.authorize(changed)); assertEquals(2,count.get());
        service.revoke();assertFalse(service.projectTrusted());
    }
    @Test void interpreterAndAdditionalBuildArgumentsStayUnknown() throws Exception {
        Files.writeString(project().resolve("pom.xml"),"<project/>");
        assertEquals(OperationImpact.Kind.UNKNOWN,CommandImpactClassifier.classify(executable("python.exe"),List.of("script.py"),project(),project()).impact().kind());
        assertEquals(OperationImpact.Kind.UNKNOWN,CommandImpactClassifier.classify(executable("mvn.cmd"),List.of("test","-f","outside.xml"),project(),project()).impact().kind());
    }
    @Test void networkApprovalHasSessionScopeButSensitiveRequestsAlwaysAskAgain() throws Exception {
        var count=new AtomicInteger();var service=service(PermissionMode.ASK,r->{count.incrementAndGet();return ApprovalHandler.Answer.APPROVE;});
        var impact=new OperationImpact(OperationImpact.Kind.PUBLIC_NETWORK,"public origin A","origin-A",false);
        var first=request(impact);service.authorize(first);service.completed(first);service.endTask();service.beginTask();service.authorize(first);
        assertEquals(1,count.get());
        service.authorize(request(new OperationImpact(OperationImpact.Kind.PUBLIC_NETWORK,"origin B","origin-B",false)));
        assertEquals(2,count.get());
        var secret=new ApprovalRequest("web_search",null,null,"web_search",null,null,0,0,true,false,"hidden",false,"secret",impact);
        service.authorize(secret);assertEquals(3,count.get());
        service.revoke();service.authorize(first);assertEquals(4,count.get());
    }
    @Test void failedExecutionDoesNotCreateReusableScope() throws Exception {
        var service=service(PermissionMode.ASK,r->ApprovalHandler.Answer.APPROVE);
        var context=new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,project().toString()),16000,WorkMode.REACT,service);
        assertThrows(IllegalStateException.class,()->new PreparedToolOperation(request(new OperationImpact(OperationImpact.Kind.PUBLIC_NETWORK,"read","origin",false)),()->{throw new IllegalStateException();}).execute(context));
        assertEquals(0,service.grantCount());
    }
    @Test void harmlessManifestMetadataAllowedButScriptAndDependencyChangesReviewed() {
        assertTrue(FileImpactClassifier.metadataOnly("package.json","{\"name\":\"a\",\"scripts\":{}}","{\"name\":\"b\",\"scripts\":{}}"));
        assertFalse(FileImpactClassifier.metadataOnly("package.json","{\"scripts\":{}}","{\"scripts\":{\"test\":\"upload\"}}"));
        assertFalse(FileImpactClassifier.metadataOnly("package.json","{}","{\"dependencies\":{\"new\":\"1\"}}"));
        assertTrue(FileImpactClassifier.metadataOnly("pom.xml","<project><name>a</name></project>","<project><name>b</name></project>"));
        assertFalse(FileImpactClassifier.metadataOnly("pom.xml","<project/>","<!DOCTYPE x SYSTEM 'file:///fixture'><project/>"));
    }
    @Test void askRechecksDestructionAfterApprovingSensitiveEdit() throws Exception {
        var root=project();Files.writeString(root.resolve(".env"),"fixture=old");var count=new AtomicInteger();
        var service=service(PermissionMode.ASK,r->count.incrementAndGet()==1?ApprovalHandler.Answer.APPROVE:ApprovalHandler.Answer.DENY);
        var context=new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,root.toString()),16000,WorkMode.REACT,service);
        var json=new ObjectMapper();new WriteFileTool().execute(json.readTree("{\"path\":\".env\",\"content\":\"fixture=new\",\"overwrite\":true}"),context);
        assertThrows(ToolException.class,()->new WriteFileTool().execute(json.readTree("{\"path\":\".env\",\"content\":\"\",\"overwrite\":true}"),context));
        assertEquals(2,count.get());assertEquals("fixture=new",Files.readString(root.resolve(".env")));
    }
    @Test void unknownOperationsNeverAcquireReusableApprovalEvenWithScopeKey() throws Exception {
        var count=new AtomicInteger();var service=service(PermissionMode.ASK,r->{count.incrementAndGet();return ApprovalHandler.Answer.APPROVE;});
        var unknown=request(new OperationImpact(OperationImpact.Kind.UNKNOWN,"unknown","claimed-scope",false));
        service.authorize(unknown);service.completed(unknown);service.authorize(unknown);
        assertEquals(2,count.get());assertEquals(0,service.grantCount());
    }
    @Test void realGitStatusDoesNotAutoRunConfiguredCleanFilters() throws Exception {
        var root=project(); var git=io.github.jackfeicoder.codey.tool.process.CommandResolver.resolve("git",List.of(),root).executable();
        var init=new ProcessBuilder(git.toString(),"init","-q").directory(root.toFile()).start();assertEquals(0,init.waitFor());
        var configure=new ProcessBuilder(git.toString(),"config","filter.fixture.clean","echo should-not-run").directory(root.toFile()).start();assertEquals(0,configure.waitFor());
        assertFalse(GitQueryGuard.noExecutableFilters(git,root));
        var service=service(PermissionMode.ASK,ApprovalHandler.unavailable());
        var context=new ToolContext(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY,root.toString()),16000,WorkMode.REACT,service);
        var args=new ObjectMapper().createObjectNode().put("command",git.toString());args.putArray("args").add("status").add("--short");
        var failure=assertThrows(ToolException.class,()->new ExecuteCommandTool().execute(args,context));assertEquals(ToolResult.ErrorCode.APPROVAL_REQUIRED,failure.code());
        var unset=new ProcessBuilder(git.toString(),"config","--unset","filter.fixture.clean").directory(root.toFile()).start();assertEquals(0,unset.waitFor());
        // 宿主若另有全局过滤器，也继续要求审批，不把本机配置当作无条件可信。
        if (GitQueryGuard.noExecutableFilters(git,root)) {
            String result=new ExecuteCommandTool().execute(args,context);
            assertEquals(0,new ObjectMapper().readTree(result).path("exitCode").asInt(-1));
        }
    }
}
