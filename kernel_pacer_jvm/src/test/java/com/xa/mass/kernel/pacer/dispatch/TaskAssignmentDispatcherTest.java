package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerQuery;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskAssignmentDispatcherTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, 111_111_111})
    void selectedTransferThenClaimPublishesCommandWithReturnedLeaseFence(long expectedScore) {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(expectedScore == 0
                ? workerScores.transferCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L, true)
                : workerScores.transferObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L, true)).thenReturn(Map.of(
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
                new ResultContextCodec()
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
            verify(workerScores).transferCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L, true);
        } else {
            verify(workerScores).transferObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L, true);
        }
        verifyNoMoreInteractions(workerScores);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"STALE,0", "INVALID,0", "NOOP,0", "STALE,111", "INVALID,111", "NOOP,111"})
    void unsuccessfulTransferDoesNotClaimOrPublishEvenWithReturnedCurrentFence(WorkerScoreTransitionStatus status, long expectedScore) {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(expectedScore == 0
                ? workerScores.transferCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L, true)
                : workerScores.transferObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L, true)).thenReturn(Map.of(
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
                new ResultContextCodec()
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
            verify(workerScores).transferCurrentHotScoreLeases("group-1", List.of("worker-1"), 5_000L, true);
        } else {
            verify(workerScores).transferObservedHotScoreLeases("group-1", Map.of("worker-1", expectedScore), 5_000L, true);
        }
        verifyNoMoreInteractions(workerScores);
    }

    @Test
    void mixedCandidatesUseSeparateOperationsWithoutDowngradingRejectedFences() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(workerScores.transferObservedHotScoreLeases("group-1", Map.of("strict", 401L, "stale", 402L),
                5_000L, true)).thenReturn(Map.of(
                        "strict", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 501L),
                        "stale", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, 601L)));
        when(workerScores.transferCurrentHotScoreLeases("group-1", List.of("hint", "sealed"), 5_000L, true))
                .thenReturn(Map.of(
                        "hint", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 701L),
                        "sealed", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, 801L)));
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
        assertEquals(2, new TaskAssignmentDispatcher(itemScores, workerScores, commands, codec).dispatch(dueTask(), List.of(
                attempt(item("m-hint"), 301L, worker("hint", 0)),
                attempt(item("m-stale"), 302L, worker("stale", 402L)),
                attempt(item("m-strict"), 303L, worker("strict", 401L)),
                attempt(item("m-sealed"), 304L, worker("sealed", 0))), 5_000L));
        assertEquals(List.of("hint", "strict"), List.copyOf(published.get().keySet()));
        assertEquals(codec.encode(new ResultContextCodec.ResultContext("task-1", "m-hint", "hint", "group-1", 701L)),
                published.get().get("hint").forward());
        assertEquals(codec.encode(new ResultContextCodec.ResultContext("task-1", "m-strict", "strict", "group-1", 501L)),
                published.get().get("strict").forward());
        verify(workerScores).transferObservedHotScoreLeases("group-1", Map.of("strict", 401L, "stale", 402L), 5_000L, true);
        verify(workerScores).transferCurrentHotScoreLeases("group-1", List.of("hint", "sealed"), 5_000L, true);
        verify(itemScores).rewriteObservedItemScores("task-1", Map.of("m-hint", 301L, "m-strict", 303L), 5_000L, -1);
        verifyNoMoreInteractions(workerScores, itemScores);
    }

    @Test
    void failureAfterFirstTransferDoesNotClaimCompensateOrRetry() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(workerScores.transferObservedHotScoreLeases("group-1", Map.of("strict", 401L), 5_000L, true))
                .thenReturn(Map.of("strict", new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, 501L)));
        when(workerScores.transferCurrentHotScoreLeases("group-1", List.of("hint"), 5_000L, true))
                .thenThrow(new IllegalStateException("current transfer unavailable"));
        assertThrows(IllegalStateException.class, () -> new TaskAssignmentDispatcher(itemScores, workerScores, commands,
                new ResultContextCodec()).dispatch(dueTask(), List.of(
                        attempt(item("m-hint"), 301L, worker("hint", 0)),
                        attempt(item("m-strict"), 302L, worker("strict", 401L))), 5_000L));
        var order = org.mockito.Mockito.inOrder(workerScores);
        order.verify(workerScores).transferObservedHotScoreLeases("group-1", Map.of("strict", 401L), 5_000L, true);
        order.verify(workerScores).transferCurrentHotScoreLeases("group-1", List.of("hint"), 5_000L, true);
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
                new ResultContextCodec()
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
                new ResultContextCodec()
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
                new ResultContextCodec()
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
        return new TaskDescriptor("task-1", "group-1", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(
                        "priority", "0",
                        "maxRetryTimes", "1"
                ), "worker.default", java.util.List.of(new com.xa.mass.kernel.assignment.RefillTarget(java.util.Map.of(), 100)));
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
                new WorkerQuery("worker.default", Map.of())
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
