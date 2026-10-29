package io.github.jackfeicoder.codey.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jackfeicoder.codey.config.*;
import io.github.jackfeicoder.codey.context.ContextBudgetPolicy;
import io.github.jackfeicoder.codey.llm.*;
import io.github.jackfeicoder.codey.session.Message;
import io.github.jackfeicoder.codey.workspace.WorkspaceBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真正的v2原子存储、版本/归档和模型操作验收；供应商由本地替身代替。 */
class MemoryConsolidatorTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private static final WorkspaceBinding NONE = WorkspaceBinding.none();
    private JsonMemoryStore store() { return new JsonMemoryStore(root); }
    private MemoryService service() { return new MemoryService(store(),new MemorySettings()); }
    private MemoryConsolidationSettings settings() { return new MemoryConsolidationSettings(true,2,8000,2,2,5,16000,4096); }
    private MemoryConsolidator maintenance() { return new MemoryConsolidator(store(),service(),settings()); }
    private MemoryEntry entry(String text) { return MemoryStoreTest.entry(text); }
    private List<MemoryEntry> seed() {
        var entries = List.of(entry("Java知识解释时附带面试回答与示例"),entry("解释Java概念时补充面试回答示例"),entry("保留独立约束"));
        store().update(MemoryScope.USER,null,old->entries); return entries;
    }
    private MemoryTidyPlan.Operation merge(List<MemoryEntry> entries) {
        return new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.MERGE,List.of(entries.get(0).id(),entries.get(1).id()),
                "Java面试", "Java解释附带面试示例",List.of("Java"),"同一偏好合并",null,null);
    }
    private LlmClient client(MemoryTidyPlan.Operation... operations) throws Exception {
        String json = mapper.writeValueAsString(new MemoryTidyPlan(List.of(operations)));
        return request -> { requests.add(request); return new LlmResponse(Message.assistant(json),"stop",null); };
    }
    private MemoryConsolidator.Outcome tidy(boolean force,LlmClient client) { return maintenance().tidy(NONE,MemoryScope.USER,force,client,ContextBudgetPolicy.unknown(),x->{}); }
    @Test void mergePreservesOmittedEntriesAndCreatesAtomicArchivesAndVersion() throws Exception {
        var original = seed(); var outcome = tidy(false,client(merge(original)));
        assertTrue(outcome.changed(),outcome.detail()); assertEquals(1,requests.size());
        assertTrue(requests.get(0).tools().isEmpty()); assertFalse(requests.get(0).toolCallsAllowed()); assertEquals(4096,requests.get(0).maxOutputTokens());
        var document = store().readDocument(MemoryScope.USER,null);
        assertEquals(2,document.schemaVersion()); assertEquals(2,document.entries().size());
        assertTrue(document.entries().contains(original.get(2))); assertEquals(1,document.archived().size()); assertEquals(1,document.versions().size());
        assertEquals(original.subList(0,2),document.archived().get(0).originals()); assertEquals(original,document.versions().get(0).entries());
        assertEquals(original.get(0).id(),document.archived().get(0).replacement().id());
        assertEquals(2,service().list(NONE).size());
    }
    @Test void restoreGroupRecoversOriginalsAndRollbackRecoversWholeScope() throws Exception {
        var original = seed(); assertTrue(tidy(true,client(merge(original))).changed());
        var archived = maintenance().archives(NONE,MemoryScope.USER).get(0);
        maintenance().restoreArchive(NONE,MemoryScope.USER,archived.id());
        assertEquals(new HashSet<>(original),new HashSet<>(store().load(MemoryScope.USER,null)));
        assertTrue(maintenance().archives(NONE,MemoryScope.USER).isEmpty());
        assertEquals(2,maintenance().versions(NONE,MemoryScope.USER).size());
        String version = maintenance().versions(NONE,MemoryScope.USER).get(0).id();
        store().update(MemoryScope.USER,null,old->List.of(original.get(2)));
        maintenance().rollback(NONE,MemoryScope.USER,version);
        assertEquals(original,store().load(MemoryScope.USER,null));
    }
    @Test void shortenRetainsFullOriginalAndCanBeRestored() throws Exception {
        var original=entry("解释应附带清晰的面试回答示例，不要遗漏关键约束。".repeat(10));
        store().update(MemoryScope.USER,null,old->List.of(original));
        var op=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.SHORTEN,List.of(original.id()),"偏好","解释附面试示例，保留关键约束",List.of(),"精简重复表述",null,null);
        assertTrue(tidy(true,client(op)).changed());
        var archived=maintenance().archives(NONE,MemoryScope.USER).get(0);
        assertEquals(original,archived.originals().get(0));
        maintenance().restoreArchive(NONE,MemoryScope.USER,archived.id()); assertEquals(List.of(original),store().load(MemoryScope.USER,null));
    }
    @Test void archiveRequiresExplicitReplacementAndRestoreDoesNotRemoveSuccessor() throws Exception {
        var old=entry("使用旧缓存"); var raw=entry("已改为新缓存，旧缓存不再使用");
        var next=new MemoryEntry(raw.id(),raw.scope(),null,raw.loading(),raw.title(),raw.content(),raw.tags(),null,raw.createdAt(),"2026-10-02T00:00:00Z");
        store().update(MemoryScope.USER,null,x->List.of(old,next));
        var op=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.ARCHIVE,List.of(old.id()),null,null,null,"明确被新缓存替代",next.id(),"旧缓存不再使用");
        assertTrue(tidy(true,client(op)).changed()); assertEquals(List.of(next),store().load(MemoryScope.USER,null));
        maintenance().restoreArchive(NONE,MemoryScope.USER,maintenance().archives(NONE,MemoryScope.USER).get(0).id());
        assertEquals(Set.of(old,next),new HashSet<>(store().load(MemoryScope.USER,null)));
    }
    @Test void ageOrInactivityIsInsufficientForArchive() throws Exception {
        var entries=seed(); var op=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.ARCHIVE,List.of(entries.get(0).id()),null,null,null,"很久没用",entries.get(1).id(),"Java");
        assertFalse(tidy(true,client(op)).changed()); assertEquals(entries,store().load(MemoryScope.USER,null)); assertTrue(maintenance().versions(NONE,MemoryScope.USER).isEmpty());
    }
    @Test void pinnedRuleMutationIsOnlyReviewWhileOtherSafeOperationsCanApply() throws Exception {
        var original=seed(); var raw=entry("默认中文回答");
        var pin=new MemoryEntry(raw.id(),raw.scope(),null,MemoryLoading.ALWAYS,raw.title(),raw.content(),raw.tags(),null,raw.createdAt(),raw.updatedAt());
        store().update(MemoryScope.USER,null,x->{var all=new ArrayList<>(original);all.add(pin);return all;});
        var edit=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.SHORTEN,List.of(pin.id()),"规则","中文",List.of(),"精简",null,null);
        var result=tidy(true,client(edit,merge(original)));
        assertTrue(result.changed()); assertEquals(1,result.reviews().size()); assertTrue(store().load(MemoryScope.USER,null).contains(pin));
    }
    @Test void invalidOrRepeatedIdsRejectWholePlanAndNoOriginalIsLost() throws Exception {
        var original=seed(); var unknown=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.KEEP,List.of(UUID.randomUUID().toString()),null,null,null,"保留",null,null);
        assertFalse(tidy(true,client(merge(original),unknown)).changed()); assertEquals(original,store().load(MemoryScope.USER,null));
        var repeated=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.REVIEW,List.of(original.get(0).id()),null,null,null,"待检查",null,null);
        assertFalse(tidy(true,client(merge(original),repeated)).changed()); assertEquals(original,store().load(MemoryScope.USER,null));
        assertTrue(maintenance().archives(NONE,MemoryScope.USER).isEmpty());
    }
    @Test void concurrentManualWriteMakesModelPlanStaleWithoutOverwritingIt() throws Exception {
        var original=seed(); String json=mapper.writeValueAsString(new MemoryTidyPlan(List.of(merge(original))));
        var concurrent=entry("另一会话的新决定");
        LlmClient client=request->{store().update(MemoryScope.USER,null,old->{var copy=new ArrayList<>(old);copy.add(concurrent);return copy;});return new LlmResponse(Message.assistant(json),"stop",null);};
        var result=tidy(true,client); assertFalse(result.changed()); assertTrue(result.detail().contains("已被修改"));
        assertEquals(4,store().load(MemoryScope.USER,null).size()); assertTrue(store().load(MemoryScope.USER,null).contains(concurrent));
        assertTrue(maintenance().archives(NONE,MemoryScope.USER).isEmpty());
    }
    @Test void unchangedScopeDoesNotRepeatEvenAfterRestartButManualForceCanRetry() throws Exception {
        seed(); var client=client(); assertTrue(tidy(false,client).attempted());
        assertFalse(tidy(false,client).attempted()); assertEquals(1,requests.size());
        var future=new MemoryConsolidator(store(),service(),settings(),Clock.fixed(Instant.now().plusSeconds(7200),ZoneOffset.UTC));
        assertFalse(future.tidy(NONE,MemoryScope.USER,false,client,ContextBudgetPolicy.unknown(),x->{}).attempted());
        assertTrue(tidy(true,client).attempted()); assertEquals(2,requests.size());
    }
    @Test void cooldownAndMinimumGrowthArePersistent() throws Exception {
        var original=seed(); var client=client(); Instant start=Instant.parse("2026-10-29T00:00:00Z");
        var first=new MemoryConsolidator(store(),service(),settings(),Clock.fixed(start,ZoneOffset.UTC));
        assertTrue(first.tidy(NONE,MemoryScope.USER,false,client,ContextBudgetPolicy.unknown(),x->{}).attempted());
        store().update(MemoryScope.USER,null,old->{var copy=new ArrayList<>(old);copy.add(entry("新增一"));return copy;});
        var later=new MemoryConsolidator(store(),service(),settings(),Clock.fixed(start.plusSeconds(3),ZoneOffset.UTC));
        assertFalse(later.tidy(NONE,MemoryScope.USER,false,client,ContextBudgetPolicy.unknown(),x->{}).attempted());
        store().update(MemoryScope.USER,null,old->{var copy=new ArrayList<>(old);copy.add(entry("新增二"));return copy;});
        assertFalse(first.tidy(NONE,MemoryScope.USER,false,client,ContextBudgetPolicy.unknown(),x->{}).attempted());
        assertTrue(later.tidy(NONE,MemoryScope.USER,false,client,ContextBudgetPolicy.unknown(),x->{}).attempted()); assertEquals(2,requests.size());
    }
    @Test void inputBudgetBlocksBeforeModelAndDisabledOrLowWaterlineDoesNotCall() throws Exception {
        seed(); var client=client();
        var normal=new MemoryConsolidator(store(),service(),new MemoryConsolidationSettings());
        assertFalse(normal.tidy(NONE,MemoryScope.USER,false,client,ContextBudgetPolicy.unknown(),x->{}).attempted());
        var tiny=new ContextBudgetPolicy(new ModelCapabilities(128L,1L,1L),new ContextBudgetSettings());
        assertFalse(maintenance().tidy(NONE,MemoryScope.USER,true,client,tiny,x->{}).attempted()); assertTrue(requests.isEmpty());
        assertEquals(1,store().readDocument(MemoryScope.USER,null).revision());
    }
    @Test void failedAndTruncatedResponsesPreserveMemoryAndDoNotRetryUnchanged() throws Exception {
        var original=seed(); LlmClient bad=request->{requests.add(request);throw new LlmException(LlmException.Kind.HTTP,"SECRET");};
        var first=tidy(false,bad); assertFalse(first.changed()); assertFalse(first.detail().contains("SECRET"));
        assertFalse(tidy(false,bad).attempted()); assertEquals(1,requests.size()); assertEquals(original,store().load(MemoryScope.USER,null));
        LlmClient truncated=request->new LlmResponse(Message.assistant("{\"operations\":[]}"),"length",null);
        assertFalse(tidy(true,truncated).changed()); assertTrue(maintenance().versions(NONE,MemoryScope.USER).isEmpty());
    }
    @Test void timeoutCancelsModelAndLateOutputCannotCommit() throws Exception {
        var original=seed();var cancelled=new CountDownLatch(1);
        var fast=new MemoryConsolidator(store(),service(),new MemoryConsolidationSettings(true,2,8000,0,1,1,16000,4096));
        LlmClient slow=request->{try{Thread.sleep(30000);}catch(InterruptedException e){cancelled.countDown();}return new LlmResponse(Message.assistant("{\"operations\":[]}"),"stop",null);};
        assertTrue(fast.tidy(NONE,MemoryScope.USER,true,slow,ContextBudgetPolicy.unknown(),x->{}).detail().contains("超时"));
        assertTrue(cancelled.await(2,TimeUnit.SECONDS)); assertEquals(original,store().load(MemoryScope.USER,null));
    }
    @Test void archiveRestoreRefusesToOverwriteNewManualEdit() throws Exception {
        var original=seed(); assertTrue(tidy(true,client(merge(original))).changed());
        var archive=maintenance().archives(NONE,MemoryScope.USER).get(0);
        service().remember(NONE,MemoryScope.USER,MemoryLoading.ON_DEMAND,"手动修改","新的用户要求",List.of(),null,archive.replacement().id());
        var before=store().readDocument(MemoryScope.USER,null);
        assertThrows(MemoryException.class,()->maintenance().restoreArchive(NONE,MemoryScope.USER,archive.id()));
        assertEquals(before,store().readDocument(MemoryScope.USER,null));
    }
    @Test void recentVersionsAreBoundedAndAlwaysRecoverable() throws Exception {
        var first=entry("x".repeat(100));store().update(MemoryScope.USER,null,x->List.of(first));
        for(int length:new int[]{80,70,60,50,40,30}){
            var op=new MemoryTidyPlan.Operation(MemoryTidyPlan.Action.SHORTEN,List.of(first.id()),"title","x".repeat(length),List.of(),"精简",null,null);
            assertTrue(tidy(true,client(op)).changed());
        }
        assertEquals(5,maintenance().versions(NONE,MemoryScope.USER).size());assertEquals(6,maintenance().archives(NONE,MemoryScope.USER).size());
    }
    @Test void v1LoadsWithoutWritingAndMigratesOnFirstSuccessfulChange() throws Exception {
        var original=List.of(entry("旧格式"));Files.createDirectories(root.resolve("memory"));
        var json=mapper.createObjectNode().put("schemaVersion",1);json.set("entries",mapper.valueToTree(original));
        Files.writeString(root.resolve("memory/user.json"),json.toString());
        assertEquals(original,store().load(MemoryScope.USER,null));assertEquals(1,store().readDocument(MemoryScope.USER,null).schemaVersion());
        store().update(MemoryScope.USER,null,old->old);
        assertEquals(2,store().readDocument(MemoryScope.USER,null).schemaVersion());assertEquals(original,store().load(MemoryScope.USER,null));
    }
}
