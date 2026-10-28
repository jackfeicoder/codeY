package io.github.jackfeicoder.codey.memory;

import io.github.jackfeicoder.codey.config.MemorySettings;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 跨会话作用域、显式更新、重复检测和整体Token预算。 */
class MemoryServiceTest {
    @TempDir Path root;
    private MemoryService service(MemorySettings settings) { return new MemoryService(new JsonMemoryStore(root.resolve("data")), settings); }
    private WorkspaceBinding workspace(String name) throws Exception {
        var dir = root.resolve(name); Files.createDirectories(dir);
        return new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, dir.toString());
    }
    private MemoryEntry save(MemoryService service, WorkspaceBinding workspace, MemoryScope scope, MemoryLoading loading, String text) {
        return service.remember(workspace, scope, loading, null, text, List.of(), null, null);
    }
    @Test void userSharedProjectIsolatedAndScopeStableAcrossServiceRestart() throws Exception {
        var service = service(new MemorySettings()); var a = workspace("a"); var b = workspace("b");
        var user = save(service, a, MemoryScope.USER, MemoryLoading.ALWAYS, "默认中文回答");
        var project = save(service, a, MemoryScope.PROJECT, MemoryLoading.ON_DEMAND, "Redis旧客户端兼容背景");
        assertEquals(Set.of(user, project), new HashSet<>(service(new MemorySettings()).list(a)));
        assertEquals(List.of(user), service.list(b));
        assertEquals(List.of(user), service.list(WorkspaceBinding.none()));
        assertEquals(MemoryService.projectId(a), MemoryService.projectId(new WorkspaceBinding(WorkspaceBinding.Mode.DIRECTORY, Path.of(a.path()).resolve(".").toString())));
        assertNotEquals(MemoryService.projectId(a), MemoryService.projectId(b));
        assertThrows(MemoryException.class, () -> service.forget(b, project.id()));
    }
    @Test void duplicateRejectedAndExplicitUpdateKeepsIdCreationAndSingleEntry() throws Exception {
        var service = service(new MemorySettings()); var a = workspace("a");
        var saved = save(service, a, MemoryScope.USER, MemoryLoading.ON_DEMAND, "Use Java");
        assertThrows(MemoryException.class, () -> save(service, a, MemoryScope.USER, MemoryLoading.ALWAYS, "use   java"));
        var updated = service.remember(a, MemoryScope.USER, MemoryLoading.ALWAYS, "语言偏好", "Use Chinese", List.of("中文"), null, saved.id());
        assertEquals(saved.id(), updated.id()); assertEquals(saved.createdAt(), updated.createdAt());
        assertEquals(List.of(updated), service.list(a));
        service.forget(a, saved.id()); assertTrue(service.list(a).isEmpty());
        assertTrue(service(new MemorySettings()).select(a, "Chinese", 3000).always().isEmpty());
    }
    @Test void projectAlwaysAndMissingWorkspaceAndCrossScopeUpdateAreRejected() throws Exception {
        var service = service(new MemorySettings()); var a = workspace("a");
        assertThrows(MemoryException.class, () -> save(service, a, MemoryScope.PROJECT, MemoryLoading.ALWAYS, "rule"));
        assertThrows(MemoryException.class, () -> save(service, WorkspaceBinding.none(), MemoryScope.PROJECT, MemoryLoading.ON_DEMAND, "rule"));
        var user = save(service, a, MemoryScope.USER, MemoryLoading.ON_DEMAND, "text");
        assertThrows(MemoryException.class, () -> service.remember(a, MemoryScope.PROJECT, MemoryLoading.ON_DEMAND, null, "update", List.of(), null, user.id()));
        assertThrows(MemoryException.class, () -> service.forget(a, user.id().substring(0, 8)));
    }
    @Test void exceedingAlwaysBudgetRejectsWriteAndPreservesPrevious() throws Exception {
        var service = service(new MemorySettings(128, 2000, 5)); var a = workspace("a");
        assertThrows(MemoryException.class, () -> save(service, a, MemoryScope.USER, MemoryLoading.ALWAYS, "中".repeat(300)));
        assertTrue(service.list(a).isEmpty());
        save(service, a, MemoryScope.USER, MemoryLoading.ON_DEMAND, "中".repeat(300));
        assertEquals(1, service.list(a).size());
    }
    @Test void recallUsesQueryAndBudgetButAlwaysIsNeverSilentlyTruncated() throws Exception {
        var service = service(new MemorySettings()); var a = workspace("a");
        var always = save(service, a, MemoryScope.USER, MemoryLoading.ALWAYS, "默认中文");
        var java = save(service, a, MemoryScope.USER, MemoryLoading.ON_DEMAND, "Java线程池附带面试示例");
        save(service, a, MemoryScope.PROJECT, MemoryLoading.ON_DEMAND, "Redis兼容旧客户端");
        var result = service.select(a, "线程池", 3000);
        assertEquals(List.of(always), result.always()); assertEquals(List.of(java), result.recalled());
        assertTrue(result.alwaysTokens() <= 1000); assertTrue(result.recalledTokens() <= 2000);
        var tight = service.select(a, "线程池", 0);
        assertEquals(List.of(always), tight.always()); assertTrue(tight.recalled().isEmpty());
        assertTrue(service.select(a, "astronomy", 3000).recalled().isEmpty());
        assertTrue(service.select(a, "", 3000).recalled().isEmpty());
    }
    @Test void recallCountAndDedupAndZeroRecallBudgetAreEnforced() throws Exception {
        var service = service(new MemorySettings(1000, 2000, 1)); var a = workspace("a");
        save(service, a, MemoryScope.USER, MemoryLoading.ON_DEMAND, "Java示例A");
        save(service, a, MemoryScope.PROJECT, MemoryLoading.ON_DEMAND, "Java示例A");
        save(service, a, MemoryScope.PROJECT, MemoryLoading.ON_DEMAND, "Java示例B");
        assertEquals(1, service.select(a, "Java", 10000).recalled().size());
        assertTrue(service(new MemorySettings(1000, 0, 5)).select(a, "Java", 10000).recalled().isEmpty());
    }
    @Test void reducingAlwaysLimitOnRestartFailsExplicitlyRatherThanTruncating() throws Exception {
        var a = workspace("a"); var service = service(new MemorySettings());
        save(service, a, MemoryScope.USER, MemoryLoading.ALWAYS, "常驻规则".repeat(20));
        var tight = service(new MemorySettings(128, 2000, 5));
        assertThrows(MemoryException.class, () -> tight.select(a, "query", 3000));
        assertEquals(1, tight.list(a).size());
    }
}
