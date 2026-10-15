package com.xa.mass.scenario.sms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.call.TaskRpcPreparationService;
import com.xa.mass.server.task.observation.TaskLeaseProjectionAccess;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class SmsResultCacheTest {
    private static final TaskCaller ADMIN = com.xa.mass.server.testing.TaskCallers.caller("proof", "admin");
    private static final TaskCaller SERVICE = TaskCaller.forProjectService(new com.xa.mass.server.project.ProjectDefinition("sms", List.of("g")));
    private final TaskRpcPreparationService preparation = mock(TaskRpcPreparationService.class);
    private final TaskRpcCallService calls = mock(TaskRpcCallService.class);
    private final TaskDataService data = mock(TaskDataService.class);
    private final TaskLeaseProjectionAccess projection = mock(TaskLeaseProjectionAccess.class);
    private final AtomicLong ticker = new AtomicLong();
    private final AtomicLong time = new AtomicLong(1000);
    private SmsReceptionService service;

    private SmsReceptionService start(long capacity) {
        when(preparation.prepare(eq(SERVICE), eq("sms"), anyString(), any())).thenReturn(new TaskCreateResponse("task"));
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenAnswer(ignored -> time.get());
        var instance = new SmsReceptionService(preparation, calls, data, projection, "g", clock, SERVICE,
                new SmsResultCacheProperties(capacity, Duration.ofMinutes(10)), ticker::get);
        instance.start();
        return instance;
    }
    @AfterEach void close() { if (service != null) service.close(); }

    private static TaskItemResultResponse value(String id, long revision) {
        var snapshot = new LinkedHashMap<String, Object>(Map.of("messageId", id, "applicationId", "A", "country", "CN",
                "phoneNumber", "123", "workerId", "w", "leaseUntil", 2000, "status", revision == 0 ? "WAITING" : "RECEIVED", "revision", revision));
        if (revision > 0) snapshot.put("sms", Map.of("smsId", "sms-" + revision, "text", "message-" + revision));
        return TaskItemResultResponse.succeeded(Jsons.toJson(snapshot));
    }
    private void sequence(String id, TaskItemResultResponse first, TaskItemResultResponse... rest) {
        var pending = new ArrayDeque<TaskItemResultResponse>(); pending.add(first); pending.addAll(List.of(rest));
        when(data.takeTaskItemResults(SERVICE, "sms", "task", List.of(id))).thenAnswer(ignored ->
                pending.isEmpty() ? Map.of() : Map.of(id, pending.removeFirst()));
    }

    @Test void cachedQueriesStillConsumeNewResultsAndDoNotRegressOnEmptyOldOrFailedResults() {
        service = start(10);
        sequence("m", value("m", 0), value("m", 2), value("m", 1), TaskItemResultResponse.failed());
        assertThat(service.get(ADMIN, "m")).containsEntry("status", "WAITING");
        for (int n = 0; n < 4; n++) assertThat(((Number) service.get(ADMIN, "m").get("revision")).longValue()).isEqualTo(2);
        verify(data, times(5)).takeTaskItemResults(SERVICE, "sms", "task", List.of("m"));
        verify(projection, times(2)).acceptResults(eq(SERVICE), eq("task"), anyMap());
        verifyNoInteractions(calls);
        time.set(3000);
        assertThat(service.get(ADMIN, "m")).containsEntry("status", "RECEIVED").containsEntry("leaseActive", false);
    }

    @Test void readsAndEqualOrLowerRevisionsDoNotRefreshExpiration() {
        service = start(10);
        sequence("m", value("m", 2), value("m", 2), value("m", 1));
        service.get(ADMIN, "m");
        ticker.set(Duration.ofMinutes(9).toNanos());
        service.get(ADMIN, "m"); service.get(ADMIN, "m"); service.get(ADMIN, "m");
        ticker.set(Duration.ofMinutes(11).toNanos());
        assertThat(service.get(ADMIN, "m")).containsEntry("status", "NOT_OBSERVED");
        verify(projection).acceptResults(eq(SERVICE), eq("task"), anyMap());
    }

    @Test void aNewVersionRenewsExpirationAndCachedLeaseStateUsesCurrentTime() {
        service = start(10);
        sequence("m", value("m", 0), value("m", 1));
        service.get(ADMIN, "m");
        ticker.set(Duration.ofMinutes(9).toNanos());
        service.get(ADMIN, "m");
        ticker.set(Duration.ofMinutes(11).toNanos()); time.set(5000);
        assertThat(service.get(ADMIN, "m")).containsEntry("status", "RECEIVED").containsEntry("leaseActive", false);
        ticker.set(Duration.ofMinutes(20).toNanos());
        assertThat(service.get(ADMIN, "m")).containsEntry("status", "NOT_OBSERVED");
    }

    @Test void aDelayedOlderTakeCannotOverwriteANewerResponse() throws Exception {
        service = start(10);
        var oldTaken = new CountDownLatch(1); var releaseOld = new CountDownLatch(1);
        var attempts = new AtomicInteger();
        when(data.takeTaskItemResults(SERVICE, "sms", "task", List.of("m"))).thenAnswer(ignored -> {
            if (attempts.getAndIncrement() == 0) {
                oldTaken.countDown(); assertThat(releaseOld.await(3, TimeUnit.SECONDS)).isTrue();
                return Map.of("m", value("m", 0));
            }
            return Map.of("m", value("m", 2));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slow = executor.submit(() -> service.get(ADMIN, "m"));
            assertThat(oldTaken.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(ADMIN, "m")).containsEntry("status", "RECEIVED");
            releaseOld.countDown();
            assertThat(slow.get(3, TimeUnit.SECONDS)).containsEntry("status", "RECEIVED");
        } finally { releaseOld.countDown(); }
        verify(projection).acceptResults(eq(SERVICE), eq("task"), anyMap());
    }

    @Test void boundedCacheAndRestartLoseOnlyLocallyRetainedContent() throws Exception {
        service = start(1);
        sequence("a", value("a", 1)); sequence("b", value("b", 1));
        service.get(ADMIN, "a"); service.get(ADMIN, "b");
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (((Number) service.metrics(ADMIN).get("cacheSize")).longValue() > 1 && System.nanoTime() < until) Thread.sleep(2);
        assertThat(((Number) service.metrics(ADMIN).get("cacheSize")).longValue()).isLessThanOrEqualTo(1);
        assertThat(List.of(service.get(ADMIN, "a").get("status"), service.get(ADMIN, "b").get("status"))).contains("NOT_OBSERVED");
        service.close(); service = start(10);
        assertThat(service.get(ADMIN, "a")).containsEntry("status", "NOT_OBSERVED");
        sequence("a", value("a", 3));
        assertThat(service.get(ADMIN, "a")).containsEntry("status", "RECEIVED");
    }

    @Test void cacheNeverBypassesAccessChecksOrHidesRedisFailureAndLostTakesAreNotResubmitted() {
        service = start(10);
        sequence("m", value("m", 1)); service.get(ADMIN, "m");
        clearInvocations(data);
        var operator = com.xa.mass.server.testing.TaskCallers.caller("other", "operator");
        assertThatThrownBy(() -> service.get(operator, "m")).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        verifyNoInteractions(data);
        when(data.takeTaskItemResults(SERVICE, "sms", "task", List.of("m"))).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThatThrownBy(() -> service.get(ADMIN, "m")).hasMessage("Redis unavailable");
        when(data.takeTaskItemResults(SERVICE, "sms", "task", List.of("lost")))
                .thenThrow(new IllegalStateException("take response lost")).thenReturn(Map.of());
        assertThatThrownBy(() -> service.get(ADMIN, "lost")).hasMessage("take response lost");
        assertThat(service.get(ADMIN, "lost")).containsEntry("status", "NOT_OBSERVED");
        verifyNoInteractions(calls);
    }

    @Test void stoppingAfterTakeBeforeCacheMergeDoesNotRecoverOrResubmitTheConsumedResult() throws Exception {
        service = start(10);
        var taken = new CountDownLatch(1);
        var returnResponse = new CountDownLatch(1);
        when(data.takeTaskItemResults(SERVICE, "sms", "task", List.of("m"))).thenAnswer(ignored -> {
            taken.countDown();
            assertThat(returnResponse.await(3, TimeUnit.SECONDS)).isTrue();
            return Map.of("m", value("m", 1));
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var query = executor.submit(() -> service.get(ADMIN, "m"));
            assertThat(taken.await(3, TimeUnit.SECONDS)).isTrue();
            service.stop();
            returnResponse.countDown();
            assertThatThrownBy(() -> query.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(SmsReceptionService.ProductError.class);
            verifyNoInteractions(projection, calls);
            when(data.takeTaskItemResults(SERVICE, "sms", "task", List.of("m"))).thenReturn(Map.of());
            service = start(10);
            assertThat(service.get(ADMIN, "m")).containsEntry("status", "NOT_OBSERVED");
        } finally { returnResponse.countDown(); }
    }
}
