package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TaskSchedulingBatchSourceTest {

    @Test
    void normalAndInitialShareOneOwnerReloadAndRemainSeparated() {
        TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        List<String> normalIds = List.of("normal", "future", "missing");
        List<String> initialIds = List.of("initial", "wrong-initial");
        List<String> allIds = List.of(
                "normal", "future", "missing", "initial", "wrong-initial"
        );
        when(scores.acquireDispatchWorkTasks(100)).thenReturn(normalIds);
        when(scores.acquireInitialRunningTasks(97)).thenReturn(initialIds);
        when(scores.getScoreStates(allIds)).thenReturn(Map.of(
                "normal", running("normal", 19_900, 0),
                "future", running("future", 20_000, 0),
                "missing", running("missing", 19_800, 0),
                "initial", running(
                        "initial",
                        TaskScoreBandCore.INITIAL_TIME_MILLIS,
                        TaskScoreBandCore.MAX_SUFFIX
                ),
                "wrong-initial", running("wrong-initial", 10_100, 0)
        ));
        when(catalog.loadTaskAllocationDescriptors(allIds)).thenReturn(Map.of(
                "normal", descriptor("normal"),
                "future", descriptor("future"),
                "initial", descriptor("initial"),
                "wrong-initial", descriptor("wrong-initial")
        ));
        TaskSchedulingBatchSource source = new TaskSchedulingBatchSource(
                scores,
                catalog,
                () -> 20_000
        );

        var batch = source.acquireTasks(100, true, true);

        assertEquals(List.of("normal"), ids(batch.normalTasks()));
        assertEquals(List.of("initial"), ids(batch.initialTasks()));
        verify(scores).getScoreStates(allIds);
        verify(catalog).loadTaskAllocationDescriptors(allIds);
    }

    @Test
    void normalPageConsumesTheBudgetBeforeInitialRead() {
        TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        List<String> ids = IntStream.range(0, 100)
                .mapToObj(index -> "task-" + index)
                .toList();
        Map<String, TaskScoreState> states = new LinkedHashMap<>();
        Map<String, TaskDescriptor> descriptors = new LinkedHashMap<>();
        for (String taskId : ids) {
            states.put(taskId, running(taskId, 19_900, 0));
            descriptors.put(taskId, descriptor(taskId));
        }
        when(scores.acquireDispatchWorkTasks(100)).thenReturn(ids);
        when(scores.getScoreStates(ids)).thenReturn(states);
        when(catalog.loadTaskAllocationDescriptors(ids)).thenReturn(descriptors);
        TaskSchedulingBatchSource source = new TaskSchedulingBatchSource(
                scores,
                catalog,
                () -> 20_000
        );

        var batch = source.acquireTasks(100, true, true);

        assertEquals(ids, ids(batch.normalTasks()));
        assertEquals(List.of(), batch.initialTasks());
        verify(scores, never()).acquireInitialRunningTasks(0);
    }

    @Test
    void emptyOwnerPagesDoNotLoadStatesOrDescriptors() {
        TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        when(scores.acquireDispatchWorkTasks(100)).thenReturn(List.of());
        when(scores.acquireInitialRunningTasks(100)).thenReturn(List.of());
        TaskSchedulingBatchSource source = new TaskSchedulingBatchSource(
                scores,
                catalog,
                () -> 20_000
        );

        var batch = source.acquireTasks(100, true, true);

        assertEquals(List.of(), batch.normalTasks());
        assertEquals(List.of(), batch.initialTasks());
        verify(scores, never()).getScoreStates(List.of());
        verify(catalog, never()).loadTaskAllocationDescriptors(List.of());
    }

    private static List<String> ids(List<DueTaskObservation> observations) {
        return observations.stream().map(DueTaskObservation::taskId).toList();
    }

    private static TaskScoreState running(
            String taskId,
            long timeMillis,
            int suffix
    ) {
        return new TaskScoreState(
                taskId,
                1,
                TaskScoreBand.RUNNING_VISIBLE,
                timeMillis,
                suffix
        );
    }

    private static TaskDescriptor descriptor(String taskId) {
        return new TaskDescriptor(
                taskId,
                "group-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "1"
                )
        );
    }
}
