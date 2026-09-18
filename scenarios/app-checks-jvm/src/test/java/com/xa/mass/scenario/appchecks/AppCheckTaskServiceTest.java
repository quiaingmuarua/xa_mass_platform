package com.xa.mass.scenario.appchecks;

import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.project.*;
import com.xa.mass.server.task.*;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.*;
import java.util.*;
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

    AppCheckTaskService service() {
        when(creation.create(any())).thenReturn(new TaskCreateResponse("task"));
        when(data.appendFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
            List<TaskItemRequest> items = call.getArgument(1);
            var result = new LinkedHashMap<String, ActionOutcome>();
            items.forEach(item -> result.put(item.messageId(), ActionOutcome.applied()));
            return result;
        });
        var service = new AppCheckTaskService(projects, queries, creation, data, lifecycle,
                Clock.fixed(Instant.parse("2026-09-18T01:00:00Z"), ZoneOffset.UTC));
        service.start(); return service;
    }
    static Map<String, Object> simulation() {
        return Map.of("ranges", Map.of("registered", List.of(0, 500), "unregistered", List.of(500, 900), "failed", List.of(900, 1000)),
                "delayMs", List.of(2000, 5000));
    }
    static Map<String, Object> request(int count) {
        return new LinkedHashMap<>(Map.of("requestId", "request", "appId", "app-a", "country", "CN", "name", "lookup",
                "simulation", simulation(), "numbers", IntStream.range(0, count).mapToObj(i -> "+8613800" + String.format("%06d", i)).toList()));
    }

    @Test void completeValidationPrecedesTaskCreation() {
        try (var service = service()) {
            for (Object invalid : List.of(List.of(), List.of("+1"), List.of("+44123"), List.of("+86123", " +86123 "), List.of("unknown"))) {
                var input = request(1); input.put("numbers", invalid);
                assertThatThrownBy(() -> service.create(input)).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                        error -> assertThat(error.status).isEqualTo(400));
            }
            var tooMany = request(1001);
            assertThatThrownBy(() -> service.create(tooMany)).isInstanceOf(AppCheckTaskService.RequestFailure.class);
            var unsupported = request(1); unsupported.put("appId", "app-c");
            assertThatThrownBy(() -> service.create(unsupported)).hasMessageContaining("appId");
            var missing = request(1); missing.remove("simulation");
            assertThatThrownBy(() -> service.create(missing)).hasMessageContaining("simulation");
            var unknown = request(1); unknown.put("workerId", "forged");
            assertThatThrownBy(() -> service.create(unknown)).hasMessageContaining("Unknown");
            verifyNoInteractions(creation, data, lifecycle);
            assertThat(AppCheckSpecification.parse(request(1000)).numbers()).hasSize(1000);
        }
    }

    @Test void rejectsInvalidAndOversizedSimulation() {
        for (Object description : List.of(Map.of(), Map.of("ranges", Map.of(), "delayMs", List.of(0, 0)),
                Map.of("ranges", Map.of("registered", List.of(0, 501), "unregistered", List.of(500, 900), "failed", List.of(900, 1000)), "delayMs", List.of(0, 0)),
                Map.of("ranges", ((Map<?, ?>) simulation().get("ranges")), "delayMs", List.of(-1, 0)),
                Map.of("ranges", ((Map<?, ?>) simulation().get("ranges")), "delayMs", List.of(0, 30001)),
                Map.of("ranges", ((Map<?, ?>) simulation().get("ranges")), "delayMs", List.of("x".repeat(4096), 0)))) {
            var request = request(1); request.put("simulation", description);
            assertThatThrownBy(() -> AppCheckSpecification.parse(request)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void createsCompleteTaskAndBatchesBeforeApprovalWithoutChangingIdempotentSalt() {
        try (var service = service()) {
            assertThat(service.create(request(201)).taskId()).isEqualTo("task");
            var order = inOrder(creation, data, lifecycle);
            var creationArgument = org.mockito.ArgumentCaptor.forClass(TaskCreateRequest.class);
            order.verify(creation).create(creationArgument.capture());
            var created = creationArgument.getValue();
            assertThat(created.projectId()).isEqualTo("app-checks");
            assertThat(created.workerGroupId()).isEqualTo("app-a-sim");
            assertThat(created.metadata()).containsEntry("saltDate", "2026-09-18").containsEntry("scenario", "app-checks")
                    .doesNotContainKeys("numbers", "totalCount");
            assertThat(created.refill().getFirst().poolName()).isEqualTo("any");
            String salt = created.metadata().get("salt");
            order.verify(data, times(2)).appendFiniteTaskItems(eq("task"), argThat(items -> items.size() == 100
                    && items.stream().allMatch(i -> i.payload().get("salt").equals(salt) && i.ttlMillis() == 600_000L
                    && i.eventCode().equals(AppCheckTaskService.EVENT) && i.workerSelector().executorName().equals("worker.any"))));
            order.verify(data).appendFiniteTaskItems(eq("task"), argThat(items -> items.size() == 1));
            order.verify(lifecycle).approve("task");
            assertThat(service.create(request(201)).taskId()).isEqualTo("task");
            verify(creation, times(1)).create(any());
            assertThatThrownBy(() -> service.create(request(200))).hasMessageContaining("different content");
        }
    }

    @Test void normalizationAndSaltDoNotDependOnNameDescriptionOrRequestId() {
        var first = AppCheckSpecification.parse(request(1));
        var other = request(1); other.put("requestId", "other"); other.remove("name"); other.put("numbers", List.of(" +8613800000000 "));
        var second = AppCheckSpecification.parse(other);
        LocalDate date = LocalDate.of(2026, 9, 18);
        assertThat(first.salt(date)).isEqualTo(second.salt(date));
        assertThat(first.salt(date.plusDays(1))).isNotEqualTo(first.salt(date));
        other.put("appId", "app-b");
        assertThat(AppCheckSpecification.parse(other).salt(date)).isNotEqualTo(first.salt(date));
    }

    @Test void unknownCreateOrAppendPreservesTaskIdAndNeverReplays() {
        try (var service = service()) {
            doThrow(new IllegalStateException("response lost")).when(data).appendFiniteTaskItems(anyString(), anyList());
            for (int attempt = 0; attempt < 2; attempt++) assertThatThrownBy(() -> service.create(request(1)))
                    .isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class, error -> assertThat(error.taskId).isEqualTo("task"));
            verify(creation, times(1)).create(any()); verify(data, times(1)).appendFiniteTaskItems(anyString(), anyList());
            verify(lifecycle, never()).approve(anyString());
            when(creation.create(any())).thenThrow(new TaskCreationUnconfirmedException("generated", new IllegalStateException()));
            var other = request(1); other.put("requestId", "unknown-create");
            assertThatThrownBy(() -> service.create(other)).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                    error -> assertThat(error.taskId).isEqualTo("generated"));
        }
    }

    @Test void uncertainApprovalIsRetainedWithoutResubmittingItems() {
        try (var service = service()) {
            when(lifecycle.approve("task")).thenThrow(new IllegalStateException("approval response lost"));
            for (int i = 0; i < 2; i++) assertThatThrownBy(() -> service.create(request(1)))
                    .isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class, error -> {
                        assertThat(error.status).isEqualTo(503); assertThat(error.taskId).isEqualTo("task");
                    });
            verify(creation, times(1)).create(any());
            verify(data, times(1)).appendFiniteTaskItems(anyString(), anyList());
            verify(lifecycle, times(1)).approve("task");
        }
    }

    @Test void retainedRequestCapacityRejectsBeforeEffectsButStillAllowsDuplicates() {
        try (var service = service()) {
            for (int i = 0; i < 50; i++) {
                var input = request(1); input.put("requestId", "capacity-" + i); service.create(input);
            }
            clearInvocations(creation, data, lifecycle);
            assertThatThrownBy(() -> service.create(request(1))).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                    error -> assertThat(error.status).isEqualTo(429));
            var duplicate = request(1); duplicate.put("requestId", "capacity-0");
            assertThat(service.create(duplicate).taskId()).isEqualTo("task");
            verifyNoInteractions(creation, data, lifecycle);
            service.stop();
            assertThatThrownBy(() -> service.create(duplicate)).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                    error -> assertThat(error.status).isEqualTo(503));
        }
    }

    @Test void duplicatesShareInFlightWorkAndThirdNewRequestGetsBackpressure() throws Exception {
        try (var service = service(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            when(creation.create(any())).thenAnswer(call -> { entered.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return new TaskCreateResponse("task"); });
            try {
                var first = executor.submit(() -> service.create(request(1)));
                var duplicate = executor.submit(() -> service.create(request(1)));
                var secondInput = request(1); secondInput.put("requestId", "second");
                var second = executor.submit(() -> service.create(secondInput));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var third = request(1); third.put("requestId", "third");
                assertThatThrownBy(() -> service.create(third)).isInstanceOfSatisfying(AppCheckTaskService.RequestFailure.class,
                        error -> assertThat(error.status).isEqualTo(429));
                release.countDown();
                assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(duplicate.get(3, TimeUnit.SECONDS)); second.get(3, TimeUnit.SECONDS);
                verify(creation, times(2)).create(any());
            } finally { release.countDown(); }
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
            var item = new TaskItem("one", AppCheckTaskService.EVENT, 1L, Map.of("number", "+86123"), 5, 600_000L, new WorkerQuery("worker.any", Map.of()));
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
}
