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
    void coalescesSameItemAndCompletesDuplicateWaiters()
            throws Exception {
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties(10));
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> first =
                new DeferredResult<>(1_000L);
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> second =
                new DeferredResult<>(1_000L);

        registry.register("task-1", "message-1", first);
        registry.register("task-1", "message-1", second);

        TaskRpcWaitRegistry.ProbeRequest request = registry.takeDue();
        assertThat(request.taskId()).isEqualTo("task-1");
        assertThat(request.messageId()).isEqualTo("message-1");

        registry.completeSuccess(
                "task-1",
                "message-1",
                "{\"ok\":true}"
        );
        assertSucceeded(first);
        assertSucceeded(second);
        assertThat(registry.waiterCount()).isZero();
        registry.finishProbe("task-1", "message-1", 0);
    }

    @Test
    void differentItemsRemainIndependentProbeRequests()
            throws Exception {
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties(10));
        registry.register(
                "task-1",
                "message-1",
                new DeferredResult<>(1_000L)
        );
        registry.register(
                "task-1",
                "message-2",
                new DeferredResult<>(1_000L)
        );

        TaskRpcWaitRegistry.ProbeRequest first = registry.takeDue();
        TaskRpcWaitRegistry.ProbeRequest second = registry.takeDue();

        assertThat(List.of(first.messageId(), second.messageId()))
                .containsExactlyInAnyOrder("message-1", "message-2");
        assertThat(first.taskId()).isEqualTo("task-1");
        assertThat(second.taskId()).isEqualTo("task-1");
        registry.finishProbe(
                first.taskId(),
                first.messageId(),
                250
        );
        registry.finishProbe(
                second.taskId(),
                second.messageId(),
                250
        );
        registry.shutdown();
    }

    @Test
    void resultAndTimeoutHaveOneCompletionWinner() {
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties(10));
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new DeferredResult<>(1_000L);
        TaskRpcWaitRegistry.Waiter waiter = registry.register(
                "task-1",
                "message-1",
                deferred
        );

        assertThat(waiter.completeSuccess("{\"ok\":true}")).isTrue();
        assertThat(waiter.completePending()).isFalse();
        assertSucceeded(deferred);
        assertThat(registry.waiterCount()).isZero();
    }

    @Test
    void enforcesWaiterCapacityAndUsesConfiguredIntervals() {
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties(1));
        registry.register(
                "task-1",
                "message-1",
                new DeferredResult<>(1_000L)
        );

        assertThatThrownBy(() -> registry.register(
                "task-2",
                "message-2",
                new DeferredResult<>(1_000L)
        )).isInstanceOf(ServerException.class);
        assertThat(registry.intervalForWaiterAgeMillis(1_000))
                .isEqualTo(50);
        assertThat(registry.intervalForWaiterAgeMillis(1_001))
                .isEqualTo(100);
        assertThat(registry.intervalForWaiterAgeMillis(5_001))
                .isEqualTo(250);
    }

    @Test
    void sharedVirtualThreadProbesOneItemAtATime()
            throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> first =
                new DeferredResult<>(2_000L);
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> second =
                new DeferredResult<>(2_000L);
        registry.register("task-1", "message-1", first);
        registry.register("task-1", "message-1", second);
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
            assertSucceeded(first);
            assertSucceeded(second);
        } finally {
            probe.stop();
        }
    }

    @Test
    void redisFailureReschedulesTheItemWithoutFailingTheWaiter()
            throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new DeferredResult<>(2_000L);
        registry.register("task-1", "message-1", deferred);
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
            assertSucceeded(deferred);
        } finally {
            probe.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertSucceeded(
            DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred
    ) {
        ResponseEntity<TaskRpcCallResponse> response =
                (ResponseEntity<TaskRpcCallResponse>) deferred.getResult();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status().wireValue())
                .isEqualTo("succeeded");
    }

    private static TaskRpcProperties properties(int maxWaiters) {
        return new TaskRpcProperties(
                30_000,
                60_000,
                maxWaiters,
                50,
                100,
                250,
                10_000,
                100
        );
    }
}
