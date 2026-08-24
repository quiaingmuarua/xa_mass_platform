package com.xa.mass.server.taskdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

class TaskRpcWaitRegistryTest {

    @Test
    void coalescesSameItemAndCompletesBothBatchWaiters() throws Exception {
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties(10));
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> first =
                new DeferredResult<>(1_000L);
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> second =
                new DeferredResult<>(1_000L);

        registry.register(
                "task-1",
                List.of("message-1"),
                Map.of(),
                first
        );
        registry.register(
                "task-1",
                List.of("message-1"),
                Map.of(),
                second
        );

        TaskRpcWaitRegistry.ProbeRequest request = registry.takeDue();
        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.messageId()).isEqualTo("message-1");

        registry.completeSuccess("task-1", "message-1", "{\"ok\":true}");
        assertSucceeded(first, "message-1");
        assertSucceeded(second, "message-1");
        assertThat(registry.waiterCount()).isZero();
        registry.finishProbe("task-1", "message-1", 0);
    }

    @Test
    void batchWaitsForEveryItemAndTimeoutMarksOnlyMissingItems() {
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties(10));
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new DeferredResult<>(1_000L);
        TaskRpcWaitRegistry.BatchWaiter waiter = registry.register(
                "task-1",
                List.of("message-1", "message-2"),
                Map.of("message-1", "one"),
                deferred
        );

        assertThat(waiter.completeNotObserved()).isTrue();
        assertThat(waiter.completeNotObserved()).isFalse();
        ResponseEntity<TaskRpcCallResponse> response = result(deferred);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().results().get("message-1").status()
                .wireValue()).isEqualTo("succeeded");
        assertThat(response.getBody().results().get("message-2").status()
                .wireValue()).isEqualTo("not_observed");
        assertThat(registry.waiterCount()).isZero();
    }

    @Test
    void capacityContinuesToCountWaitingRequests() {
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties(1));

        TaskRpcWaitRegistry.BatchWaiter first = registry.register(
                "task-1",
                List.of("message-1", "message-2"),
                Map.of(),
                new DeferredResult<>(1_000L)
        );
        assertThatThrownBy(() -> registry.register(
                "task-2",
                List.of("message-3"),
                Map.of(),
                new DeferredResult<>(1_000L)
        )).isInstanceOf(ServerException.class);
        assertThat(registry.waiterCount()).isOne();

        first.completeNotObserved();
        assertThat(registry.waiterCount()).isZero();
        assertThat(registry.intervalForWaiterAgeMillis(1_000)).isEqualTo(50);
        assertThat(registry.intervalForWaiterAgeMillis(1_001)).isEqualTo(100);
        assertThat(registry.intervalForWaiterAgeMillis(5_001)).isEqualTo(250);
    }

    @Test
    void sharedVirtualThreadProbeCompletesABatchItem() throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new DeferredResult<>(2_000L);
        registry.register(
                "task-1",
                List.of("message-1"),
                Map.of(),
                deferred
        );
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1")
        )).thenReturn(Map.of("message-1", "{\"value\":1}"));

        probe.start();
        try {
            verify(taskRuntime, timeout(1_000))
                    .loadTaskItemSuccessResults(
                            "task-1",
                            List.of("message-1")
                    );
            assertSucceeded(deferred, "message-1");
        } finally {
            probe.stop();
        }
    }

    @Test
    void redisFailureReschedulesWithoutFailingTheBatch() throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new DeferredResult<>(2_000L);
        registry.register(
                "task-1",
                List.of("message-1"),
                Map.of(),
                deferred
        );
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1")
        ))
                .thenThrow(new IllegalStateException("Redis unavailable"))
                .thenReturn(Map.of("message-1", "{\"value\":1}"));

        probe.start();
        try {
            verify(taskRuntime, timeout(1_500).atLeast(2))
                    .loadTaskItemSuccessResults(
                            "task-1",
                            List.of("message-1")
                    );
            assertSucceeded(deferred, "message-1");
        } finally {
            probe.stop();
        }
    }

    private static void assertSucceeded(
            DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred,
            String messageId
    ) {
        long deadline = System.nanoTime()
                + java.time.Duration.ofSeconds(1).toNanos();
        while (!deferred.hasResult() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ResponseEntity<TaskRpcCallResponse> response = result(deferred);
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().results().get(messageId).status()
                .wireValue()).isEqualTo("succeeded");
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<TaskRpcCallResponse> result(
            DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred
    ) {
        return (ResponseEntity<TaskRpcCallResponse>) deferred.getResult();
    }

    private static TaskRpcProperties properties(int maxWaiters) {
        return new TaskRpcProperties(
                30_000,
                60_000,
                maxWaiters,
                50,
                100,
                250
        );
    }
}
