package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskDispatchProgressTest {
    @Test
    void returnedCapacityReachesWaitingTasksEvenWhenReleaseAndEmptyRoundsRepeat() {
        var rig = new Rig();
        var tasks = List.of(task("background", "shared"), task("online", "shared"), task("third", "shared"));
        for (int round = 0; round < 9; round++) {
            if (round % 3 == 0) rig.availableGroups.add("shared");
            rig.policy.dispatchTasks(tasks);
        }
        assertEquals(List.of("background", "online", "third"), rig.published);
        verify(rig.selection, times(9)).prepareQueries(Map.of(
                "background", "shared", "online", "shared", "third", "shared"));
        for (var task : tasks) verify(rig.itemScores, times(9)).acquireItemScoreCandidates(task.taskId(), 100);
    }

    @Test
    void progressInAnotherGroupDoesNotResetTheSharedGroupsTurn() {
        var rig = new Rig();
        var tasks = List.of(task("background", "shared"), task("online", "shared"), task("independent", "other"));
        for (int round = 0; round < 4; round++) {
            rig.availableGroups.addAll(List.of("shared", "other"));
            // Task score observation order can change independently of successful dispatch.
            rig.policy.dispatchTasks(round % 2 == 0 ? tasks : List.of(tasks.get(2), tasks.get(0), tasks.get(1)));
        }
        assertEquals(List.of("background", "online", "background", "online"),
                rig.published.stream().filter(id -> !id.equals("independent")).toList());
        assertEquals(4, rig.published.stream().filter("independent"::equals).count());
    }

    @Test
    void removedTasksAreNotReadAndNewTasksCanUseCapacity() {
        var rig = new Rig();
        var first = task("first", "shared");
        var second = task("second", "shared");
        rig.availableGroups.add("shared");
        rig.policy.dispatchTasks(List.of(first));
        rig.availableGroups.add("shared");
        rig.policy.dispatchTasks(List.of(first, second));
        rig.availableGroups.add("shared");
        rig.policy.dispatchTasks(List.of(second));
        assertEquals(List.of("first", "second", "second"), rig.published);
        verify(rig.itemScores, times(2)).acquireItemScoreCandidates("first", 100);
    }

    @Test
    void unsuccessfulAssignmentDoesNotCountAsServingTheWaitingTask() {
        var rig = new Rig();
        var tasks = List.of(task("first", "shared"), task("waiting", "shared"));
        rig.availableGroups.add("shared");
        rig.policy.dispatchTasks(tasks);
        rig.availableGroups.add("shared");
        rig.rejectPublication = true;
        assertEquals(0, rig.policy.dispatchTasks(tasks));
        rig.rejectPublication = false;
        rig.availableGroups.add("shared");
        rig.policy.dispatchTasks(tasks);
        assertEquals(List.of("first", "waiting"), rig.published);
    }

    private static ObservedTask task(String id, String group) {
        return new ObservedTask(new TaskDescriptor(id, group, TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority", "50", "maxRetryTimes", "3")), 101L);
    }

    private static final class Rig {
        final TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
        final WorkerCandidateSelectionPolicy selection = mock(WorkerCandidateSelectionPolicy.class);
        final Set<String> availableGroups = new HashSet<>();
        final List<String> published = new ArrayList<>();
        final TaskDispatchPolicy policy;
        boolean rejectPublication;

        Rig() {
            var runtime = mock(TaskRuntime.class);
            var dispatcher = mock(TaskAssignmentDispatcher.class);
            when(itemScores.acquireItemScoreCandidates(anyString(), eq(100))).thenAnswer(call -> Map.of(
                    "item-" + call.getArgument(0), new TaskItemScoreBandCore.TaskItemScoreObservation(201L, 4)));
            when(runtime.loadTaskItems(anyString(), anyList())).thenAnswer(call -> {
                List<String> ids = call.getArgument(1);
                var result = new LinkedHashMap<String, TaskItem>();
                ids.forEach(id -> result.put(id, new TaskItem(id, "event", 0, Map.of(), 0, null,
                        TaskItemWorkerSelector.parse(Map.of()))));
                return result;
            });
            when(selection.acquireCandidates(any(), anyString(), anyMap(), anySet(), anyLong())).thenAnswer(call -> {
                String group = call.getArgument(1);
                Map<String, TaskItemWorkerSelector> selectors = call.getArgument(2);
                Set<String> roundWorkers = call.getArgument(3);
                String worker = "worker-" + group;
                if (!availableGroups.contains(group) || !roundWorkers.add(worker)) return Map.of();
                return Map.of(selectors.keySet().iterator().next(), new HeldWorkerCandidate(worker, group, "adapter", 301L));
            });
            when(dispatcher.dispatch(any(), anyList(), anyLong())).thenAnswer(call -> {
                ObservedTask task = call.getArgument(0);
                List<?> assignments = call.getArgument(1);
                if (assignments.isEmpty() || rejectPublication) return 0;
                availableGroups.remove(task.descriptor().workerGroupId());
                published.add(task.taskId());
                return assignments.size();
            });
            policy = new TaskDispatchPolicy(mock(TaskScoreBandCore.class), itemScores, runtime, dispatcher,
                    mock(TaskIdleSettlement.class), selection, 5, () -> 1_000L);
        }
    }
}
