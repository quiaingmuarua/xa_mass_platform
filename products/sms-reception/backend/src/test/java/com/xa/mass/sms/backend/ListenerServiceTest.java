package com.xa.mass.sms.backend;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListenerServiceTest {
    static class Time extends Clock {
        long now = 1_000_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    Map<String, Object> input(String request) {
        return Map.of("requestId", request, "applicationId", "A", "country", "CN", "listenSeconds", 60);
    }
    static class Fixture {
        final WorkerGroupRegistrationService registrations = mock(WorkerGroupRegistrationService.class);
        final TaskCallSubmissionService submissions = mock(TaskCallSubmissionService.class);
        final TaskDataService results = mock(TaskDataService.class);
        Fixture() {
            when(registrations.register(anyString(), anyMap(), anyList())).thenAnswer(i -> {
                String group = i.getArgument(0);
                return new WorkerGroupRegistrationService.Registration(group,
                        "task-" + group.substring(4).toUpperCase(Locale.ROOT), "registered");
            });
            when(results.loadTaskItemResults(anyString(), anyList())).thenReturn(Map.of());
        }
        ListenerService service(Clock time, int limit) {
            var service = new ListenerService(registrations, submissions, results, time, limit, false);
            service.start();
            return service;
        }
    }
    Fixture client() { return new Fixture(); }
    TaskItemResultResponse result(ListenerService.Record record, String status, boolean sms) {
        Map<String, Object> snapshot = new LinkedHashMap<>(Map.of("listenerId", record.id, "applicationId", "A",
                "country", "CN", "phone", "100", "workerId", "real-worker", "startedAt", 1_000_000,
                "expiresAt", 1_060_000, "status", status, "revision", status.equals("LISTENING") ? 0 : 1));
        if (sms) snapshot.put("sms", Map.of("smsId", "sms1", "text", "[A] 123456", "receivedAt", 1_000_020));
        return TaskItemResultResponse.succeeded(Jsons.toJson(snapshot));
    }
    @Test void concurrentIdempotencyAndConflictDoNotCreateExtraCommands() throws Exception {
        Fixture client = client();
        try (var service = client.service(new Time(), 50_000);
             var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Map<String, Object>>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) results.add(pool.submit(() -> service.create(input("same"))));
            Set<Object> ids = new HashSet<>();
            for (var future : results) ids.add(future.get().get("id"));
            assertThat(ids).hasSize(1);
            Map<String, Object> changed = new HashMap<>(input("same")); changed.put("country", "US");
            assertThatThrownBy(() -> service.create(changed)).isInstanceOf(ListenerService.ProductError.class)
                    .hasMessageContaining("different content");
            verify(client.submissions, timeout(2000).times(1)).submit(anyString(), argThat(items -> items.size() == 1));
        }
    }
    @Test void fullLaterSnapshotCanArriveFirstAndLateInitialCannotEraseIt() {
        try (var service = client().service(new Time(), 10)) {
            var record = service.require((String) service.create(input("one")).get("id"));
            service.accept(record, result(record, "RECEIVED", true));
            service.accept(record, result(record, "LISTENING", false));
            assertThat(record.view()).containsEntry("status", "RECEIVED").containsEntry("phone", "100").containsKey("sms");
            assertThat(service.cancel(record.id)).containsEntry("status", "RECEIVED");
        }
    }
    @Test void cancelBeforeOwnershipWaitsThenUsesActualWorkerAndDoesNotInventRemoteSuccess() {
        Fixture client = client();
        try (var service = client.service(new Time(), 10)) {
            var record = service.require((String) service.create(input("one")).get("id"));
            assertThat(service.cancel(record.id)).containsEntry("status", "CANCELLING");
            assertThat(service.metrics()).containsEntry("activeListenersObserved", 0);
            verify(client.submissions, never()).submit(anyString(), argThat(items -> items.stream()
                    .anyMatch(item -> record.cancelId.equals(item.messageId()))));
            service.accept(record, result(record, "LISTENING", false));
            assertThat(service.metrics()).containsEntry("activeListenersObserved", 1);
            verify(client.submissions, timeout(2000)).submit(eq("task-CN"), argThat(items -> items.stream().anyMatch(item ->
                    record.cancelId.equals(item.messageId()) && List.of("workerId", "$eq", "real-worker")
                            .equals(item.workerSelector()) && "extension.worker.sms.listen.cancel".equals(item.eventCode()))));
            assertThat(record.view()).containsEntry("status", "CANCELLING");
            service.accept(record, result(record, "CANCELLED", false));
            assertThat(record.view()).containsEntry("status", "CANCELLED");
        }
    }
    @Test void missingAndFailedPlatformResultBecomeUnconfirmedNotBusinessExpiryOrSuccess() {
        Fixture client = client(); Time time = new Time();
        try (var service = client.service(time, 10)) {
            var record = service.require((String) service.create(input("one")).get("id"));
            service.accept(record, TaskItemResultResponse.failed());
            service.observe();
            assertThat(record.view()).containsEntry("status", "ESTABLISHING");
            time.now = record.observationDeadline;
            service.observe();
            assertThat(record.view()).containsEntry("status", "UNCONFIRMED").doesNotContainKey("sms");
        }
    }
    @Test void recordsAreBoundedAndResultReadsAreBatched() {
        Fixture client = client();
        try (var service = client.service(new Time(), 3)) {
            for (int i = 0; i < 3; i++) service.create(input("id" + i));
            assertThatThrownBy(() -> service.create(input("overflow"))).isInstanceOf(ListenerService.ProductError.class);
            service.observe();
            verify(client.results).loadTaskItemResults(eq("task-CN"), argThat(ids -> ids.size() == 3));
            assertThat(service.page(1, 1)).containsEntry("total", 3);
            assertThatThrownBy(() -> service.page(-1, 1)).isInstanceOf(ListenerService.ProductError.class);
        }
    }
    @Test void constructionAndFailedStartupDoNotLeaveWorkRunning() {
        Fixture fixture = client();
        var service = new ListenerService(fixture.registrations, fixture.submissions, fixture.results);
        verifyNoInteractions(fixture.registrations, fixture.submissions, fixture.results);
        assertThat(service.isRunning()).isFalse();
        assertThatThrownBy(() -> service.create(input("before-start"))).isInstanceOf(ListenerService.ProductError.class);
        when(fixture.registrations.register(eq("sms-us"), anyMap(), anyList()))
                .thenThrow(new IllegalStateException("registration unavailable"));
        assertThatThrownBy(service::start).hasMessage("registration unavailable");
        assertThat(service.isRunning()).isFalse();
        verify(fixture.registrations, never()).register(eq("sms-gb"), anyMap(), anyList());
        assertThatThrownBy(service::start).hasMessageContaining("closed");
        service.close();
    }
    @Test void submissionFailureIsObservedWithoutAutomaticReplayAndShutdownRejectsNewCommands() {
        Fixture fixture = client();
        when(fixture.submissions.submit(anyString(), anyList())).thenThrow(new IllegalStateException("uncertain"));
        try (var service = fixture.service(new Time(), 10)) {
            String id = (String) service.create(input("uncertain")).get("id");
            verify(fixture.submissions, timeout(2000)).submit(eq("task-CN"), anyList());
            service.observe();
            service.observe();
            verify(fixture.submissions, times(1)).submit(eq("task-CN"), anyList());
            assertThat(service.get(id)).containsEntry("status", "ESTABLISHING");
            service.stop();
            assertThatThrownBy(() -> service.create(input("after-stop"))).isInstanceOf(ListenerService.ProductError.class);
        }
    }

}
