package com.xa.mass.scenario.messages;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.task.*;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.error.*;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageTaskServiceTest {
    final TaskCreationService creation = mock(TaskCreationService.class);
    final TaskDataService data = mock(TaskDataService.class);
    final TaskLifecycleService lifecycle = mock(TaskLifecycleService.class);
    final ProjectTaskQueryService queries = mock(ProjectTaskQueryService.class);
    final OperationGuard operations = new OperationGuard();
    MessageTaskService service() {
        when(creation.createForRequest(any(), anyString(), anyString())).thenReturn(new TaskCreateResponse("finite-task"));
        when(data.importFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
            List<TaskItemRequest> items = call.getArgument(1);
            var result = new LinkedHashMap<String, ActionOutcome>();
            items.forEach(i -> result.put(i.messageId(), ActionOutcome.applied())); return result;
        });
        var service = new MessageTaskService(mock(ProjectDirectory.class), queries, creation, data, lifecycle, "demo-sim", operations);
        entry("finite-task", input(), "pre_review", "2");
        service.start(); return service;
    }
    Map<String, Object> input() { return new HashMap<>(Map.of("requestId", "request", "name", "campaign", "recipientCountry", "CN", "senderCountry", "CN", "body", "{}")); }
    void entry(String id, Map<String, Object> input, String state, String version) {
        var metadata = new HashMap<>(MessageSpecification.parse(input).metadata());
        if (version == null) metadata.remove("inputVersion"); else metadata.put("inputVersion", version);
        when(queries.get("messages", id)).thenReturn(new ProjectTaskQueryService.Entry(id, 1,
                new TaskView(id, "messages", "demo-sim", "CLOSE_WHEN_IDLE", List.of(), Map.of(), "campaign", metadata), state));
    }
    static InputStream file(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    static String numbers(int count) { return IntStream.range(0, count).mapToObj(i -> "+86138" + String.format("%08d", i)).collect(java.util.stream.Collectors.joining("\n")); }

    @Test void createsOnlyAnEmptyCorrelatedTaskAndFingerprintCapturesEverySendingChoice() {
        try (var service = service()) {
            var request = input();
            assertThat(service.create(request).taskId()).isEqualTo("finite-task");
            verify(creation).createForRequest(argThat(r -> r.priority() == 50 && r.maxRetryTimes() == 3
                    && r.metadata().get("inputVersion").equals("2") && !r.metadata().containsKey("recipientIds")), eq("request"), matches("[0-9a-f]{64}"));
            verifyNoInteractions(data, lifecycle);
            var spec = MessageSpecification.parse(request);
            assertThat(spec.fingerprint("other-group")).isNotEqualTo(spec.fingerprint("demo-sim"));
            for (var change : Map.of("name", "other", "senderCountry", "US", "recipientCountry", "GB", "senderPhone", "+86123", "body", "{ }" ).entrySet()) {
                var changed = new HashMap<>(request); changed.put(change.getKey(), change.getValue());
                assertThat(MessageSpecification.parse(changed).fingerprint("demo-sim")).isNotEqualTo(spec.fingerprint("demo-sim"));
            }
            request.remove("senderCountry"); var omitted = MessageSpecification.parse(request);
            request.put("senderCountry", null); request.put("senderPhone", " ");
            assertThat(MessageSpecification.parse(request).fingerprint("demo-sim")).isEqualTo(omitted.fingerprint("demo-sim"));
            var old = input(); old.put("recipientIds", List.of("+86123"));
            assertThatThrownBy(() -> service.create(old)).hasMessageContaining("Unknown message task fields");
        }
    }

    @Test void wholeFileValidationDeduplicatesAndWritesBoundedBatchesWithoutApproval() {
        try (var service = service()) {
            assertThatThrownBy(() -> service.importRecipients("finite-task", file(numbers(201) + "\nbad")))
                    .hasMessageContaining("202");
            verifyNoInteractions(data, lifecycle);
            var receipt = service.importRecipients("finite-task", file("\uFEFF\n" + numbers(201) + "\n8613800000000"));
            assertThat(receipt.uniqueCount()).isEqualTo(201);
            assertThat(receipt.duplicateCount()).isEqualTo(1);
            assertThat(receipt.emptyCount()).isEqualTo(1);
            assertThat(receipt.confirmedAddedCount()).isEqualTo(201);
            verify(data, times(2)).importFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 100
                    && items.stream().allMatch(i -> i.ttlMillis() == null && i.payload().get("campaignId").equals("finite-task"))));
            verify(data).importFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 1));
            verifyNoInteractions(lifecycle);
            assertThat(MessageSpecification.messageId("finite-task", "+86123")).isNotEqualTo(MessageSpecification.messageId("other", "+86123"));
        }
    }

    @Test void storedConfigurationDrivesPoolOrPhoneSelectionForAnyAndCrossCountrySending() {
        for (String sender : Arrays.asList("US", null)) for (String phone : Arrays.asList("+86123", null)) {
            reset(data, creation);
            try (var service = service()) {
                var request = input(); request.put("senderCountry", sender); request.put("senderPhone", phone);
                service.create(request); entry("finite-task", request, "pre_review", "2");
                verify(creation).createForRequest(argThat(r -> phone == null
                        ? r.refill().getFirst().target().query().equals(sender == null ? Map.of() : Map.of("worker.country", List.of(sender)))
                        : r.refill().isEmpty()), eq("request"), anyString());
                service.importRecipients("finite-task", file("+86123"));
                var expected = new HashMap<String, Object>();
                if (sender != null) expected.put("country", List.of(sender)); if (phone != null) expected.put("phone", phone);
                verify(data).importFiniteTaskItems(eq("finite-task"), argThat(items ->
                        items.getFirst().workerSelector().executorName().equals(phone == null ? "worker.messaging.available" : "worker.messaging.phone")
                        && items.getFirst().workerSelector().input().equals(expected) && items.getFirst().payload().get("country").equals("CN")));
            }
        }
    }

    @Test void partialFailureRetainsConfirmedRangeAndExplicitRetryDoesNotCreateAnotherTask() {
        try (var service = service()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("confirmation lost");
                List<TaskItemRequest> items = call.getArgument(1);
                return items.stream().collect(java.util.stream.Collectors.toMap(TaskItemRequest::messageId, i -> ActionOutcome.applied()));
            }).when(data).importFiniteTaskItems(anyString(), anyList());
            assertThatThrownBy(() -> service.importRecipients("finite-task", file(numbers(201))))
                    .isInstanceOfSatisfying(MessageTaskService.ProductError.class, error -> {
                        assertThat(error.status).isEqualTo(503); assertThat(error.taskId).isEqualTo("finite-task");
                        assertThat(error.confirmedAddedCount).isEqualTo(100); assertThat(error.existingCount).isZero();
                    });
            verify(data, times(2)).importFiniteTaskItems(anyString(), anyList());
            service.importRecipients("finite-task", file(numbers(1)));
            verifyNoInteractions(creation, lifecycle);
        }
    }

    @Test void knownCreationIdentityAndStateConflictsKeepTheirClassification() {
        try (var service = service()) {
            when(creation.createForRequest(any(), anyString(), anyString())).thenThrow(new TaskCreationUnconfirmedException("known", new IllegalStateException()));
            assertThatThrownBy(() -> service.create(input())).isInstanceOfSatisfying(MessageTaskService.ProductError.class, e -> {
                assertThat(e.taskId).isEqualTo("known"); assertThat(e.status).isEqualTo(503);
            });
            doThrow(new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "test.create", "conflict", null)).when(creation).createForRequest(any(), anyString(), anyString());
            assertThatThrownBy(() -> service.create(input())).isInstanceOfSatisfying(MessageTaskService.ProductError.class, e -> assertThat(e.status).isEqualTo(409));
        }
    }

    @Test void approvalRequiresCurrentCountAndLegacyTasksCanOnlyClose() {
        try (var service = service()) {
            when(data.observeItemScoreCounts(List.of("finite-task"))).thenReturn(Map.of("finite-task", new TaskItemScoreCounts(2, Map.of())));
            assertThatThrownBy(() -> service.approve("finite-task", 1)).hasMessageContaining("变化");
            service.approve("finite-task", 2); verify(lifecycle).approve("finite-task");
            for (String state : Arrays.asList("terminal", "running_visible", null)) {
                entry("finite-task", input(), state, "2");
                assertThatThrownBy(() -> service.approve("finite-task", 2)).isInstanceOf(MessageTaskService.ProductError.class);
            }
            entry("finite-task", input(), "pre_review", null);
            assertThatThrownBy(() -> service.importRecipients("finite-task", file("+86123"))).hasMessageContaining("旧输入版本");
            assertThatThrownBy(() -> service.approve("finite-task", 2)).hasMessageContaining("旧输入版本");
            service.closeTask("finite-task"); verify(lifecycle).close("finite-task");
        }
    }

    @Test void concurrentImportBlocksSameTaskMutationsAndThirdImportBeforeReadingThenReleasesCapacity() throws Exception {
        try (var service = service(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            entry("second", input(), "pre_review", "2");
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            doAnswer(call -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                List<TaskItemRequest> items = call.getArgument(1); return Map.of(items.getFirst().messageId(), ActionOutcome.applied());
            }).when(data).importFiniteTaskItems(anyString(), anyList());
            try {
                var one = executor.submit(() -> service.importRecipients("finite-task", file("+86123")));
                var two = executor.submit(() -> service.importRecipients("second", file("+86123")));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var stream = mock(InputStream.class);
                assertThatThrownBy(() -> service.importRecipients("third", stream)).isInstanceOfSatisfying(MessageTaskService.ProductError.class, e -> assertThat(e.status).isEqualTo(429));
                verifyNoInteractions(stream);
                assertThatThrownBy(() -> service.approve("finite-task", 1)).isInstanceOfSatisfying(MessageTaskService.ProductError.class, e -> assertThat(e.status).isEqualTo(409));
                assertThatThrownBy(() -> service.closeTask("finite-task")).isInstanceOfSatisfying(MessageTaskService.ProductError.class, e -> assertThat(e.status).isEqualTo(409));
                assertThatThrownBy(() -> operations.taskMutation("finite-task", () -> true)).isInstanceOf(ServerException.class);
                release.countDown(); one.get(); two.get();
                service.importRecipients("finite-task", file("+86123"));
            } finally { release.countDown(); }
        }
    }
    @Test void readsExistingTasksWithoutAnyLocalSubmissionAndKeepsIndependentCountsAndUnparseableResults() {
        try (var service = service()) {
            var task = new com.xa.mass.server.api.v1.contract.runtimeview.TaskView("existing", "messages", "demo-sim",
                    "CLOSE_WHEN_IDLE", List.of(), Map.of(), "saved name", Map.of("scenario", "messages", "recipientCountry", "CN", "body", "{}"));
            var entry = new ProjectTaskQueryService.Entry("existing", 100L, task, "terminal");
            when(queries.get("messages", "existing")).thenReturn(entry);
            when(queries.list("messages", 100)).thenReturn(new ProjectTaskQueryService.ProjectTasks("messages", List.of(entry), false));
            var tags = new LinkedHashMap<Integer, Long>(); for (int tag = 1; tag <= 9; tag++) tags.put(tag, 0L);
            tags.put(5, 1L); tags.put(6, 2L); tags.put(7, 3L); tags.put(8, 4L); tags.put(9, 5L);
            when(data.observeItemScoreCounts(List.of("existing"))).thenReturn(Map.of("existing",
                    new com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts(1000, tags)));
            when(data.previewTaskResults("existing")).thenReturn(new TaskDataService.ResultPreview(List.of(
                    new TaskDataService.ResultEntry("bad-content", null, TaskItemResultResponse.succeeded("not-json")),
                    new TaskDataService.ResultEntry("failed", null, TaskItemResultResponse.failed())), true));
            var detail = service.get("existing");
            assertThat((Map<String,Object>) detail.get("task")).containsEntry("sendTotal", 1000L)
                    .containsEntry("sentCount", 14L).containsEntry("deliveredCount", 12L).containsEntry("readCount", 9L)
                    .containsEntry("repliedCount", 5L).containsEntry("failedCount", 1L).containsEntry("name", "saved name");
            var results = (List<Map<String,Object>>) detail.get("results");
            assertThat(results.getFirst()).containsEntry("resultStatus", "succeeded").containsKey("contentError").doesNotContainKey("status");
            assertThat(results.getLast()).containsEntry("resultStatus", "failed");
            assertThat(detail).containsEntry("resultsTruncated", true);
            assertThat(service.list(100).get("tasks")).isEqualTo(List.of(detail.get("task")));
            verifyNoInteractions(creation, lifecycle);
        }
    }
    @Test void stopRefusesNewBusiness() {
        var service = service(); service.stop();
        assertThatThrownBy(() -> service.create(input())).hasMessageContaining("unavailable");
        verifyNoInteractions(creation);
    }
}
