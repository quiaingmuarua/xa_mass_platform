package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AssignmentPacersTest {

    @Test
    void defaultRuleDispatchUsesTaskItemTargetsDirectly() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskAssignmentDispatcher dispatcher = mock(
                TaskAssignmentDispatcher.class
        );
        WorkerCandidateSelectionPolicy selection = mock(
                WorkerCandidateSelectionPolicy.class
        );
        TaskItem explicit = item(
                "message-explicit", List.of("worker-target")
        );
        TaskItem anyWorker = item("message-any", List.of());
        LinkedHashMap<String, TaskItemScoreObservation> observed =
                new LinkedHashMap<>();
        observed.put(
                explicit.messageId(),
                new TaskItemScoreObservation(101L, 1)
        );
        observed.put(
                anyWorker.messageId(),
                new TaskItemScoreObservation(102L, 1)
        );
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(observed);
        when(taskRuntime.loadTaskItems(
                "task-1", List.copyOf(observed.keySet())
        )).thenReturn(Map.of(
                explicit.messageId(), explicit,
                anyWorker.messageId(), anyWorker
        ));
        var selectors = new LinkedHashMap<String, TaskItemWorkerSelector>();
        selectors.put(explicit.messageId(), explicit.workerSelector());
        selectors.put(anyWorker.messageId(), anyWorker.workerSelector());
        when(selection.acquireCandidates(null,
                "group-1", selectors, Set.of(), 6_000L
        )).thenReturn(Map.of(
                explicit.messageId(), worker("worker-target", 201L),
                anyWorker.messageId(), worker("worker-any", 202L)
        ));
        when(dispatcher.dispatch(
                any(), any(), eq(6_000L)
        )).thenReturn(2);

        assertEquals(2, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                selection
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        10))
        ));

        verify(selection).acquireCandidates(null,
                "group-1", selectors, Set.of(), 6_000L
        );
    }

    @Test
    void failedResultWriteStillPrecedesFinalScorePromotion() {
        TaskScoreBandCore taskScores = mock(TaskScoreBandCore.class);
        TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        LinkedHashMap<String, TaskItemScoreObservation> observed =
                new LinkedHashMap<>();
        observed.put("message-budget", new TaskItemScoreObservation(101L, 0));
        observed.put("message-expired", new TaskItemScoreObservation(102L, 1));
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(observed);
        when(taskRuntime.loadTaskItems(
                "task-1", List.of("message-expired")
        )).thenReturn(Map.of(
                "message-expired",
                new TaskItem(
                        "message-expired",
                        "event.demo",
                        0,
                        Map.of(),
                        0,
                        999L,
                        TaskItemWorkerSelector.parse(Map.of())
                )
        ));

        assertEquals(0, dispatch(
                taskScores,
                itemScores,
                taskRuntime,
                mock(TaskAssignmentDispatcher.class),
                mock(WorkerCandidateSelectionPolicy.class)
        ).dispatchTasks(
                List.of(due(
                        "task-1",
                        TaskIdleDisposition.PARK_WHEN_IDLE,
                        10))
        ));

        InOrder order = org.mockito.Mockito.inOrder(taskRuntime, itemScores);
        order.verify(taskRuntime).storeTaskItemFailedResults(
                "task-1", List.of("message-budget", "message-expired")
        );
        order.verify(itemScores).promoteItemOutcomes("task-1", outcomeTargets(List.of("message-budget", "message-expired"), 5, 1_000L));
    }

    private static TaskDispatchPolicy dispatch(
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskAssignmentDispatcher dispatcher,
            WorkerCandidateSelectionPolicy selection
    ) {
        return new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                dispatcher,
                mock(TaskIdleSettlement.class),
                selection,
                5,
                () -> 1_000L
        );
    }

    private static void prepareClaimableItem(
            TaskItemScoreBandCore itemScores,
            TaskRuntime taskRuntime,
            TaskItem item
    ) {
        when(itemScores.acquireItemScoreCandidates("task-1", 100))
                .thenReturn(Map.of(
                        item.messageId(),
                        new TaskItemScoreObservation(333_333_333L, 1)
                ));
        when(taskRuntime.loadTaskItems(
                "task-1", List.of(item.messageId())
        )).thenReturn(Map.of(item.messageId(), item));
    }

    private static ObservedTask due(
            String taskId,
            TaskIdleDisposition idle,
            int priority) {
        return new ObservedTask(
                new TaskDescriptor(taskId, "group-1", idle, Map.of("priority", Integer.toString(priority), "maxRetryTimes", "1")),
                777_777_777L
        );
    }

    private static TaskItem item(
            String messageId,
            List<String> targetWorkerIds
    ) {
        return new TaskItem(
                messageId,
                "event.demo",
                0,
                Map.of(),
                0,
                null,
                TaskItemWorkerSelector.parse(targetWorkerIds.isEmpty()
                        ? Map.of() : Map.of("workerId", targetWorkerIds))
        );
    }

    private static HeldWorkerCandidate worker(
            String workerId,
            long score
    ) {
        return new HeldWorkerCandidate(
                workerId, "group-1", "adapter-1", score
        );
    }

    private static WorkerScoreTransitionResult transitioned(long score) {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score
        );
    }

    private static LinkedHashMap<String, Long> linkedScores(
            Object... pairs
    ) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], (Long) pairs[index + 1]);
        }
        return result;
    }
    private static Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> outcomeTargets(
            List<String> ids, int tag, long time
    ) {
        Map<String, TaskItemScoreBandCore.TaskItemOutcomeTarget> targets = new java.util.LinkedHashMap<>();
        ids.forEach(id -> targets.put(id, new TaskItemScoreBandCore.TaskItemOutcomeTarget(tag, time)));
        return targets;
    }

}
