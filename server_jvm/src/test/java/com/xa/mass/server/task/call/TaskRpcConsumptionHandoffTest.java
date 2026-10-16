package com.xa.mass.server.task.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;

@Timeout(10)
class TaskRpcConsumptionHandoffTest {
    private final TaskRpcProperties properties = new TaskRpcProperties(
            Duration.ofDays(7), 1000, 1000, 10, 100, 100, 50, 100, 250, Map.of());
    private final TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
    private final TaskRuntime runtime = mock(TaskRuntime.class);
    private final TaskRpcResultProbe probe = new TaskRpcResultProbe(runtime, registry, properties);

    @Test
    void timeoutBeforeTakeLeavesTheResultForALaterConsumer() throws Exception {
        var response = register("expired", "m");
        var due = registry.takeDueBatch(100);
        timeout(response);

        probe.probe(due);

        assertThat(results(response).get("m").status().wireValue()).isEqualTo("not_observed");
        verifyNoInteractions(runtime);
        assertEmpty();
        var later = register("expired", "m");
        when(runtime.takeTaskItemResults("expired", List.of("m")))
                .thenReturn(Map.of("m", TaskItemResult.succeeded("still in Redis")));
        probe.probe(registry.takeDueBatch(100));
        assertThat(results(later).get("m").status().wireValue()).isEqualTo("succeeded");
        assertEmpty();
    }

    @Test
    void timeoutOfOneItemDoesNotConsumeItAlongsideALiveItem() throws Exception {
        var expired = register("task", "expired");
        var live = register("task", "live");
        var due = registry.takeDueBatch(100);
        timeout(expired);
        when(runtime.takeTaskItemResults("task", List.of("live")))
                .thenReturn(Map.of("live", TaskItemResult.succeeded("result")));

        probe.probe(due);

        verify(runtime).takeTaskItemResults("task", List.of("live"));
        verifyNoMoreInteractions(runtime);
        assertThat(results(live).get("live").status().wireValue()).isEqualTo("succeeded");
        assertEmpty();
    }

    @Test
    void timeoutDuringTakeIncludesTheConsumedResultAndDoesNotBlockOtherWaiters() throws Exception {
        raceTimeoutWithTake(Map.of("m", TaskItemResult.succeeded("taken")), false);
    }

    @Test
    void timeoutDuringPartialTakeKeepsHitsAndMarksOnlyTheRemainderUnobserved() throws Exception {
        raceTimeoutWithTake(Map.of("m", TaskItemResult.succeeded("taken")), true);
    }

    @Test
    void timeoutDuringAnEmptyTakeFinishesWithoutAnotherProbe() throws Exception {
        raceTimeoutWithTake(Map.of(), false);
    }

    @Test
    void timeoutDuringAFailedTakeReleasesTheWaiterWithoutRetryingExecution() throws Exception {
        raceTimeoutWithTake(null, false);
    }

    private void raceTimeoutWithTake(Map<String, TaskItemResult> taken, boolean partial) throws Exception {
        List<String> ids = partial ? List.of("m", "pending") : List.of("m");
        var response = new DeferredResult<Map<String, TaskItemResultResponse>>();
        assertThat(registry.tryRegister("task", ids, Map.of(), response)).isTrue();
        var due = registry.takeDueBatch(100);
        var takeEntered = new CountDownLatch(1);
        var finishTake = new CountDownLatch(1);
        when(runtime.takeTaskItemResults("task", ids)).thenAnswer(ignored -> {
            takeEntered.countDown();
            assertThat(finishTake.await(5, TimeUnit.SECONDS)).isTrue();
            if (taken == null) throw new IllegalStateException("take failed");
            return taken;
        });
        var probeDone = new CompletableFuture<Void>();
        Thread.ofVirtual().start(() -> {
            try { probe.probe(due); probeDone.complete(null); }
            catch (Throwable failure) { probeDone.completeExceptionally(failure); }
        });
        var timeoutDone = new CompletableFuture<Void>();
        try {
            assertThat(takeEntered.await(2, TimeUnit.SECONDS)).isTrue();
            Thread timeoutThread = Thread.ofVirtual().start(() -> {
                try { timeout(response); timeoutDone.complete(null); }
                catch (Throwable failure) { timeoutDone.completeExceptionally(failure); }
            });
            await().atMost(Duration.ofSeconds(2)).until(() ->
                    timeoutDone.isDone() || timeoutThread.getState() == Thread.State.WAITING);
            assertThat(timeoutDone).isNotDone();
            assertThat(response.hasResult()).isFalse();

            // Registry admission and an unrelated timeout must not wait for Redis.
            var unrelated = CompletableFuture.runAsync(() -> {
                try { timeout(register("other-task", "other-item")); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            unrelated.get(2, TimeUnit.SECONDS);
            assertThat(registry.waiterCount()).isOne();
        } finally {
            finishTake.countDown();
        }
        probeDone.get(2, TimeUnit.SECONDS);
        timeoutDone.get(2, TimeUnit.SECONDS);
        assertThat(results(response).get("m").status().wireValue())
                .isEqualTo(taken != null && taken.containsKey("m") ? "succeeded" : "not_observed");
        if (partial) assertThat(results(response).get("pending").status().wireValue()).isEqualTo("not_observed");
        verify(runtime).takeTaskItemResults("task", ids);
        verifyNoMoreInteractions(runtime);
        assertEmpty();
    }

    private DeferredResult<Map<String, TaskItemResultResponse>> register(String task, String message) {
        var response = new DeferredResult<Map<String, TaskItemResultResponse>>();
        assertThat(registry.tryRegister(task, List.of(message), Map.of(), response)).isTrue();
        return response;
    }

    private static void timeout(DeferredResult<?> response) throws Exception {
        DeferredResultProcessingInterceptor lifecycle = ReflectionTestUtils.invokeMethod(response, "getLifecycleInterceptor");
        lifecycle.handleTimeout(null, response);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TaskItemResultResponse> results(DeferredResult<?> response) {
        return (Map<String, TaskItemResultResponse>) response.getResult();
    }

    private void assertEmpty() {
        assertThat(registry.waiterCount()).isZero();
        assertThat(registry.pendingObservationCount()).isZero();
    }
}
