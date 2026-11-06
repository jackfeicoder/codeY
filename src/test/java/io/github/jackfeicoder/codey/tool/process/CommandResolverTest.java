package io.github.jackfeicoder.codey.tool.process;

import io.github.jackfeicoder.codey.tool.ToolException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 用真实子 JVM 保留环境键的拼写，覆盖 Windows Path/PATH 差异；不执行外部运行时。 */
class CommandResolverTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(strings={"PATH","Path"})
    void programLookupUsesPlatformEnvironmentKeyRules(String key)throws Exception {
        boolean windows=CommandResolver.windows();
        Path fixture=temp.resolve(windows?"fixture-command.exe":"fixture-command");Files.writeString(fixture,"fixture");
        if(!windows)assertTrue(fixture.toFile().setExecutable(true));
        String java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java").toString();
        var builder=new ProcessBuilder(java,"-Dfile.encoding=UTF-8","-cp",System.getProperty("java.class.path"),Probe.class.getName());
        builder.environment().keySet().removeIf(name->name.equalsIgnoreCase("PATH"));builder.environment().put(key,temp.toString());
        var process=builder.redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(15,TimeUnit.SECONDS));String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertEquals(0,process.exitValue(),output);
            assertTrue(output.contains(windows||key.equals("PATH")?"RESOLVED":"NOT_FOUND"),output);
        }finally{process.destroyForcibly();}
    }
    public static class Probe {
        public static void main(String[] args) {
            try {CommandResolver.resolve("fixture-command",List.of(),Path.of("").toAbsolutePath());System.out.println("RESOLVED");}
            catch(ToolException missing){System.out.println("NOT_FOUND");}
        }
    }
}
