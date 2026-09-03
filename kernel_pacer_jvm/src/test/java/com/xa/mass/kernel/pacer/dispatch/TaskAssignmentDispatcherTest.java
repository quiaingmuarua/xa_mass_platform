package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskAssignmentDispatcherTest {

    @Test
    void exactLeaseThenClaimPublishesCommandWithReturnedLeaseFence() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(workerScores.renewActiveHotScoreLeases(
                "group-1",
                Map.of("worker-1", 111_111_111L),
                5_000L
        )).thenReturn(Map.of(
                "worker-1",
                new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.NOOP,
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
                        worker("worker-1", 111_111_111L)
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
    }

    @Test
    void staleWorkerLeaseDoesNotClaimOrPublish() {
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        WorkerScoreCore workerScores = mock(WorkerScoreCore.class);
        WorkerCommandRuntime commands = mock(WorkerCommandRuntime.class);
        when(workerScores.renewActiveHotScoreLeases(
                "group-1",
                Map.of("worker-1", 111L),
                5_000L
        )).thenReturn(Map.of(
                "worker-1",
                new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.STALE,
                        null
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
                        worker("worker-1", 111L)
                )),
                5_000L
        ));
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
                                new HeldWorkerCandidate(
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
        return new TaskDescriptor(
                "task-1",
                "group-1",
                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "1"
                )
        );
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
                List.of()
        );
    }

    private static TaskAssignmentDispatcher.AssignmentAttempt attempt(
            TaskItem item,
            long observedItemScore,
            HeldWorkerCandidate worker
    ) {
        return new TaskAssignmentDispatcher.AssignmentAttempt(
                item,
                observedItemScore,
                worker
        );
    }

    private static HeldWorkerCandidate worker(
            String workerId,
            long score
    ) {
        return new HeldWorkerCandidate(
                workerId,
                "group-1",
                "adapter-1",
                score
        );
    }
}
