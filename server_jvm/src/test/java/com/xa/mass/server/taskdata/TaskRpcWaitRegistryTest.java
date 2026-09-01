package com.xa.mass.server.taskdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import jakarta.validation.Validation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;

class TaskRpcWaitRegistryTest {

    @Test
    void coalescesSameItemAndCountsEachWaiterAssociation() throws Exception {
        TaskRpcWaitRegistry registry = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> first =
                deferred();
        DeferredResult<TaskRpcCallResponse> second =
                deferred();

        assertThat(register(registry, "task-1", "message-1", first))
                .isTrue();
        assertThat(register(registry, "task-1", "message-1", second))
                .isTrue();
        assertThat(registry.waiterCount()).isEqualTo(2);
        assertThat(registry.pendingObservationCount()).isEqualTo(2);

        assertThat(registry.takeDueBatch(10)).containsExactly(
                request("task-1", "message-1")
        );

        registry.completeResult(
                "task-1",
                "message-1",
                TaskItemResult.succeeded("{\"ok\":true}")
        );
        assertSucceeded(first, "message-1");
        assertSucceeded(second, "message-1");
        assertThat(registry.waiterCount()).isZero();
        assertThat(registry.pendingObservationCount()).isZero();
        registry.finishProbe("task-1", "message-1", 0);
    }

    @Test
    void failedResultCompletesTheWaiterWithoutPayload() throws Exception {
        TaskRpcWaitRegistry registry = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> deferred = deferred();
        assertThat(register(registry, "task-1", "message-1", deferred))
                .isTrue();
        assertThat(registry.takeDueBatch(10)).containsExactly(
                request("task-1", "message-1")
        );

        registry.completeResult(
                "task-1",
                "message-1",
                TaskItemResult.failed()
        );

        var response = result(deferred).results().get("message-1");
        assertThat(response.status().wireValue()).isEqualTo("failed");
        assertThat(response.opaqueResultPayload()).isNull();
        assertEmpty(registry);
        registry.finishProbe("task-1", "message-1", 0);
    }

    @Test
    void capacityRejectionDoesNotMutateRegistryState() {
        TaskRpcWaitRegistry waiterLimited = registry(1, 10, 256);
        assertThat(register(
                waiterLimited,
                "task-1",
                "message-1",
                deferred()
        )).isTrue();
        assertThat(register(
                waiterLimited,
                "task-2",
                "message-2",
                deferred()
        )).isFalse();
        assertThat(waiterLimited.waiterCount()).isOne();
        assertThat(waiterLimited.pendingObservationCount()).isOne();

        TaskRpcWaitRegistry observationLimited = registry(10, 1, 256);
        assertThat(register(
                observationLimited,
                "task-1",
                "message-1",
                deferred()
        )).isTrue();
        assertThat(register(
                observationLimited,
                "task-1",
                "message-1",
                deferred()
        )).isFalse();
        assertThat(observationLimited.waiterCount()).isOne();
        assertThat(observationLimited.pendingObservationCount()).isOne();

        waiterLimited.shutdown();
        observationLimited.shutdown();
        assertEmpty(waiterLimited);
        assertEmpty(observationLimited);
    }

    @Test
    void timeoutErrorCompletionAndShutdownReleaseCapacity() throws Exception {
        TaskRpcWaitRegistry timedOut = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> timeoutResult =
                deferred();
        assertThat(register(
                timedOut,
                "task-timeout",
                "message-timeout",
                timeoutResult
        )).isTrue();
        lifecycle(timeoutResult).handleTimeout(null, timeoutResult);
        assertThat(result(timeoutResult).results()
                .get("message-timeout").status().wireValue())
                .isEqualTo("not_observed");
        lifecycle(timeoutResult).afterCompletion(null, timeoutResult);
        assertEmpty(timedOut);

        TaskRpcWaitRegistry errored = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> errorResult =
                deferred();
        assertThat(register(
                errored,
                "task-error",
                "message-error",
                errorResult
        )).isTrue();
        lifecycle(errorResult).handleError(
                null,
                errorResult,
                new IllegalStateException("client error")
        );
        assertEmpty(errored);

        TaskRpcWaitRegistry completed = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> completionResult =
                deferred();
        assertThat(register(
                completed,
                "task-completion",
                "message-completion",
                completionResult
        )).isTrue();
        lifecycle(completionResult).afterCompletion(null, completionResult);
        assertEmpty(completed);

        TaskRpcWaitRegistry stopped = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> shutdownResult =
                deferred();
        assertThat(stopped.tryRegister(
                "task-shutdown",
                List.of("observed", "missing"),
                Map.of("observed", TaskItemResult.succeeded("done")),
                shutdownResult
        )).isTrue();
        stopped.shutdown();
        TaskRpcCallResponse shutdownResponse = result(shutdownResult);
        assertThat(shutdownResponse.results().get("observed").status()
                .wireValue()).isEqualTo("succeeded");
        assertThat(shutdownResponse.results().get("missing").status()
                .wireValue()).isEqualTo("not_observed");
        assertEmpty(stopped);
    }

    @Test
    void dueBatchIsBoundedAndFiltersARecreatedItemsStaleGeneration()
            throws Exception {
        TaskRpcWaitRegistry staleRegistry = registry(10, 10, 256);
        DeferredResult<TaskRpcCallResponse> oldResult =
                deferred();
        assertThat(register(
                staleRegistry,
                "task-1",
                "message-1",
                oldResult
        )).isTrue();
        staleRegistry.completeResult(
                "task-1",
                "message-1",
                TaskItemResult.succeeded("first")
        );
        assertSucceeded(oldResult, "message-1");

        DeferredResult<TaskRpcCallResponse> currentResult =
                deferred();
        assertThat(register(
                staleRegistry,
                "task-1",
                "message-1",
                currentResult
        )).isTrue();
        assertThat(staleRegistry.takeDueBatch(10)).containsExactly(
                request("task-1", "message-1")
        );
        assertThat(currentResult.hasResult()).isFalse();
        staleRegistry.shutdown();

        TaskRpcWaitRegistry boundedRegistry = registry(10, 10, 2);
        assertThat(boundedRegistry.tryRegister(
                "task-2",
                List.of("message-1", "message-2", "message-3"),
                Map.of(),
                deferred()
        )).isTrue();
        assertThat(boundedRegistry.takeDueBatch(2))
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(2);
        boundedRegistry.shutdown();
        assertEmpty(boundedRegistry);
    }

    @Test
    void probeBatchesOneHundredItemsPerTaskAndFansOutResults()
            throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10, 200, 200);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        List<String> firstTaskIds = IntStream.range(0, 100)
                .mapToObj(index -> "message-" + index)
                .toList();
        List<String> secondTaskIds = List.of("message-a", "message-b");
        DeferredResult<TaskRpcCallResponse> first =
                deferred();
        DeferredResult<TaskRpcCallResponse> second =
                deferred();
        assertThat(registry.tryRegister(
                "task-1",
                firstTaskIds,
                Map.of(),
                first
        )).isTrue();
        assertThat(registry.tryRegister(
                "task-2",
                secondTaskIds,
                Map.of(),
                second
        )).isTrue();
        when(taskRuntime.loadTaskItemResults(eq("task-1"), anyList()))
                .thenAnswer(invocation -> resultsFor(
                        invocation.getArgument(1)
                ));
        when(taskRuntime.loadTaskItemResults(eq("task-2"), anyList()))
                .thenAnswer(invocation -> resultsFor(
                        invocation.getArgument(1)
                ));

        probe.probe(registry.takeDueBatch(200));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> firstIds = ArgumentCaptor.forClass(
                List.class
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> secondIds = ArgumentCaptor.forClass(
                List.class
        );
        verify(taskRuntime).loadTaskItemResults(
                eq("task-1"),
                firstIds.capture()
        );
        verify(taskRuntime).loadTaskItemResults(
                eq("task-2"),
                secondIds.capture()
        );
        assertThat(firstIds.getValue())
                .containsExactlyInAnyOrderElementsOf(firstTaskIds);
        assertThat(secondIds.getValue())
                .containsExactlyInAnyOrderElementsOf(secondTaskIds);
        assertThat(result(first).results()).hasSize(100);
        assertThat(result(second).results()).hasSize(2);
        assertEmpty(registry);
    }

    @Test
    void partialProbeSuccessReleasesAndReschedulesOnlyMissingItems()
            throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = fastProperties();
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        DeferredResult<TaskRpcCallResponse> deferred =
                deferred();
        assertThat(registry.tryRegister(
                "task-1",
                List.of("message-1", "message-2"),
                Map.of(),
                deferred
        )).isTrue();
        when(taskRuntime.loadTaskItemResults(
                eq("task-1"),
                anyList()
        )).thenReturn(Map.of(
                "message-1",
                TaskItemResult.succeeded("one")
        ));

        probe.probe(registry.takeDueBatch(10));

        assertThat(deferred.hasResult()).isFalse();
        assertThat(registry.waiterCount()).isOne();
        assertThat(registry.pendingObservationCount()).isOne();
        assertThat(registry.takeDueBatch(10)).containsExactly(
                request("task-1", "message-2")
        );
        registry.completeResult(
                "task-1",
                "message-2",
                TaskItemResult.succeeded("two")
        );
        registry.finishProbe("task-1", "message-2", 0);
        assertThat(result(deferred).results().keySet())
                .containsExactly("message-1", "message-2");
        assertEmpty(registry);
    }

    @Test
    void failedTaskGroupIsRescheduledWithoutBlockingOtherTasks()
            throws Exception {
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = fastProperties();
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        TaskRpcResultProbe probe = new TaskRpcResultProbe(
                taskRuntime,
                registry,
                properties
        );
        DeferredResult<TaskRpcCallResponse> failed =
                deferred();
        DeferredResult<TaskRpcCallResponse> succeeded =
                deferred();
        assertThat(register(
                registry,
                "task-failed",
                "message-1",
                failed
        )).isTrue();
        assertThat(register(
                registry,
                "task-succeeded",
                "message-2",
                succeeded
        )).isTrue();
        when(taskRuntime.loadTaskItemResults(
                "task-failed",
                List.of("message-1")
        )).thenThrow(new IllegalStateException("Redis unavailable"));
        when(taskRuntime.loadTaskItemResults(
                "task-succeeded",
                List.of("message-2")
        )).thenReturn(Map.of(
                "message-2",
                TaskItemResult.succeeded("done")
        ));

        probe.probe(registry.takeDueBatch(10));

        assertSucceeded(succeeded, "message-2");
        assertThat(failed.hasResult()).isFalse();
        assertThat(registry.takeDueBatch(10)).containsExactly(
                request("task-failed", "message-1")
        );
        registry.shutdown();
        assertEmpty(registry);
    }

    @Test
    void validatesObservationAndProbeCapacityBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            TaskRpcProperties noObservations = properties(10, 0, 256);
            TaskRpcProperties noProbeItems = properties(10, 10, 0);
            TaskRpcProperties oversizedProbe = properties(10, 10, 1_001);

            assertThat(validator.validate(noObservations))
                    .extracting(violation ->
                            violation.getPropertyPath().toString())
                    .containsExactly("maxPendingObservations");
            assertThat(validator.validate(noProbeItems))
                    .extracting(violation ->
                            violation.getPropertyPath().toString())
                    .containsExactly("maxProbeItemsPerRound");
            assertThat(validator.validate(oversizedProbe))
                    .extracting(violation ->
                            violation.getPropertyPath().toString())
                    .containsExactly("maxProbeItemsPerRound");
        }
    }

    private static boolean register(
            TaskRpcWaitRegistry registry,
            String taskId,
            String messageId,
            DeferredResult<TaskRpcCallResponse> deferred
    ) {
        return registry.tryRegister(
                taskId,
                List.of(messageId),
                Map.of(),
                deferred
        );
    }

    private static TaskRpcWaitRegistry.ProbeRequest request(
            String taskId,
            String messageId
    ) {
        return new TaskRpcWaitRegistry.ProbeRequest(taskId, messageId);
    }

    private static Map<String, TaskItemResult> resultsFor(
            List<String> messageIds
    ) {
        var results = new LinkedHashMap<String, TaskItemResult>();
        messageIds.forEach(messageId -> results.put(
                messageId,
                TaskItemResult.succeeded("result-" + messageId)
        ));
        return results;
    }

    private static void assertSucceeded(
            DeferredResult<TaskRpcCallResponse> deferred,
            String messageId
    ) {
        TaskRpcCallResponse response = result(deferred);
        assertThat(response).isNotNull();
        assertThat(response.results().get(messageId).status()
                .wireValue()).isEqualTo("succeeded");
    }

    private static void assertEmpty(TaskRpcWaitRegistry registry) {
        assertThat(registry.waiterCount()).isZero();
        assertThat(registry.pendingObservationCount()).isZero();
    }

    @SuppressWarnings("unchecked")
    private static TaskRpcCallResponse result(
            DeferredResult<TaskRpcCallResponse> deferred
    ) {
        return (TaskRpcCallResponse) deferred.getResult();
    }

    @SuppressWarnings("unchecked")
    private static DeferredResultProcessingInterceptor lifecycle(
            DeferredResult<?> deferred
    ) {
        return ReflectionTestUtils.invokeMethod(
                deferred,
                "getLifecycleInterceptor"
        );
    }

    private static DeferredResult<TaskRpcCallResponse> deferred() {
        return new DeferredResult<>(2_000L);
    }

    private static TaskRpcWaitRegistry registry(
            int maxWaiters,
            int maxPendingObservations,
            int maxProbeItemsPerRound
    ) {
        return new TaskRpcWaitRegistry(properties(
                maxWaiters,
                maxPendingObservations,
                maxProbeItemsPerRound
        ));
    }

    private static TaskRpcProperties properties(
            int maxWaiters,
            int maxPendingObservations,
            int maxProbeItemsPerRound
    ) {
        return new TaskRpcProperties(
                30_000,
                60_000,
                maxWaiters,
                maxPendingObservations,
                maxProbeItemsPerRound,
                50,
                100,
                250
        );
    }

    private static TaskRpcProperties fastProperties() {
        return new TaskRpcProperties(
                30_000,
                60_000,
                10,
                10,
                10,
                1,
                1,
                1
        );
    }
}
