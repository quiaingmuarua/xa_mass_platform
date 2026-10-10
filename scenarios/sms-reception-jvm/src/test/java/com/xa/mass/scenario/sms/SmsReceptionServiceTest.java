package com.xa.mass.scenario.sms;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.observation.TaskLeaseProjectionService;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.DeferredResult;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsReceptionServiceTest {
    final ProjectDirectory projects = mock(ProjectDirectory.class);
    final TaskRpcCallService calls = mock(TaskRpcCallService.class);
    final TaskDataService results = mock(TaskDataService.class);
    final TaskLeaseProjectionService projections = mock(TaskLeaseProjectionService.class);
    final SmsReceptionService service = new SmsReceptionService(projects, calls, results, projections, "g", Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));
    void start() { when(projects.requireManagedTaskId("sms", "g")).thenReturn("task"); service.start(); }
    Map<String, Object> snapshot(String id, boolean sms) {
        var value = new LinkedHashMap<String, Object>(Map.of("messageId", id, "applicationId", "A", "country", "CN",
                "phoneNumber", "123", "workerId", "w", "leaseUntil", 2000, "status", sms ? "RECEIVED" : "WAITING"));
        if (sms) value.put("sms", Map.of("text", "[A] 123456", "receivedAt", 1500));
        return value;
    }
    TaskItemResultResponse result(Map<String, Object> value) { return new TaskItemResultResponse(TaskItemResultStatus.SUCCEEDED, Jsons.toJson(value)); }
    @Test void constructionHasNoSideEffectsAndLifecycleRevokesAdmission() {
        verifyNoInteractions(projects, calls, results, projections);
        assertThatThrownBy(() -> service.lease(Map.of())).isInstanceOf(SmsReceptionService.ProductError.class);
        start(); service.stop();
        assertThatThrownBy(() -> service.get("id")).isInstanceOf(SmsReceptionService.ProductError.class);
        verifyNoInteractions(calls, results, projections);
    }
    @Test void acquisitionUsesOriginalMessageIdentityAndSingleMappedCall() {
        start();
        when(calls.call(eq("task"), any(), any())).thenAnswer(invocation -> {
            TaskRpcCallRequest request = invocation.getArgument(1);
            assertThat(request.waitTimeoutMillis()).isEqualTo(3000L);
            TaskItemRequest item = request.items().getFirst();
            assertThat(item.eventCode()).isEqualTo(SmsReceptionService.EVENT);
            assertThat(item.workerSelector().input()).isEqualTo(Map.of("partition", "A", "country", "CN"));
            @SuppressWarnings("unchecked") var payload = (Map<String, Object>) item.payload();
            assertThat(payload).containsEntry("messageId", item.messageId()).containsEntry("setupDeadline", 31000L).containsEntry("leaseSeconds", 60L);
            Function<Map<String, TaskItemResultResponse>, Map<String, Object>> mapping = invocation.getArgument(2);
            var deferred = new DeferredResult<Map<String, Object>>();
            deferred.setResult(mapping.apply(Map.of(item.messageId(), result(snapshot(item.messageId(), false)))));
            return deferred;
        });
        var response = (Map<?, ?>) service.lease(Map.of("applicationId", "A", "country", "CN")).getResult();
        assertThat(response.get("phoneNumber")).isEqualTo("123");
        assertThat(response.get("messageId").toString()).startsWith("sms-");
        assertThat(response.get("leaseActive")).isEqualTo(true);
        verifyNoInteractions(results, projections);
    }
    @Test void readsAreRepeatableAndNeverSubmitOrExtendLease() {
        start(); when(results.loadTaskItemResults("task", List.of("id"))).thenReturn(Map.of("id", result(snapshot("id", true))));
        assertThat(service.get("id")).isEqualTo(service.get("id")).containsEntry("status", "RECEIVED");
        verifyNoInteractions(calls, projections);
    }
    @Test void expiryRetainsLatestSmsAndUnobservedDoesNotInventFailure() {
        assertThat(SmsReceptionService.view("id", result(snapshot("id", true)), 3000)).containsEntry("leaseActive", false).containsEntry("status", "RECEIVED").containsKey("sms");
        assertThat(SmsReceptionService.view("id", result(snapshot("id", false)), 3000)).containsEntry("status", "EXPIRED");
        assertThat(SmsReceptionService.view("id", null, 3000)).isEqualTo(Map.of("messageId", "id", "status", "NOT_OBSERVED"));
    }
    @Test void projectionUsesStoredDeadlineAndIgnoresRejectedOrUnrelatedContent() {
        var rows = Map.of("id", result(snapshot("id", true)), "bad", result(Map.of("messageId", "bad", "status", "REJECTED")),
                "other", result(snapshot("mismatch", false)));
        assertThat(SmsReceptionService.leaseProjection(rows)).isEqualTo(Map.of(new Coordinate("123", "A", "w"), 2000L));
    }
    @Test void invalidRequestFailsBeforeSubmission() {
        start();
        for (var input : List.of(Map.<String,Object>of("applicationId", "A", "country", "CN", "requestId", "old"),
                Map.<String,Object>of("applicationId", "A", "country", "CN", "leaseSeconds", 1.5),
                Map.<String,Object>of("applicationId", "A", "country", "cn")))
            assertThatThrownBy(() -> service.lease(input)).isInstanceOf(SmsReceptionService.ProductError.class);
        verifyNoInteractions(calls, results);
    }
}
