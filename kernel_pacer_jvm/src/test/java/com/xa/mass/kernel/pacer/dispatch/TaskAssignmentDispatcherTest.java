package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionResult;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskAssignmentDispatcherTest {

    @Test
    void observationsContainOnlyAllocatedWorkersAndSplitEventsBeforeCommandEncoding() {
        var items = mock(TaskItemScoreBandCore.class);
        var scores = mock(WorkerScoreCore.class);
        var commands = mock(WorkerCommandRuntime.class);
        var codec = mock(ResultContextCodec.class);
        var verified = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        var claims = new LinkedHashMap<String, TaskItemScoreTransitionResult>();
        var attempts = new ArrayList<TaskAssignmentDispatcher.AssignmentAttempt>();
        var events = List.of("event.a", "event.b", "event.a", "event.rejected", "event.missing");
        for (int i = 0; i < events.size(); i++) {
            verified.put("w" + i, new WorkerScoreTransitionResult(i == 4
                    ? WorkerScoreTransitionStatus.STALE : WorkerScoreTransitionStatus.TRANSITIONED, 500L + i));
            claims.put("m" + i, new TaskItemScoreTransitionResult(i == 3
                    ? TaskItemScoreTransitionStatus.STALE : TaskItemScoreTransitionStatus.TRANSITIONED, 600L + i));
            attempts.add(attempt(new TaskItem("m" + i, events.get(i), 0, Map.of(), 0, null,
                    new WorkerQuery("worker.any", Map.of())), 300L + i, worker("w" + i, 0)));
        }
        when(scores.acquireCurrentHotScoreLeases(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(verified);
        when(items.rewriteObservedItemScores(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(claims);
        var observations = new ArrayList<WorkerObservation>();
        when(codec.encode(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            assertEquals(2, observations.size());
            throw new IllegalArgumentException("encoding failed after assignment");
        });
        long before = System.currentTimeMillis();
        var dispatcher = new TaskAssignmentDispatcher(items, scores, commands, codec, observations::add);
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(dueTask(), attempts, 5_000));
        assertEquals(List.of("event.a", "event.b"), observations.stream().map(WorkerObservation::messageEventName).toList());
        assertEquals(List.of("w0", "w2"), observations.getFirst().workerIds());
        assertEquals(List.of("w1"), observations.getLast().workerIds());
        for (var observation : observations) {
            assertEquals("group-1", observation.workerGroupId());
            assertEquals("worker.assigned", observation.observationEventName());
            org.junit.jupiter.api.Assertions.assertTrue(observation.observedAtMillis() >= before
                    && observation.observedAtMillis() <= System.currentTimeMillis());
            assertThrows(UnsupportedOperationException.class, () -> observation.workerIds().add("another"));
        }
        verifyNoInteractions(commands);
    }

    @Test
    void publicationFailureKeepsObservationAndLaterAllocationNotifiesAgainDespiteSinkFailure() {
        var items = mock(TaskItemScoreBandCore.class);
        var scores = mock(WorkerScoreCore.class);
        var commands = mock(WorkerCommandRuntime.class);
        when(scores.acquireCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L)).thenReturn(Map.of(
                "worker-1", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 501L)));
        when(items.rewriteObservedItemScores("task-1", Map.of("message-1", 301L), 5_000L, -1)).thenReturn(Map.of(
                "message-1", new TaskItemScoreTransitionResult(TaskItemScoreTransitionStatus.TRANSITIONED, 601L)));
        when(commands.appendWorkerCommands(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenThrow(new IllegalStateException("publication failed"))
                .thenReturn(Map.of("worker-1", WorkerCommandAppendStatus.APPENDED));
        var observations = new ArrayList<WorkerObservation>();
        var dispatcher = new TaskAssignmentDispatcher(items, scores, commands, new ResultContextCodec(), observation -> {
            observations.add(observation);
            throw new IllegalStateException("sink failed");
        });
        var attempts = List.of(attempt(item(), 301L, worker("worker-1", 0)));
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(dueTask(), attempts, 5_000));
        assertEquals(1, observations.size());
        assertEquals(1, dispatcher.dispatch(dueTask(), attempts, 5_000));
        assertEquals(2, observations.size());
    }

    @Test
    void failedClaimDoesNotNotifyAndDtoCapturesItsListWithoutAnotherBatchLimit() {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 200; i++) ids.add("worker-" + i);
        var observation = new WorkerObservation("group-1", ids, 1, "event", "worker.assigned");
        ids.clear();
        assertEquals(200, observation.workerIds().size());
        var items = mock(TaskItemScoreBandCore.class);
        var scores = mock(WorkerScoreCore.class);
        var commands = mock(WorkerCommandRuntime.class);
        when(scores.acquireCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L)).thenReturn(Map.of(
                "worker-1", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 501L)));
        when(items.rewriteObservedItemScores("task-1", Map.of("message-1", 301L), 5_000L, -1)).thenReturn(Map.of(
                "message-1", new TaskItemScoreTransitionResult(TaskItemScoreTransitionStatus.STALE, 601L)));
        var observations = new ArrayList<WorkerObservation>();
        assertEquals(0, new TaskAssignmentDispatcher(items, scores, commands, new ResultContextCodec(), observations::add)
                .dispatch(dueTask(), List.of(attempt(item(), 301L, worker("worker-1", 0))), 5_000));
        assertEquals(List.of(), observations);
        verifyNoInteractions(commands);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 111_111_111})
    void selectedAcquisitionThenClaimPublishesCommandWithReturnedLeaseFence(long expectedScore) {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(expectedScore == 0
                ? workerScores.acquireCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L)
                : workerScores.acquireObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L)).thenReturn(Map.of(
                "worker-1",
                new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.TRANSITIONED,
                        555_555_555L
                )
        ));
        when(itemScores.rewriteObservedItemScores(
                "task-1",
                Map.of("message-1", 333_333_333L),
                5_000L,
                -1
        )).thenReturn(Map.of(
                "message-1",
                new TaskItemScoreTransitionResult(
                        TaskItemScoreTransitionStatus.TRANSITIONED,
                        444_444_444L
                )
        ));
        AtomicReference<DeliveryCommand> published = new AtomicReference<>();
        when(commands.appendWorkerCommands(
                org.mockito.ArgumentMatchers.eq("adapter-1"),
                org.mockito.ArgumentMatchers.any()
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, DeliveryCommand> byWorker = invocation.getArgument(1);
            published.set(byWorker.get("worker-1"));
            return Map.of("worker-1", WorkerCommandAppendStatus.APPENDED);
        });

        int count = new TaskAssignmentDispatcher(
                itemScores,
                workerScores,
                commands,
                new ResultContextCodec(), ignored -> {}
        ).dispatch(
                dueTask(),
                List.of(attempt(
                        item(),
                        333_333_333L,
                        worker("worker-1", expectedScore)
                )),
                5_000L
        );

        assertEquals(1, count);
        assertEquals("event.demo", published.get().messageType());
        assertEquals("{\"a\":1,\"z\":2}", published.get().payload());
        assertEquals(
                "{\"messageId\":\"message-1\","
                        + "\"taskId\":\"task-1\","
                        + "\"workerGroupId\":\"group-1\","
                        + "\"workerId\":\"worker-1\","
                        + "\"workerLeaseScore\":555555555}",
                published.get().forward()
        );
        if (expectedScore == 0) {
            verify(workerScores).acquireCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L);
        } else {
            verify(workerScores).acquireObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L);
        }
        verifyNoMoreInteractions(workerScores);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"STALE,0", "INVALID,0", "NOOP,0", "STALE,111", "INVALID,111", "NOOP,111"})
    void unsuccessfulAcquisitionDoesNotClaimOrPublishEvenWithReturnedCurrentFence(WorkerScoreTransitionStatus status, long expectedScore) {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(expectedScore == 0
                ? workerScores.acquireCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L)
                : workerScores.acquireObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L)).thenReturn(Map.of(
                "worker-1",
                new WorkerScoreTransitionResult(
                        status,
                        555_555_555L
                )
        ));

        assertEquals(0, new TaskAssignmentDispatcher(
                itemScores,
                workerScores,
                commands,
                new ResultContextCodec(), ignored -> {}
        ).dispatch(
                dueTask(),
                List.of(attempt(
                        item(),
                        333L,
                        worker("worker-1", expectedScore)
                )),
                5_000L
        ));
        verifyNoInteractions(itemScores, commands);
        if (expectedScore == 0) {
            verify(workerScores).acquireCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L);
        } else {
            verify(workerScores).acquireObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L);
        }
        verifyNoMoreInteractions(workerScores);
    }

    @Test
    void mixedCandidatesUseSeparateOperationsWithoutDowngradingRejectedFences() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(workerScores.acquireObservedHotScoreLeases("group-1", Map.of("strict", 401L, "stale", 402L), 5_000L)).thenReturn(Map.of(
                        "strict", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 501L),
                        "stale", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, 601L)));
        when(workerScores.acquireCurrentHotScoreLeases("group-1", List.of("hint", "occupied"), 5_000L))
                .thenReturn(Map.of(
                        "hint", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 701L),
                        "occupied", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, 801L)));
        when(itemScores.rewriteObservedItemScores("task-1", Map.of("m-hint", 301L, "m-strict", 303L), 5_000L, -1))
                .thenReturn(Map.of(
                        "m-hint", new TaskItemScoreTransitionResult(TaskItemScoreTransitionStatus.TRANSITIONED, 901L),
                        "m-strict", new TaskItemScoreTransitionResult(TaskItemScoreTransitionStatus.TRANSITIONED, 903L)));
        AtomicReference<Map<String, DeliveryCommand>> published = new AtomicReference<>();
        when(commands.appendWorkerCommands(org.mockito.ArgumentMatchers.eq("adapter-1"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    published.set(invocation.getArgument(1));
                    return Map.of("hint", WorkerCommandAppendStatus.APPENDED, "strict", WorkerCommandAppendStatus.APPENDED);
                });
        var codec = new ResultContextCodec();
        assertEquals(2, new TaskAssignmentDispatcher(itemScores, workerScores, commands, codec, ignored -> {}).dispatch(dueTask(), List.of(
                attempt(item("m-hint"), 301L, worker("hint", 0)),
                attempt(item("m-stale"), 302L, worker("stale", 402L)),
                attempt(item("m-strict"), 303L, worker("strict", 401L)),
                attempt(item("m-occupied"), 304L, worker("occupied", 0))), 5_000L));
        assertEquals(List.of("hint", "strict"), List.copyOf(published.get().keySet()));
        assertEquals(codec.encode(new ResultContextCodec.ResultContext("task-1", "m-hint", "hint", "group-1", 701L)),
                published.get().get("hint").forward());
        assertEquals(codec.encode(new ResultContextCodec.ResultContext("task-1", "m-strict", "strict", "group-1", 501L)),
                published.get().get("strict").forward());
        verify(workerScores).acquireObservedHotScoreLeases("group-1", Map.of("strict", 401L, "stale", 402L), 5_000L);
        verify(workerScores).acquireCurrentHotScoreLeases("group-1", List.of("hint", "occupied"), 5_000L);
        verify(itemScores).rewriteObservedItemScores("task-1", Map.of("m-hint", 301L, "m-strict", 303L), 5_000L, -1);
        verifyNoMoreInteractions(workerScores, itemScores);
    }

    @Test
    void failureAfterFirstAcquisitionDoesNotClaimCompensateOrRetry() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(workerScores.acquireObservedHotScoreLeases("group-1", Map.of("strict", 401L), 5_000L))
                .thenReturn(Map.of("strict", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 501L)));
        when(workerScores.acquireCurrentHotScoreLeases("group-1", List.of("hint"), 5_000L))
                .thenThrow(new IllegalStateException("current acquisition unavailable"));
        assertThrows(IllegalStateException.class, () -> new TaskAssignmentDispatcher(itemScores, workerScores, commands,
                new ResultContextCodec(), ignored -> {}).dispatch(dueTask(), List.of(
                        attempt(item("m-hint"), 301L, worker("hint", 0)),
                        attempt(item("m-strict"), 302L, worker("strict", 401L))), 5_000L));
        var order = org.mockito.Mockito.inOrder(workerScores);
        order.verify(workerScores).acquireObservedHotScoreLeases("group-1", Map.of("strict", 401L), 5_000L);
        order.verify(workerScores).acquireCurrentHotScoreLeases("group-1", List.of("hint"), 5_000L);
        verifyNoMoreInteractions(workerScores);
        verifyNoInteractions(itemScores, commands);
    }

    @Test
    void duplicateWorkerIsRejectedBeforeOwnerMutation() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        TaskAssignmentDispatcher dispatcher = new TaskAssignmentDispatcher(
                itemScores,
                workerScores,
                commands,
                new ResultContextCodec(), ignored -> {}
        );

        assertThrows(IllegalArgumentException.class, () ->
                dispatcher.dispatch(
                        dueTask(),
                        List.of(
                                attempt(
                                        item("message-1"),
                                        301L,
                                        worker("worker-1", 401L)
                                ),
                                attempt(
                                        item("message-2"),
                                        302L,
                                        worker("worker-1", 402L)
                                )
                        ),
                        5_000L
                )
        );
        verifyNoInteractions(itemScores, workerScores, commands);
    }

    @Test
    void duplicateItemIsRejectedBeforeOwnerMutation() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        TaskAssignmentDispatcher dispatcher = new TaskAssignmentDispatcher(
                itemScores,
                workerScores,
                commands,
                new ResultContextCodec(), ignored -> {}
        );

        assertThrows(IllegalArgumentException.class, () ->
                dispatcher.dispatch(
                        dueTask(),
                        List.of(
                                attempt(
                                        item("message-1"),
                                        301L,
                                        worker("worker-1", 401L)
                                ),
                                attempt(
                                        item("message-1"),
                                        302L,
                                        worker("worker-2", 402L)
                                )
                        ),
                        5_000L
                )
        );
        verifyNoInteractions(itemScores, workerScores, commands);
    }

    @Test
    void wrongWorkerGroupIsRejectedBeforeOwnerMutation() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        TaskAssignmentDispatcher dispatcher = new TaskAssignmentDispatcher(
                itemScores,
                workerScores,
                commands,
                new ResultContextCodec(), ignored -> {}
        );

        assertThrows(IllegalArgumentException.class, () ->
                dispatcher.dispatch(
                        dueTask(),
                        List.of(attempt(
                                item(),
                                301L,
                                new RoutedWorkerCandidate(
                                        "worker-1",
                                        "other-group",
                                        "adapter-1",
                                        401L
                                )
                        )),
                        5_000L
                )
        );
        verifyNoInteractions(itemScores, workerScores, commands);
    }

    private static ObservedTask dueTask() {
        return new ObservedTask(descriptor(), 777L);
    }

    private static TaskDescriptor descriptor() {
        return new TaskDescriptor("task-1", "test-project", "group-1", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(
                        "priority", "0",
                        "maxRetryTimes", "1"
                ), java.util.List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(java.util.Map.of()), 100)), null, java.util.Map.of());
    }

    private static TaskItem item() {
        return item("message-1");
    }

    private static TaskItem item(String messageId) {
        return new TaskItem(
                messageId,
                "event.demo",
                0,
                Map.of("z", 2, "a", 1),
                0,
                null,
                new WorkerQuery("worker.any", Map.of())
        );
    }

    private static TaskAssignmentDispatcher.AssignmentAttempt attempt(
            TaskItem item,
            long observedItemScore,
            RoutedWorkerCandidate worker
    ) {
        return new TaskAssignmentDispatcher.AssignmentAttempt(
                item,
                observedItemScore,
                worker
        );
    }

    private static RoutedWorkerCandidate worker(
            String workerId,
            long score
    ) {
        return new RoutedWorkerCandidate(
                workerId,
                "group-1",
                "adapter-1",
                score
        );
    }
}
