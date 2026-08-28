package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.IdleAction;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.TaskItemObservation;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AssignmentPacersTest {

    @Test
    void allocationPublishesOnlyMechanismLeasedCandidates() {
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        WorkerCandidateMechanism mechanism = mock(
                WorkerCandidateMechanism.class
        );
        CandidateWorkerCache candidateCache = mock(
                CandidateWorkerCache.class
        );
        WorkerCandidateObservation candidate = mock(
                WorkerCandidateObservation.class
        );
        when(candidateCache.candidateWorkerCounts(List.of("task-1")))
                .thenReturn(Map.of("task-1", 0));
        when(selection.acquireHotPoolCandidates(
                eq("group-1"),
                any(),
                eq(6_000L)
        )).thenReturn(Map.of("task-1", List.of(candidate)));
        TaskWorkerAllocationPolicy policy = new TaskWorkerAllocationPolicy(
                selection,
                mechanism,
                candidateCache,
                () -> 1_000L
        );

        assertEquals(1, policy.allocateCandidateWorkers(
                List.of(due("task-1", WorkerAllocationMechanism
                        .PRECOMPUTED_TASK_RULE,
                        TaskIdleDisposition.CLOSE_WHEN_IDLE)),
                new TaskWorkerAllocationConfig(5_000)
        ));
        verify(mechanism).appendCandidates(
                "task-1",
                List.of(candidate),
                6_000L
        );
    }

    @Test
    void dispatchDelegatesIdleParkToExecutionMechanism() {
        TaskExecutionMechanism execution = mock(
                TaskExecutionMechanism.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        DueTaskObservation task = due(
                "task-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE
        );
        when(execution.observeTaskItems("task-1", 100))
                .thenReturn(List.of());
        TaskDispatchPolicy policy = new TaskDispatchPolicy(
                execution,
                selection,
                () -> 1_000L
        );

        assertEquals(0, policy.dispatchTasks(
                List.of(task),
                new TaskDispatchConfig(100, 5_000)
        ));
        verify(execution).settleNoClaimableItems(
                task,
                IdleAction.PARK,
                1_000L
        );
    }

    @Test
    void oneTaskUsesMultipleCompatibleWorkersInOneDispatchBatch() {
        TaskExecutionMechanism execution = mock(
                TaskExecutionMechanism.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        DueTaskObservation task = due(
                "task-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE
        );
        TaskItemObservation first = item("message-1");
        TaskItemObservation second = item("message-2");
        WorkerCandidateObservation firstWorker = worker("worker-1");
        WorkerCandidateObservation secondWorker = worker("worker-2");
        when(execution.observeTaskItems("task-1", 100))
                .thenReturn(List.of(first, second));
        when(selection.acquireWorkerCandidates(
                eq(WorkerCandidateAcquisitionStrategy.DIRECT),
                eq("group-1"),
                any(),
                eq(6_000L)
        )).thenReturn(Map.of(
                "message-1", List.of(firstWorker),
                "message-2", List.of(secondWorker)
        ));
        AtomicReference<List<TaskExecutionMechanism
                .TaskItemWorkerAssignment>> dispatched =
                new AtomicReference<>();
        doAnswer(invocation -> {
            List<TaskExecutionMechanism.TaskItemWorkerAssignment> batch =
                    invocation.getArgument(1);
            dispatched.set(batch);
            return batch.size();
        }).when(execution).dispatch(any(), any(), eq(6_000L));
        TaskDispatchPolicy policy = new TaskDispatchPolicy(
                execution,
                selection,
                () -> 1_000L
        );

        assertEquals(2, policy.dispatchTasks(
                List.of(task),
                new TaskDispatchConfig(100, 5_000)
        ));
        assertEquals(
                List.of("worker-1", "worker-2"),
                dispatched.get().stream()
                        .map(assignment -> assignment.worker().workerId())
                        .toList()
        );
    }

    @Test
    void dispatchFailureStillAdvancesTaskPacingThroughMechanism() {
        TaskExecutionMechanism execution = mock(
                TaskExecutionMechanism.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        DueTaskObservation task = due(
                "task-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE
        );
        TaskItem item = new TaskItem(
                "message-1",
                "event.demo",
                0,
                Map.of(),
                0,
                null,
                Map.of()
        );
        TaskItemObservation itemObservation = new TaskItemObservation(
                "message-1",
                1,
                item,
                mock(TaskItemReference.class)
        );
        WorkerCandidateObservation worker = new WorkerCandidateObservation(
                "worker-1",
                "group-1",
                new WorkerDescriptor(
                        "worker-1",
                        "group-1",
                        "adapter-1",
                        Map.of(),
                        Map.of()
                ),
                mock(WorkerCandidateReference.class)
        );
        when(execution.observeTaskItems("task-1", 100))
                .thenReturn(List.of(itemObservation));
        when(selection.acquireWorkerCandidates(
                eq(WorkerCandidateAcquisitionStrategy.DIRECT),
                eq("group-1"),
                any(),
                eq(6_000L)
        )).thenReturn(Map.of("message-1", List.of(worker)));
        when(execution.dispatch(any(), any(), eq(6_000L)))
                .thenThrow(new IllegalStateException("publish failed"));
        TaskDispatchPolicy policy = new TaskDispatchPolicy(
                execution,
                selection,
                () -> 1_000L
        );

        assertThrows(IllegalStateException.class, () ->
                policy.dispatchTasks(
                        List.of(task),
                        new TaskDispatchConfig(100, 5_000)
                )
        );
        verify(execution).onDispatchAttemptFinished(
                task,
                1_000L
        );
    }

    private static DueTaskObservation due(
            String taskId,
            WorkerAllocationMechanism mechanism,
            TaskIdleDisposition idle
    ) {
        return new DueTaskObservation(
                taskId,
                mock(TaskSchedulingReference.class),
                descriptor(taskId, mechanism, idle)
        );
    }

    private static TaskDescriptor descriptor(
            String taskId,
            WorkerAllocationMechanism mechanism,
            TaskIdleDisposition idle
    ) {
        return new TaskDescriptor(
                taskId,
                "group-1",
                mechanism,
                idle,
                mechanism == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                        ? Map.of()
                        : null,
                Map.of(
                        "priority", "10",
                        "maximumCandidateWorkers", "2",
                        "maxRetryTimes", "1"
                )
        );
    }

    private static TaskItemObservation item(String messageId) {
        return new TaskItemObservation(
                messageId,
                1,
                new TaskItem(
                        messageId,
                        "event.demo",
                        0,
                        Map.of(),
                        0,
                        null,
                        Map.of()
                ),
                mock(TaskItemReference.class)
        );
    }

    private static WorkerCandidateObservation worker(String workerId) {
        return new WorkerCandidateObservation(
                workerId,
                "group-1",
                new WorkerDescriptor(
                        workerId,
                        "group-1",
                        "adapter-1",
                        Map.of(),
                        Map.of()
                ),
                mock(WorkerCandidateReference.class)
        );
    }
}
