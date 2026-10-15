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
    private static final com.xa.mass.server.task.access.TaskCaller CALLER = com.xa.mass.server.testing.TaskCallers.caller("proof", "admin");
    final TaskCreationService creation = mock(TaskCreationService.class);
    final TaskDataService data = mock(TaskDataService.class);
    final ProjectTaskQueryService queries = mock(ProjectTaskQueryService.class);
    final OperationGuard operations = new OperationGuard();
    MessageTaskService service() {
        when(creation.createForRequest(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), any(), anyString())).thenReturn(new TaskCreateResponse("finite-task"));
        when(data.importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), anyString(), anyList())).thenAnswer(call -> {
            List<TaskItemRequest> items = call.getArgument(3);
            var result = new LinkedHashMap<String, ActionOutcome>();
            items.forEach(i -> result.put(i.messageId(), ActionOutcome.applied())); return result;
        });
        var service = new MessageTaskService(mock(ProjectDirectory.class), queries, creation, data, config(), operations);
        entry("finite-task", input(), "pre_review");
        service.start(); return service;
    }
    MessageScenarioProperties config() {
        return new MessageScenarioProperties(List.of(
                new MessageScenarioProperties.Application("demo", "Demo", "demo-sim"),
                new MessageScenarioProperties.Application("app-a", "App A", "app-a-sim"),
                new MessageScenarioProperties.Application("app-b", "App B", "app-b-sim")));
    }
    Map<String, Object> input() { return new HashMap<>(Map.of("appId", "demo", "requestId", "request", "name", "campaign", "recipientCountry", "CN", "senderCountry", "CN", "body", "{}")); }
    void entry(String id, Map<String, Object> input, String state) {
        var metadata = new HashMap<>(MessageSpecification.parse(input).metadata());
        metadata.put("server.inputOwner", "messages");
        when(queries.get(CALLER, "messages", id)).thenReturn(new ProjectTaskQueryService.Entry(id, 1L,
                new TaskView(id, "messages", "demo-sim", "CLOSE_WHEN_IDLE", List.of(), Map.of(), "campaign", metadata), state, null));
    }
    static InputStream file(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
    static String numbers(int count) { return IntStream.range(0, count).mapToObj(i -> "+86138" + String.format("%08d", i)).collect(java.util.stream.Collectors.joining("\n")); }

    @Test void createsOnlyAnEmptyCorrelatedTaskAndFingerprintCapturesEverySendingChoice() {
        try (var service = service()) {
            var request = input();
            assertThat(service.create(CALLER, request).taskId()).isEqualTo("finite-task");
            verify(creation).createForRequest(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), argThat(r -> r.priority() == 50 && r.maxRetryTimes() == 3
                    && r.metadata().get("inputVersion").equals("3") && !r.metadata().containsKey("recipientIds")), matches("[0-9a-f]{64}"));
            verifyNoInteractions(data);
            var spec = MessageSpecification.parse(request);
            assertThat(spec.fingerprint("other-group")).isNotEqualTo(spec.fingerprint("demo-sim"));
            for (var change : Map.of("name", "other", "senderCountry", "US", "recipientCountry", "GB", "appId", "app-a", "body", "{ }" ).entrySet()) {
                var changed = new HashMap<>(request); changed.put(change.getKey(), change.getValue());
                assertThat(MessageSpecification.parse(changed).fingerprint("demo-sim")).isNotEqualTo(spec.fingerprint("demo-sim"));
            }
            request.remove("senderCountry"); var omitted = MessageSpecification.parse(request);
            request.put("senderCountry", null);
            assertThat(MessageSpecification.parse(request).fingerprint("demo-sim")).isEqualTo(omitted.fingerprint("demo-sim"));
            var old = input(); old.put("recipientIds", List.of("+86123"));
            assertThatThrownBy(() -> service.create(CALLER, old)).hasMessageContaining("Unknown message task fields");
        }
    }

    @Test void cleanupRejectionIsNotTranslatedIntoAnUnknownCreation() {
        try (var service = service()) {
            var rejected = new com.xa.mass.server.error.ServerException(
                    com.xa.mass.server.error.ServerErrorCode.TASK_CLEANUP_IN_PROGRESS, "taskCreation.create", null, null);
            when(creation.createForRequest(eq(CALLER), eq("messages"), any(), anyString())).thenThrow(rejected);
            assertThatThrownBy(() -> service.create(CALLER, input())).isSameAs(rejected);
            verifyNoInteractions(data);
        }
    }

    @Test void wholeFileValidationDeduplicatesAndWritesBoundedBatchesWithoutApproval() {
        try (var service = service()) {
            assertThatThrownBy(() -> service.importRecipients(CALLER, "finite-task", file(numbers(201) + "\nbad")))
                    .hasMessageContaining("202");
            verifyNoInteractions(data);
            var receipt = service.importRecipients(CALLER, "finite-task", file("\uFEFF\n" + numbers(201) + "\n8613800000000"));
            assertThat(receipt.uniqueCount()).isEqualTo(201);
            assertThat(receipt.duplicateCount()).isEqualTo(1);
            assertThat(receipt.emptyCount()).isEqualTo(1);
            assertThat(receipt.confirmedAddedCount()).isEqualTo(201);
            verify(data, times(2)).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), eq("finite-task"), argThat(items -> items.size() == 100
                    && items.stream().allMatch(i -> i.ttlMillis() == null && i.payload().get("campaignId").equals("finite-task"))));
            verify(data).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), eq("finite-task"), argThat(items -> items.size() == 1));
            assertThat(MessageSpecification.messageId("finite-task", "+86123")).isNotEqualTo(MessageSpecification.messageId("other", "+86123"));
        }
    }

    @Test void eachApplicationUsesItsOwnGroupAndPoolForAnyAndCrossCountrySending() {
        for (String app : List.of("demo", "app-a", "app-b")) for (String sender : Arrays.asList("US", null)) {
            reset(data, creation);
            try (var service = service()) {
                var request = input(); request.put("appId", app); request.put("senderCountry", sender);
                service.create(CALLER, request); entry("finite-task", request, "pre_review");
                verify(creation).createForRequest(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), argThat(r -> r.workerGroupId().equals(app + "-sim")
                        && r.refill().getFirst().poolName().equals("messaging")
                        && r.refill().getFirst().target().query().equals(sender == null ? Map.of() : Map.of("worker.country", List.of(sender)))), anyString());
                service.importRecipients(CALLER, "finite-task", file("+86123"));
                verify(data).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), eq("finite-task"), argThat(items ->
                        items.getFirst().workerSelector().executorName().equals("worker.messaging.available")
                        && items.getFirst().workerSelector().input().equals(sender == null ? Map.of() : Map.of("country", List.of(sender)))
                        && items.getFirst().payload().get("country").equals("CN")));
            }
        }
    }

    @Test void opaqueContentSurvivesCreationAndImportAndStoredAppsAreNotResolvedAgain() {
        try (var service = service()) {
            String content = "  你好 {{name}}\n<body>not JSON</body>  ";
            var request = input(); request.put("body", content);
            service.create(CALLER, request);
            verify(creation).createForRequest(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), argThat(r -> r.metadata().get("body").equals(content)), anyString());
            request.put("appId", "retired-application");
            entry("finite-task", request, "pre_review");
            service.importRecipients(CALLER, "finite-task", file("+86123"));
            verify(data).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), eq("finite-task"), argThat(items -> items.getFirst().payload().get("body").equals(content)));
        }
    }

    @Test void rejectsMissingUnknownApplicationsDirectedInputAndInvalidTextBeforeCreatingAnything() {
        try (var service = service()) {
            var missing = input(); missing.remove("appId");
            var unknown = input(); unknown.put("appId", "missing");
            var phone = input(); phone.put("senderPhone", null);
            var group = input(); group.put("workerGroupId", "demo-sim");
            var blank = input(); blank.put("body", " ");
            var longText = input(); longText.put("body", "x".repeat(4097));
            for (var request : List.of(missing, unknown, phone, group, blank, longText))
                assertThatThrownBy(() -> service.create(CALLER, request)).isInstanceOfSatisfying(MessageError.class,
                        error -> assertThat(error.status).isEqualTo(400));
            verifyNoInteractions(creation, data);
        }
    }

    @Test void partialFailureRetainsConfirmedRangeAndExplicitRetryDoesNotCreateAnotherTask() {
        try (var service = service()) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("confirmation lost");
                List<TaskItemRequest> items = call.getArgument(3);
                return items.stream().collect(java.util.stream.Collectors.toMap(TaskItemRequest::messageId, i -> ActionOutcome.applied()));
            }).when(data).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), anyString(), anyList());
            assertThatThrownBy(() -> service.importRecipients(CALLER, "finite-task", file(numbers(201))))
                    .isInstanceOfSatisfying(MessageError.class, error -> {
                        assertThat(error.status).isEqualTo(503); assertThat(error.taskId).isEqualTo("finite-task");
                        assertThat(error.confirmedAddedCount).isEqualTo(100); assertThat(error.existingCount).isZero();
                    });
            verify(data, times(2)).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), anyString(), anyList());
            service.importRecipients(CALLER, "finite-task", file(numbers(1)));
            verifyNoInteractions(creation);
        }
    }

    @Test void knownCreationIdentityAndStateConflictsKeepTheirClassification() {
        try (var service = service()) {
            when(creation.createForRequest(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), any(), anyString())).thenThrow(new TaskCreationUnconfirmedException("known", new IllegalStateException()));
            assertThatThrownBy(() -> service.create(CALLER, input())).isInstanceOfSatisfying(MessageError.class, e -> {
                assertThat(e.taskId).isEqualTo("known"); assertThat(e.status).isEqualTo(503);
            });
            doThrow(new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "test.create", "conflict", null)).when(creation).createForRequest(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), any(), anyString());
            assertThatThrownBy(() -> service.create(CALLER, input())).isInstanceOfSatisfying(MessageError.class, e -> assertThat(e.status).isEqualTo(409));
        }
    }

    @Test void concurrentImportBlocksSameTaskMutationsAndThirdImportBeforeReadingThenReleasesCapacity() throws Exception {
        try (var service = service(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            entry("second", input(), "pre_review");
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            doAnswer(call -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                List<TaskItemRequest> items = call.getArgument(3); return Map.of(items.getFirst().messageId(), ActionOutcome.applied());
            }).when(data).importFiniteTaskItems(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.eq("messages"), anyString(), anyList());
            try {
                var one = executor.submit(() -> service.importRecipients(CALLER, "finite-task", file("+86123")));
                var two = executor.submit(() -> service.importRecipients(CALLER, "second", file("+86123")));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var stream = mock(InputStream.class);
                assertThatThrownBy(() -> service.importRecipients(CALLER, "third", stream)).isInstanceOfSatisfying(MessageError.class, e -> assertThat(e.status).isEqualTo(429));
                verifyNoInteractions(stream);
                assertThatThrownBy(() -> operations.taskMutation("finite-task", () -> true)).isInstanceOf(ServerException.class);
                release.countDown(); one.get(); two.get();
                service.importRecipients(CALLER, "finite-task", file("+86123"));
            } finally { release.countDown(); }
        }
    }
    @Test void readsExistingResultsWithoutLocalSubmissionAndKeepsUnparseableRows() {
        try (var service = service()) {
            var task = new com.xa.mass.server.api.v1.contract.runtimeview.TaskView("existing", "messages", "demo-sim",
                    "CLOSE_WHEN_IDLE", List.of(), Map.of(), "saved name", Map.of("server.inputOwner", "messages", "scenario", "messages", "recipientCountry", "CN", "body", "{}"));
            var entry = new ProjectTaskQueryService.Entry("existing", 100L, task, "terminal", null);
            when(queries.get(CALLER, "messages", "existing")).thenReturn(entry);
            when(data.previewTaskResults(CALLER, "existing")).thenReturn(new TaskDataService.ResultPreview(List.of(
                    new TaskDataService.ResultEntry("bad-content", null, TaskItemResultResponse.succeeded("not-json")),
                    new TaskDataService.ResultEntry("failed", null, TaskItemResultResponse.failed())), true));
            var detail = service.results(CALLER, "existing");
            var results = (List<Map<String,Object>>) detail.get("results");
            assertThat(results.getFirst()).containsEntry("resultStatus", "succeeded").containsKey("contentError").doesNotContainKey("status");
            assertThat(results.getLast()).containsEntry("resultStatus", "failed");
            assertThat(detail).containsEntry("truncated", true);
            verifyNoInteractions(creation);
        }
    }
    @Test void stopRefusesNewBusiness() {
        var service = service(); service.stop();
        assertThatThrownBy(() -> service.create(CALLER, input())).hasMessageContaining("unavailable");
        verifyNoInteractions(creation);
    }
}
