package com.xa.mass.kernel.pacer;

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
    void runningSourceReadsOwnersOnceAndPreservesScoreOrder() {
        TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        List<String> ids = List.of(
                "first",
                "future",
                "wrong-band",
                "nonzero-suffix",
                "missing-descriptor",
                "last"
        );
        when(scores.acquireDispatchWorkTasks(100)).thenReturn(ids);
        when(scores.getScoreStates(ids)).thenReturn(Map.of(
                "first", running("first", 9_900, 0),
                "future", running("future", 10_000, 0),
                "wrong-band", admission("wrong-band", 9_900, 0),
                "nonzero-suffix", running("nonzero-suffix", 9_900, 1),
                "missing-descriptor", running(
                        "missing-descriptor", 9_900, 0
                ),
                "last", running("last", 9_800, 0)
        ));
        when(catalog.loadTaskAllocationDescriptors(ids)).thenReturn(Map.of(
                "first", descriptor("first"),
                "future", descriptor("future"),
                "wrong-band", descriptor("wrong-band"),
                "nonzero-suffix", descriptor("nonzero-suffix"),
                "last", descriptor("last")
        ));
        TaskSchedulingBatchSource source = new TaskSchedulingBatchSource(
                scores,
                catalog,
                () -> 10_000
        );

        assertEquals(
                List.of("first", "last"),
                source.acquireRunningTasks(100).stream()
                        .map(DueTaskObservation::taskId)
                        .toList()
        );
        verify(scores).getScoreStates(ids);
        verify(catalog).loadTaskAllocationDescriptors(ids);
    }

    @Test
    void admissionAcceptsPrioritySuffixAndEmptyPageStopsEarly() {
        TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        when(scores.acquireBandTaskCandidates(
                TaskScoreBand.ADMISSION_VISIBLE,
                10_000,
                100
        )).thenReturn(List.of("task-1"));
        when(scores.getScoreStates(List.of("task-1"))).thenReturn(Map.of(
                "task-1", admission("task-1", 9_900, 90)
        ));
        when(catalog.loadTaskAllocationDescriptors(List.of("task-1")))
                .thenReturn(Map.of("task-1", descriptor("task-1")));
        TaskSchedulingBatchSource source = new TaskSchedulingBatchSource(
                scores,
                catalog,
                () -> 10_000
        );

        assertEquals(
                List.of("task-1"),
                source.acquireAdmissionTasks(100).stream()
                        .map(DueTaskObservation::taskId)
                        .toList()
        );

        TaskScoreBandCore emptyScores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog unusedCatalog = mock(TaskResourceCatalog.class);
        when(emptyScores.acquireDispatchWorkTasks(100)).thenReturn(List.of());
        TaskSchedulingBatchSource empty = new TaskSchedulingBatchSource(
                emptyScores,
                unusedCatalog,
                () -> 10_000
        );
        assertEquals(List.of(), empty.acquireRunningTasks(100));
        verify(emptyScores, never()).getScoreStates(List.of());
        verify(unusedCatalog, never()).loadTaskAllocationDescriptors(List.of());
    }

    @Test
    void completeRunningPageProducesOneObservationPerSupportedTask() {
        TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        List<String> ids = IntStream.range(0, 100)
                .mapToObj(index -> "task-" + index)
                .toList();
        Map<String, TaskScoreState> states = new LinkedHashMap<>();
        Map<String, TaskDescriptor> descriptors = new LinkedHashMap<>();
        for (String taskId : ids) {
            states.put(taskId, running(taskId, 9_900, 0));
            descriptors.put(taskId, descriptor(taskId));
        }
        when(scores.acquireDispatchWorkTasks(100)).thenReturn(ids);
        when(scores.getScoreStates(ids)).thenReturn(states);
        when(catalog.loadTaskAllocationDescriptors(ids)).thenReturn(descriptors);
        TaskSchedulingBatchSource source = new TaskSchedulingBatchSource(
                scores,
                catalog,
                () -> 10_000
        );

        List<DueTaskObservation> observations = source.acquireRunningTasks(100);

        assertEquals(ids, observations.stream()
                .map(DueTaskObservation::taskId)
                .toList());
        assertEquals(100, observations.size());
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

    private static TaskScoreState admission(
            String taskId,
            long timeMillis,
            int suffix
    ) {
        return new TaskScoreState(
                taskId,
                1,
                TaskScoreBand.ADMISSION_VISIBLE,
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
