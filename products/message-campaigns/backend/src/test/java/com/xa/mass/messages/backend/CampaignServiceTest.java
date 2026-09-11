package com.xa.mass.messages.backend;

import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.task.*;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CampaignServiceTest {
    final TaskCreationService creation = mock(TaskCreationService.class);
    final TaskDataService data = mock(TaskDataService.class);
    final TaskLifecycleService lifecycle = mock(TaskLifecycleService.class);
    CampaignService service() {
        when(creation.create(any())).thenReturn(new TaskCreateResponse("finite-task"));
        when(data.appendFiniteTaskItems(anyString(), anyList())).thenAnswer(call -> {
            List<TaskItemRequest> items = call.getArgument(1);
            var result = new LinkedHashMap<String, ActionOutcome>();
            items.forEach(i -> result.put(i.messageId(), ActionOutcome.applied()));
            return result;
        });
        when(data.loadTaskItemResults(anyString(), anyList())).thenReturn(Map.of());
        var service = new CampaignService(mock(WorkerGroupRegistrationService.class), creation, data, lifecycle,
                "demo-sim", List.of("extension.worker.message.send"), 100);
        service.start(); return service;
    }
    Map<String, Object> input(int count) {
        return Map.of("requestId", "request", "name", "campaign", "country", "CN", "body", "message",
                "recipientIds", IntStream.range(0, count).mapToObj(i -> "recipient-" + i).toList(), "senderPhone", "+86123");
    }
    @Test void validatesWholeBatchBeforeAnyTaskAndChunksBeforeApproval() {
        try (var service = service()) {
            assertThatThrownBy(() -> service.create(input(1001))).isInstanceOf(CampaignService.ProductError.class);
            verifyNoInteractions(creation);
            var created = service.create(input(201));
            verify(lifecycle, timeout(3000)).approve("finite-task");
            var order = inOrder(creation, data, lifecycle);
            order.verify(creation).create(argThat(r -> r.workerGroupId().equals("demo-sim")
                    && r.allocationRule().get("worker.country").equals(Map.of("$eq", "CN"))
                    && r.allocationRule().containsKey("worker.messaging.enabled") && r.allocationRule().containsKey("worker.phone")));
            order.verify(data, times(2)).appendFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 100));
            order.verify(data).appendFiniteTaskItems(eq("finite-task"), argThat(items -> items.size() == 1));
            order.verify(lifecycle).approve("finite-task");
            assertThat(service.create(input(201)).get("id")).isEqualTo(created.get("id"));
            assertThatThrownBy(() -> service.create(input(200))).hasMessageContaining("different content");
            verify(creation, times(1)).create(any());
        }
    }
    @Test void partialSubmissionKeepsIdentityAndNeverApprovesOrRetries() throws Exception {
        try (var service = service()) {
            var second = new CountDownLatch(1);
            doAnswer(call -> {
                List<TaskItemRequest> items = call.getArgument(1);
                if (items.size() == 1) { second.countDown(); throw new IllegalStateException("uncertain append"); }
                var result = new LinkedHashMap<String, ActionOutcome>();
                items.forEach(i -> result.put(i.messageId(), ActionOutcome.applied())); return result;
            }).when(data).appendFiniteTaskItems(anyString(), anyList());
            String id = (String) service.create(input(101)).get("id");
            assertThat(second.await(3, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.get(id).get("submission").equals("SUBMITTING") && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(service.create(input(101))).containsEntry("id", id).containsEntry("taskId", "finite-task")
                    .containsEntry("submission", "SUBMISSION_UNCONFIRMED");
            verify(creation, times(1)).create(any()); verify(lifecycle, never()).approve(anyString());
        }
    }
    @Test void latestReplyIsCompleteAndLateExecutionCannotRegressBusinessProjection() {
        try (var service = service()) {
            var campaign = new CampaignService.Campaign(CampaignService.Specification.parse(input(1)), "demo-sim");
            var message = campaign.messages.getFirst();
            var snapshot = new LinkedHashMap<String, Object>(Map.of("campaignId", campaign.id, "messageId", message.id,
                    "country", "CN", "recipientId", message.recipient, "body", "message", "phone", "+86123",
                    "workerId", "worker", "status", "REPLIED", "observedAtMillis", 100L, "reply", "latest"));
            snapshot.put("replyRequestId", "reply");
            service.accept(campaign, message, TaskItemResultResponse.succeeded(Jsons.toJson(snapshot)));
            snapshot.put("reply", "old"); snapshot.put("observedAtMillis", 99L);
            service.accept(campaign, message, TaskItemResultResponse.succeeded(Jsons.toJson(snapshot)));
            snapshot.put("status", "SENT"); snapshot.put("observedAtMillis", 200L);
            service.accept(campaign, message, TaskItemResultResponse.succeeded(Jsons.toJson(snapshot)));
            service.accept(campaign, message, TaskItemResultResponse.failed());
            assertThat(message.view()).containsEntry("status", "REPLIED").containsEntry("reply", "latest");
            snapshot.put("status", "REPLIED"); snapshot.put("reply", "new");
            service.accept(campaign, message, TaskItemResultResponse.succeeded(Jsons.toJson(snapshot)));
            assertThat(message.view()).containsEntry("reply", "new");
            snapshot.put("recipientId", "wrong"); snapshot.put("observedAtMillis", 300L);
            service.accept(campaign, message, TaskItemResultResponse.succeeded(Jsons.toJson(snapshot)));
            assertThat(message.view()).containsEntry("recipientId", "recipient-0");
        }
    }
    @Test void stopRefusesNewBusiness() {
        var service = service(); service.stop();
        assertThatThrownBy(() -> service.create(input(1))).hasMessageContaining("unavailable");
        verifyNoInteractions(creation);
    }
    @Test void fullSubmissionQueueRejectsNewIdentityWithoutCreatingAnotherTask() throws Exception {
        try (var service = service()) {
            var entered = new CountDownLatch(2); var unblock = new CountDownLatch(1);
            when(creation.create(any())).thenAnswer(call -> { entered.countDown(); unblock.await(3, TimeUnit.SECONDS); return new TaskCreateResponse("task"); });
            try {
                for (int i = 0; i < 2; i++) { var input = new HashMap<>(input(1)); input.put("requestId", "running" + i); service.create(input); }
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                for (int i = 0; i < 8; i++) { var input = new HashMap<>(input(1)); input.put("requestId", "queued" + i); service.create(input); }
                assertThatThrownBy(() -> service.create(input(1))).hasMessageContaining("queue full");
                assertThat(service.page(0, 100).get("total")).isEqualTo(10);
                verify(creation, times(2)).create(any());
            } finally { unblock.countDown(); }
        }
    }
}
