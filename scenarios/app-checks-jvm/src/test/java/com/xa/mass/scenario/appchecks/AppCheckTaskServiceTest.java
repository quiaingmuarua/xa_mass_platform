package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.project.*;
import com.xa.mass.server.task.*;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppCheckTaskServiceTest {
    final ProjectDirectory projects = mock(ProjectDirectory.class);
    final ProjectTaskQueryService queries = mock(ProjectTaskQueryService.class);
    final TaskCreationService creation = mock(TaskCreationService.class);
    final TaskDataService data = mock(TaskDataService.class);
    final TaskLifecycleService lifecycle = mock(TaskLifecycleService.class);
    final OperationGuard operations = new OperationGuard();
    final TaskResultsExportService exports = mock(TaskResultsExportService.class);

    AppCheckTaskService service() {
        when(creation.createForRequest(any(), anyString(), anyString())).thenReturn(new TaskCreateResponse("task"));
        when(data.importFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
            List<TaskItemRequest> items = call.getArgument(1);
            var result = new LinkedHashMap<String, ActionOutcome>();
            items.forEach(item -> result.put(item.messageId(), ActionOutcome.applied())); return result;
        });
        when(queries.get(eq("app-checks"), anyString())).thenAnswer(call -> entry(call.getArgument(1), "pre_review", true));
        var service = new AppCheckTaskService(projects, queries, creation, data, lifecycle, operations, exports,
                Clock.fixed(Instant.parse("2026-09-18T01:00:00Z"), ZoneOffset.UTC));
        service.start(); return service;
    }
    ProjectTaskQueryService.Entry entry(String id, String state, boolean modern) {
        var metadata = new HashMap<>(Map.of("scenario", "app-checks", "appId", "app-a", "country", "CN", "simulation", Jsons.toJson(simulation()), "salt", "fixed"));
        if (modern) metadata.put("inputVersion", "2");
        return new ProjectTaskQueryService.Entry(id, 1, new TaskView(id, "app-checks", "app-a-sim", "CLOSE_WHEN_IDLE",
                List.of(RefillTarget.of(modern ? "assignment-window" : "any", new EligibilityQuery(Map.of()), 100)), Map.of(), "test", metadata), state);
    }
    static Map<String, Object> simulation() {
        return Map.of("ranges", Map.of("registered", List.of(0, 500), "unregistered", List.of(500, 900), "failed", List.of(900, 1000)), "delayMs", List.of(2000, 5000));
    }
    static Map<String, Object> request() {
        return new LinkedHashMap<>(Map.of("requestId", "request", "appId", "app-a", "country", "CN", "simulation", simulation()));
    }
    static InputStream file(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }

    @Test void createsOnlyAnEmptyTaskAndDoesNotRetainAFiftyRequestBudget() {
        try (var service = service()) {
            for (int i = 0; i < 51; i++) { var input = request(); input.put("requestId", "r" + i); service.create(input); }
            verify(creation, times(51)).createForRequest(argThat(req -> req.metadata().get("inputVersion").equals("2")
                    && !req.metadata().containsKey("numbers") && req.refill().getFirst().poolName().equals("assignment-window")), anyString(), anyString());
            verifyNoInteractions(data, lifecycle);
            var invalid = request(); invalid.put("numbers", List.of("+86123"));
            assertThatThrownBy(() -> service.create(invalid)).hasMessageContaining("Unknown");
            when(creation.createForRequest(any(), anyString(), anyString())).thenThrow(new TaskCreationUnconfirmedException("stable", null));
            assertThatThrownBy(() -> service.create(request())).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                    error -> assertThat(error.taskId).isEqualTo("stable"));
        }
    }
    @Test void creationFingerprintAndVersionedSaltAreStableWithoutInputNumbers() {
        var first = AppCheckSpecification.parse(request());
        var other = request(); other.put("requestId", "other");
        var second = AppCheckSpecification.parse(other);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.salt(LocalDate.of(2026, 9, 18))).isNotEqualTo(second.salt(LocalDate.of(2026, 9, 18)));
        other.put("simulation", Map.of());
        assertThatThrownBy(() -> AppCheckSpecification.parse(other)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void importsOneHundredThousandNumbersInBoundedBatchesWithoutApproval() {
        try (var service = service()) {
            String text = IntStream.range(0, 100000).mapToObj(i -> "86138" + String.format("%08d", i)).collect(java.util.stream.Collectors.joining("\n"));
            var report = service.importNumbers("task", file(text + "\n+8613800000000\n\n"));
            assertThat(report.uniqueCount()).isEqualTo(100000);
            assertThat(report.addedCount()).isEqualTo(100000);
            assertThat(report.duplicateCount()).isEqualTo(1);
            assertThatThrownBy(() -> service.importNumbers("task", file(text + "\n8613900000000"))).hasMessageContaining("100,000");
            verify(data, times(1000)).importFiniteTaskItems(eq("task"), argThat(batch -> batch.size() == 100
                    && batch.stream().allMatch(item -> item.ttlMillis() == null && item.payload().get("salt").equals("fixed"))));
            verifyNoInteractions(creation, lifecycle);
        }
    }
    @Test void validatesTheWholeFileBeforeWritesAndAlwaysDeletesTheSpool() throws Exception {
        try (var service = service()) {
            assertThatThrownBy(() -> service.importNumbers("task", file("86123\nwrong"))).hasMessageContaining("第 2 行");
            assertThatThrownBy(() -> service.importNumbers("task", new ByteArrayInputStream(new byte[]{(byte)0xc3, 0x28}))).hasMessageContaining("UTF-8");
            assertThatThrownBy(() -> service.importNumbers("task", file("1".repeat(AppCheckNumberFile.MAX_BYTES + 1)))).hasMessageContaining("10 MiB");
            verify(data, never()).importFiniteTaskItems(anyString(), anyList());
            var spool = AppCheckNumberFile.read(file("\uFEFF86123\r\n +86123 \r\n"), "CN");
            assertThat(spool.duplicateCount()).isEqualTo(1);
            var path = spool.file(); spool.close(); assertThat(path).doesNotExist();
        }
    }
    @Test void stopsAtAnUnconfirmedBatchAndAllowsApprovalOfTheObservedPartialSet() {
        try (var service = service()) {
            when(data.importFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
                List<TaskItemRequest> items = call.getArgument(1);
                var outcomes = new LinkedHashMap<String, ActionOutcome>();
                items.forEach(item -> outcomes.put(item.messageId(), ActionOutcome.applied()));
                return outcomes;
            })
                    .thenThrow(new IllegalStateException());
            String text = IntStream.range(0, 101).mapToObj(i -> "86138" + String.format("%08d", i)).collect(java.util.stream.Collectors.joining("\n"));
            assertThatThrownBy(() -> service.importNumbers("task", file(text))).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                    error -> { assertThat(error.status).isEqualTo(503); assertThat(error.taskId).isEqualTo("task");
                        assertThat(error.confirmedAdded).isEqualTo(100L); assertThat(error.existing).isZero(); });
            verify(data, times(2)).importFiniteTaskItems(eq("task"), anyList());
            when(data.observeItemScoreCounts(List.of("task"))).thenReturn(Map.of("task", new TaskItemScoreCounts(100, Map.of(1, 100L))));
            assertThatThrownBy(() -> service.approve("task", 101)).hasMessageContaining("数量");
            service.approve("task", 100); verify(lifecycle).approve("task");
            when(queries.get("app-checks", "task")).thenReturn(entry("task", "terminal", true));
            assertThatThrownBy(() -> service.importNumbers("task", file("86123"))).hasMessageContaining("待审核");
        }
    }
    @Test void oldTasksRemainReadableButCannotAcceptNewNumberIdentities() {
        try (var service = service()) {
            when(queries.get("app-checks", "task")).thenReturn(entry("task", "pre_review", false));
            assertThatThrownBy(() -> service.importNumbers("task", file("86123"))).hasMessageContaining("旧供给配置");
            assertThatThrownBy(() -> service.approve("task", 1)).hasMessageContaining("旧供给配置");
            service.closeTask("task"); verify(lifecycle).close("task");
        }
    }
    @Test void importConcurrencyIsReleasedAfterEachFile() throws Exception {
        try (var service = service(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            when(data.importFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
                entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                List<TaskItemRequest> items = call.getArgument(1);
                return Map.of(items.getFirst().messageId(), ActionOutcome.applied());
            });
            var a = executor.submit(() -> service.importNumbers("a", file("86123")));
            var b = executor.submit(() -> service.importNumbers("b", file("86123")));
            try { assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.importNumbers("c", file("86123"))).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class, e -> assertThat(e.status).isEqualTo(429));
            } finally { release.countDown(); }
            a.get(); b.get(); assertThat(service.importNumbers("c", file("86123")).addedCount()).isEqualTo(1);
        }
    }
    @Test void queriesStoredTruthWithoutLocalSubmissionAndPreservesBadContentRows() {
        try (var service = service()) {
            var task = new TaskView("stored", "app-checks", "app-a-sim", "CLOSE_WHEN_IDLE", List.of(), Map.of(), "stored name",
                    Map.of("scenario", "app-checks", "appId", "app-a", "country", "CN", "simulation", Jsons.toJson(simulation())));
            var entry = new ProjectTaskQueryService.Entry("stored", 123L, task, "terminal");
            when(queries.get("app-checks", "stored")).thenReturn(entry);
            var tags = new LinkedHashMap<Integer, Long>(); for (int tag = 1; tag <= 9; tag++) tags.put(tag, 0L);
            tags.put(5, 2L); tags.put(6, 998L);
            when(data.observeItemScoreCounts(List.of("stored"))).thenReturn(Map.of("stored", new TaskItemScoreCounts(1000, tags)));
            var item = new TaskItem("one", AppCheckWorkerSupply.EVENT, 1L, Map.of("number", "+86123"), 5, 600_000L, new WorkerQuery("worker.any", Map.of()));
            var valid = Jsons.toJson(Map.of("number", "+86123", "workerGroupId", "app-a-sim", "workerId", "actual", "registered", false, "simulatedDelayMillis", 12));
            when(data.previewTaskResults("stored")).thenReturn(new TaskDataService.ResultPreview(List.of(
                    new TaskDataService.ResultEntry("one", item, TaskItemResultResponse.succeeded(valid)),
                    new TaskDataService.ResultEntry("bad", item, TaskItemResultResponse.succeeded("not-json")),
                    new TaskDataService.ResultEntry("failed", item, TaskItemResultResponse.failed())), true));
            var response = service.get("stored");
            assertThat((Map<String, Object>) response.get("task")).containsEntry("totalCount", 1000L).containsEntry("succeededCount", 998L)
                    .containsEntry("failedCount", 2L).doesNotContainKey("registeredCount");
            var rows = (List<Map<String, Object>>) response.get("results");
            assertThat(rows.get(0)).containsEntry("registered", false).containsEntry("resultStatus", "succeeded");
            assertThat(rows.get(1)).containsKey("contentError").containsEntry("resultStatus", "succeeded");
            assertThat(rows.get(2)).containsEntry("resultStatus", "failed").doesNotContainKey("registered");
            assertThat(response).containsEntry("resultsTruncated", true);
            verifyNoInteractions(creation, lifecycle);
        }
    }
    @Test void numberInputVersionDoesNotMakeRetiredAnySupplyWritable() {
        try (var service = service()) {
            var original = entry("task", "pre_review", true).task();
            var old = new TaskView(original.taskId(), original.projectId(), original.workerGroupId(), original.idleDisposition(),
                    List.of(RefillTarget.of("any", new EligibilityQuery(Map.of()), 100)), original.config(), original.name(), original.metadata());
            when(queries.get("app-checks", "task")).thenReturn(new ProjectTaskQueryService.Entry("task", 1, old, "pre_review"));
            when(data.observeItemScoreCounts(List.of("task"))).thenReturn(Map.of("task", new TaskItemScoreCounts(0, Map.of(1, 0L, 5, 0L, 6, 0L, 7, 0L, 8, 0L, 9, 0L))));
            when(data.previewTaskResults("task")).thenReturn(new TaskDataService.ResultPreview(List.of(), false));
            assertThat((Map<String, Object>) service.get("task").get("task")).containsEntry("inputVersion", "2")
                    .containsKey("inputUnavailableReason");
            assertThatThrownBy(() -> service.importNumbers("task", file("86123"))).hasMessageContaining("旧供给配置");
            assertThatThrownBy(() -> service.approve("task", 1)).hasMessageContaining("旧供给配置");
            assertThat(service.create(request()).taskId()).isEqualTo("task");
            service.closeTask("task");
            verify(lifecycle).close("task");
            verify(lifecycle, never()).approve(anyString());
            verify(data, never()).importFiniteTaskItems(anyString(), anyList());
        }
    }
    @Test void exportsOnlyAssociatedBusinessAnswersAndDeletesTheIntermediateFile() throws Exception {
        try (var service = service()) {
            when(queries.get("app-checks", "task")).thenReturn(entry("task", "terminal", true));
            var items = new LinkedHashMap<String, TaskItem>();
            var records = new ArrayList<String>();
            for (String id : List.of("registered", "unregistered", "bad-json", "no-answer", "wrong-number", "no-item", "no-number", "wrong-id")) {
                var payload = id.equals("no-number") ? Map.<String, Object>of() : Map.<String, Object>of("number", "+86123");
                if (!id.equals("no-item")) items.put(id, new TaskItem(id.equals("wrong-id") ? "another" : id,
                        AppCheckWorkerSupply.EVENT, 1L, payload, 5, 600_000L, new WorkerQuery("worker.any", Map.of())));
                var content = new LinkedHashMap<String, Object>(Map.of("number", "+86123", "workerGroupId", "app-a-sim",
                        "workerId", "worker", "registered", !id.equals("unregistered"), "simulatedDelayMillis", 2));
                if (id.equals("no-answer")) content.remove("registered");
                if (id.equals("no-number")) content.remove("number");
                if (id.equals("wrong-number")) content.put("number", "+86124");
                records.add(Jsons.toJson(Map.of("messageId", id, "opaqueResultPayload", id.equals("bad-json") ? "not-json" : Jsons.toJson(content))));
            }
            when(data.loadTaskItems(eq("task"), anyList())).thenReturn(items);
            for (String filter : List.of("all", "registered", "unregistered")) {
                var source = Files.createTempFile("app-check-export-test-", ".jsonl");
                Files.write(source, records, StandardCharsets.UTF_8);
                when(exports.export("task")).thenReturn(new TaskResultsExportService.TaskResultsExport(source));
                var csv = service.export("task", filter);
                try {
                    assertThat(source).doesNotExist();
                    assertThat(csv.count()).isEqualTo(filter.equals("all") ? 2L : 1L);
                    var lines = Files.readAllLines(csv.file(), StandardCharsets.UTF_8);
                    assertThat(lines).hasSize((int) csv.count() + 1);
                    assertThat(lines.get(1)).startsWith("\"'+86123\",");
                    if (!filter.equals("all")) assertThat(lines.get(1)).contains(filter.equals("registered") ? "已注册" : "未注册");
                } finally { Files.deleteIfExists(source); Files.deleteIfExists(csv.file()); }
            }
        }
    }
}
