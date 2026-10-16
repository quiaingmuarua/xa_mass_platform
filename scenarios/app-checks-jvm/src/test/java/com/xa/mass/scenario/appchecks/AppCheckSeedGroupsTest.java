package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.result.TaskResultRecord;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static com.xa.mass.scenario.appchecks.AppCheckTaskServiceTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppCheckSeedGroupsTest {
    private ProjectTaskQueryService.Entry entry(int size) {
        var meta = metadata();
        meta.remove("country");
        meta.put("checkType", AppCheckContracts.MD5_PREFIX_BATCH);
        meta.put("checkTypeLabel", "MD5 batch"); meta.put("checkLabel", "MD5");
        meta.put("seedType", "text"); meta.put("seedsPerItem", "" + size);
        meta.put("eventCode", "extension.worker.string.md5.batch"); meta.put("contractParameters", "{}");
        return new ProjectTaskQueryService.Entry("task", 1L, new TaskView("task", "app-checks", "demo-sim",
                "CLOSE_WHEN_IDLE", List.of(), Map.of(), "batch", meta), "pre_review", null);
    }
    private AppCheckTaskSnapshot snapshot(int size) {
        var task = entry(size).task();
        return AppCheckTaskSnapshot.read(task.workerGroupId(), task.metadata(), AppCheckTestSettings.contracts());
    }
    @Test void twentyThreeSeedsProduceTwentyThreeOrThreeItemsAndReplayKeepsCompleteGroups() {
        for (int size : List.of(1, 10)) {
            var fixture = new AppCheckTaskServiceTest();
            try (var service = fixture.service()) {
                when(fixture.queries.get(CALLER, "app-checks", "task")).thenReturn(entry(size));
                var stored = new LinkedHashMap<String, TaskItemRequest>();
                when(fixture.data.importFiniteTaskItems(eq(CALLER), eq("app-checks"), eq("task"), anyList())).thenAnswer(call -> {
                    List<TaskItemRequest> items = call.getArgument(3);
                    var effects = new LinkedHashMap<String, ActionOutcome>();
                    for (var item : items) {
                        var old = stored.putIfAbsent(item.messageId(), item);
                        if (old != null) assertThat(item).isEqualTo(old);
                        effects.put(item.messageId(), old == null ? ActionOutcome.applied() : ActionOutcome.unchanged());
                    }
                    return effects;
                });
                var seeds = IntStream.range(0, 23).mapToObj(i -> "seed-" + i).toList();
                String input = "\uFEFF" + String.join("\n", seeds) + "\n\nseed-0\n";
                var first = service.importSeeds(CALLER, "task", file(input));
                assertThat(first.uniqueCount()).isEqualTo(23);
                assertThat(first.duplicateCount()).isEqualTo(1);
                assertThat(first.itemCount()).isEqualTo(size == 1 ? 23 : 3);
                assertThat(first.addedCount()).isEqualTo(first.itemCount());
                var original = new LinkedHashMap<>(stored);
                var replay = service.importSeeds(CALLER, "task", file(input));
                assertThat(replay.addedCount()).isZero(); assertThat(replay.existingCount()).isEqualTo(first.itemCount());
                assertThat(stored).isEqualTo(original);
                assertThat(stored.values().stream().flatMap(item -> ((List<String>) item.payload().get("values")).stream()).toList()).isEqualTo(seeds);
                if (size == 10) {
                    assertThat(stored.values().stream().map(item -> ((List<?>) item.payload().get("values")).size()).toList()).containsExactly(10, 10, 3);
                    var overlap = service.importSeeds(CALLER, "task", file("seed-0\nseed-22"));
                    assertThat(overlap.itemCount()).isEqualTo(1); assertThat(overlap.addedCount()).isEqualTo(1);
                    assertThat(stored).hasSize(4);
                } else assertThat(stored.keySet()).containsExactlyElementsOf(seeds.stream().map(AppCheckContract.SeedType.TEXT::messageId).toList());
            }
        }
    }
    @Test void groupIdentityIncludesOrderTypeAndTailWithoutAmbiguousConcatenation() {
        var grouped = snapshot(10);
        assertThat(grouped.messageId(List.of("a", "bc"))).isNotEqualTo(grouped.messageId(List.of("ab", "c")))
                .isNotEqualTo(grouped.messageId(List.of("bc", "a")));
        assertThat(grouped.messageId(List.of("a"))).startsWith("group-").isNotEqualTo(snapshot(1).messageId(List.of("a")));
        assertThatThrownBy(() -> grouped.payload(List.of("a", "a"))).hasMessageContaining("duplicates");
        assertThat(grouped.payload(List.of("a"))).isEqualTo(Map.of("values", List.of("a")));
    }
    @Test void completeValidationPrecedesWritesAndPartialReplayPreservesTheOriginalBoundaries() {
        var fixture = new AppCheckTaskServiceTest();
        try (var service = fixture.service()) {
            when(fixture.queries.get(CALLER, "app-checks", "task")).thenReturn(entry(10));
            assertThatThrownBy(() -> service.importSeeds(CALLER, "task", new java.io.ByteArrayInputStream(new byte[]{'a', '\n', (byte) 0xc3, 0x28}))).hasMessageContaining("UTF-8");
            verify(fixture.data, never()).importFiniteTaskItems(any(), anyString(), anyString(), anyList());
            var stored = new LinkedHashMap<String, TaskItemRequest>();
            int[] calls = {0};
            when(fixture.data.importFiniteTaskItems(eq(CALLER), eq("app-checks"), eq("task"), anyList())).thenAnswer(call -> {
                if (++calls[0] == 2) throw new IllegalStateException("unconfirmed");
                List<TaskItemRequest> items = call.getArgument(3);
                assertThat(items.size()).isLessThanOrEqualTo(100);
                var effects = new LinkedHashMap<String, ActionOutcome>();
                for (var item : items) effects.put(item.messageId(), stored.putIfAbsent(item.messageId(), item) == null ? ActionOutcome.applied() : ActionOutcome.unchanged());
                return effects;
            });
            String input = String.join("\n", IntStream.range(0, 1001).mapToObj(i -> "seed-" + i).toList());
            assertThatThrownBy(() -> service.importSeeds(CALLER, "task", file(input))).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                    failure -> assertThat(failure.confirmedAdded).isEqualTo(100));
            var before = new LinkedHashMap<>(stored);
            var retry = service.importSeeds(CALLER, "task", file(input));
            assertThat(retry.itemCount()).isEqualTo(101); assertThat(retry.existingCount()).isEqualTo(100); assertThat(retry.addedCount()).isEqualTo(1);
            assertThat(stored).containsAllEntriesOf(before).hasSize(101);
            try (var normalized = AppCheckSeedFile.read(file(input), AppCheckContracts.md5PrefixBatch(), null)) {
                var assembled = normalized.prepareGroups(snapshot(10)); var path = assembled.file();
                assertThat(Files.readAllLines(path)).hasSize(101); assembled.close(); assertThat(path).doesNotExist();
            }
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private TaskDataService.ResultEntry result(List<String> seeds, List<Map<String, Object>> answers) {
        var snapshot = snapshot(100);
        var item = new TaskItem(snapshot.messageId(seeds), snapshot.eventCode(), 1L, snapshot.payload(seeds), 5, 600000L, new WorkerQuery("worker.any", Map.of()));
        return new TaskDataService.ResultEntry(item.messageId(), item, TaskItemResultResponse.succeeded(Jsons.toJson(Map.of("results", answers))));
    }
    private Map<String, Object> answer(String seed) { return Map.of("input", seed, "valid", true, "md5", seed.equals("a") ? "0cc175b9c0f1b6a831c399e269772661" : "900150983cd24fb0d6963f7d28e17f72"); }
    @Test void unorderedAnswersExpandInInputOrderAndEveryDamagedGroupIsExcludedFromCsv() throws Exception {
        var fixture = new AppCheckTaskServiceTest();
        try (var service = fixture.service()) {
            when(fixture.queries.get(CALLER, "app-checks", "task")).thenReturn(entry(100));
            var seeds = List.of("a", "abc");
            var good = result(seeds, List.of(answer("abc"), answer("a")));
            var failed = new TaskDataService.ResultEntry(good.messageId(), good.item(), TaskItemResultResponse.failed());
            var bad = List.of(result(seeds, List.of(answer("a"))), result(seeds, List.of(answer("a"), answer("a"))),
                    result(seeds, List.of(answer("a"), answer("other"))), result(seeds, List.of(answer("a"), Map.of("input", "abc", "valid", true, "md5", "broken"))));
            var entries = new ArrayList<>(List.of(good)); entries.addAll(bad); entries.add(failed);
            when(fixture.data.previewTaskResults(CALLER, "task")).thenReturn(new TaskDataService.ResultPreview(entries, false));
            var rows = (List<Map<String,Object>>) service.results(CALLER, "task").get("results");
            assertThat(rows).hasSize(12);
            assertThat(rows.get(0)).containsEntry("seed", "a").containsEntry("matched", true);
            assertThat(rows.get(1)).containsEntry("seed", "abc").containsEntry("matched", false);
            for (var row : rows.subList(2, 10)) assertThat(row).containsKey("contentError").doesNotContainKey("matched");
            for (var row : rows.subList(10, 12)) assertThat(row).containsEntry("resultStatus", "failed").doesNotContainKey("matched");
            doAnswer(call -> {
                Consumer<TaskResultRecord> consumer = call.getArgument(2);
                for (var entry : entries) consumer.accept(new TaskResultRecord(entry.messageId(),
                        entry.result().status() == TaskItemResultStatus.FAILED ? com.xa.mass.kernel.task.TaskRuntime.TaskItemResult.failed()
                                : com.xa.mass.kernel.task.TaskRuntime.TaskItemResult.succeeded(entry.result().opaqueResultPayload()), entry.item()));
                return null;
            }).when(fixture.exports).forEachResult(eq(CALLER), eq("task"), any());
            for (String filter : List.of("all", "matched", "unmatched")) {
                var csv = service.export(CALLER, "task", filter);
                try { assertThat(csv.count()).isEqualTo(filter.equals("all") ? 2 : 1); }
                finally { Files.deleteIfExists(csv.file()); }
            }
        }
    }
    @Test void previewCapsBusinessRowsEvenWhenTheOwnerItemPreviewWasNotTruncated() {
        var fixture = new AppCheckTaskServiceTest();
        try (var service = fixture.service()) {
            when(fixture.queries.get(CALLER, "app-checks", "task")).thenReturn(entry(100));
            var seeds = IntStream.range(0, 100).mapToObj(i -> "seed-" + i).toList();
            var first = result(seeds, seeds.stream().map(this::answer).toList());
            when(fixture.data.previewTaskResults(CALLER, "task")).thenReturn(new TaskDataService.ResultPreview(List.of(first, result(List.of("tail"), List.of(answer("tail")))), false));
            var preview = service.results(CALLER, "task");
            assertThat((List<?>) preview.get("results")).hasSize(100); assertThat(preview).containsEntry("truncated", true);
            when(fixture.data.previewTaskResults(CALLER, "task")).thenReturn(new TaskDataService.ResultPreview(List.of(first), false));
            assertThat(service.results(CALLER, "task")).containsEntry("truncated", false);
        }
    }
}
