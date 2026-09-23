package com.xa.mass.scenario.messages;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.task.*;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.workerdelivery.json.Jsons;
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
    MessageTaskService service() {
        when(creation.create(any())).thenReturn(new TaskCreateResponse("finite-task"));
        when(data.appendFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
            List<TaskItemRequest> items = call.getArgument(1);
            var result = new LinkedHashMap<String, ActionOutcome>();
            items.forEach(i -> result.put(i.messageId(), ActionOutcome.applied()));
            return result;
        });
        when(data.loadTaskItemResults(anyString(), anyList())).thenReturn(Map.of());
        var service = new MessageTaskService(mock(ProjectDirectory.class), queries, creation, data, lifecycle,
                "demo-sim");
        service.start(); return service;
    }
    Map<String, Object> input(int count) {
        return Map.of("requestId", "request", "name", "campaign", "recipientCountry", "CN", "senderCountry", "CN", "body", "{}",
                "recipientIds", IntStream.range(0, count).mapToObj(i -> "+861380000" + String.format("%04d", i)).toList(), "senderPhone", "+86123");
    }
    @Test void validatesInternationalNumbersAndBothCountriesBeforeAdmission() {
        try (var service = service()) {
            for (Object numbers : List.of(List.of(), List.of("recipient-0"), List.of("+86"), List.of("+44123"),
                    List.of("+086123"), List.of("+861 23"), List.of("+8612345678901234"), List.of(123),
                    List.of("+86123", " +86123 "))) {
                var request = new HashMap<>(input(1)); request.put("recipientIds", numbers);
                assertThatThrownBy(() -> service.create(request)).isInstanceOf(MessageTaskService.ProductError.class);
            }
            for (String field : List.of("recipientCountry", "senderCountry")) {
                for (String invalid : List.of("ANY", "FR", "cn", "")) {
                    var request = new HashMap<>(input(1)); request.put(field, invalid);
                    assertThatThrownBy(() -> service.create(request)).isInstanceOf(MessageTaskService.ProductError.class);
                }
            }
            var old = new HashMap<>(input(1)); old.put("country", old.remove("recipientCountry"));
            assertThatThrownBy(() -> service.create(old)).hasMessageContaining("Unknown message task fields");
            verifyNoInteractions(creation, lifecycle);
            assertThat(MessageTaskService.Specification.parse(input(1000)).recipientIds()).hasSize(1000);
            for (var country : Map.of("CN", "+861", "US", "+12", "GB", "+441").entrySet()) {
                var request = new HashMap<>(input(1)); request.put("recipientCountry", country.getKey());
                request.put("recipientIds", List.of("  " + country.getValue() + "  "));
                assertThat(MessageTaskService.Specification.parse(request).recipientIds()).containsExactly(country.getValue());
            }
        }
    }
    @Test void senderRangeIsIndependentAndAnyOmitsOnlyCountryConstraints() {
        for (String senderCountry : Arrays.asList("US", null)) {
            reset(creation, data, lifecycle);
            try (var service = service()) {
                var request = new HashMap<>(input(1)); request.put("senderCountry", senderCountry);
                request.remove("senderPhone"); request.put("recipientIds", List.of(" +8613800000000 "));
                var created = service.create(request);
                verify(lifecycle, timeout(3000)).approve("finite-task");
                verify(creation).create(argThat(r -> r.refill().size() == 1 && r.refill().getFirst().poolName().equals("messaging")
                        && r.refill().getFirst().target().query().equals(senderCountry == null ? Map.of() : Map.of("worker.country", List.of(senderCountry)))));
                verify(data).appendFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 1
                        && items.getFirst().workerSelector().executorName().equals("worker.messaging.available")
                        && items.getFirst().workerSelector().input().equals(senderCountry == null ? Map.of() : Map.of("country", List.of(senderCountry)))
                        && ((Map<?, ?>) items.getFirst().payload()).get("country").equals("CN")));
                assertThat(created.taskId()).isEqualTo("finite-task");
                request.put("recipientIds", List.of("+8613800000000"));
                if (senderCountry == null) request.remove("senderCountry");
                assertThat(service.create(request)).isEqualTo(created);
                request.put("senderCountry", "GB");
                assertThatThrownBy(() -> service.create(request)).hasMessageContaining("different content");
            }
        }
    }
    @Test void validatesWholeBatchAndSynchronouslyAppendsBeforeApproval() {
        try (var service = service()) {
            assertThatThrownBy(() -> service.create(input(1001))).isInstanceOf(MessageTaskService.ProductError.class);
            verifyNoInteractions(creation);
            assertThat(service.create(input(201)).taskId()).isEqualTo("finite-task");
            var order = inOrder(creation, data, lifecycle);
            order.verify(creation).create(argThat(r -> r.name().equals("campaign") && r.metadata().get("scenario").equals("messages")
                    && r.metadata().get("recipientCountry").equals("CN") && !r.metadata().containsKey("recipientIds")));
            order.verify(data, times(2)).appendFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 100
                    && items.stream().allMatch(i -> i.payload().get("campaignId").equals("finite-task"))));
            order.verify(data).appendFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 1));
            order.verify(lifecycle).approve("finite-task");
            service.create(input(201));
            verify(creation, times(1)).create(any());
            assertThatThrownBy(() -> service.create(input(200))).hasMessageContaining("different content");
        }
    }

    @Test void senderPhoneUsesQualifiedDirectQueryWithoutPoolSupply() {
        for (String senderCountry : Arrays.asList("US", null)) {
            reset(creation, data, lifecycle);
            try (var service = service()) {
                var request = new HashMap<>(input(1)); request.put("senderCountry", senderCountry);
                var created = service.create(request);
                verify(creation).create(argThat(r -> r.refill().isEmpty()
                        && r.metadata().get("senderPhone").equals("+86123")
                        && r.metadata().get("recipientCountry").equals("CN")));
                var expected = new LinkedHashMap<String, Object>(); expected.put("phone", "+86123");
                if (senderCountry != null) expected.put("country", List.of(senderCountry));
                verify(data).appendFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 1
                        && items.getFirst().workerSelector().executorName().equals("worker.messaging.phone")
                        && items.getFirst().workerSelector().input().equals(expected)
                        && ((Map<?, ?>) items.getFirst().payload()).get("country").equals("CN")));
                verify(lifecycle).approve("finite-task");
                assertThat(service.create(request)).isEqualTo(created);
                verify(creation, times(1)).create(any());
            }
        }
    }
    @Test void partialAppendAndUnknownCreationRetainGeneratedIdAndNeverRetry() {
        try (var service = service()) {
            doThrow(new IllegalStateException("Lost response")).when(data).appendFiniteTaskItems(anyString(), anyList());
            for (int i = 0; i < 2; i++) assertThatThrownBy(() -> service.create(input(1)))
                    .isInstanceOfSatisfying(MessageTaskService.ProductError.class, e -> assertThat(e.taskId).isEqualTo("finite-task"));
            verify(creation, times(1)).create(any()); verify(lifecycle, never()).approve(anyString());
            when(creation.create(any())).thenThrow(new TaskCreationUnconfirmedException("known-id", new IllegalStateException()));
            var other = new HashMap<>(input(1)); other.put("requestId", "other");
            assertThatThrownBy(() -> service.create(other)).isInstanceOfSatisfying(MessageTaskService.ProductError.class,
                    e -> assertThat(e.taskId).isEqualTo("known-id"));
        }
    }
    @Test void duplicateSharesInFlightSubmissionAndThirdNewSubmissionIsRejectedBeforeEffects() throws Exception {
        try (var service = service(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(2); var unblock = new CountDownLatch(1);
            when(creation.create(any())).thenAnswer(call -> { entered.countDown();
                assertThat(unblock.await(3, TimeUnit.SECONDS)).isTrue(); return new TaskCreateResponse("task"); });
            try {
                var first = executor.submit(() -> service.create(input(1)));
                var duplicate = executor.submit(() -> service.create(input(1)));
                var other = new HashMap<>(input(1)); other.put("requestId", "other");
                var second = executor.submit(() -> service.create(other));
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var rejected = new HashMap<>(input(1)); rejected.put("requestId", "rejected");
                assertThatThrownBy(() -> service.create(rejected)).isInstanceOfSatisfying(MessageTaskService.ProductError.class,
                        e -> assertThat(e.status).isEqualTo(429));
                unblock.countDown();
                assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo(duplicate.get(2, TimeUnit.SECONDS)); second.get(2, TimeUnit.SECONDS);
                verify(creation, times(2)).create(any());
            } finally { unblock.countDown(); }
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
        assertThatThrownBy(() -> service.create(input(1))).hasMessageContaining("unavailable");
        verifyNoInteractions(creation);
    }
}
