package com.xa.mass.server.task;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskDataPreviewTest {
    final TaskRuntime runtime = mock(TaskRuntime.class);
    final TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
    final TaskItemScoreBandCore scores = mock(TaskItemScoreBandCore.class);
    final TaskDataService service = new TaskDataService(runtime, catalog, new TaskItemMapper(), scores,
            new TaskItemOutcomeProperties(Map.of()), mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class));

    @Test void oneScanAndOneItemReadClampAnOversizedHashPageAndKeepFailures() {
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task",
                new TaskRuntime.TaskDescriptor("task", "messages", "g", TaskRuntime.TaskIdleDisposition.CLOSE_WHEN_IDLE,
                        Map.of("priority", "0", "maxRetryTimes", "3"), List.of(), null, Map.of())));
        var results = new LinkedHashMap<String, TaskRuntime.TaskItemResult>();
        for (int i = 0; i < 101; i++) results.put("m" + i, i == 0 ? TaskRuntime.TaskItemResult.failed() : TaskRuntime.TaskItemResult.succeeded("{}"));
        when(runtime.scanTaskItemResults("task", "0", 100)).thenReturn(new TaskRuntime.TaskItemResultPage("0", results));
        when(runtime.loadTaskItems(eq("task"), anyList())).thenReturn(Map.of());
        var preview = service.previewTaskResults("task");
        assertThat(preview.truncated()).isTrue(); assertThat(preview.results()).hasSize(100);
        assertThat(preview.results().getFirst().result().status().wireValue()).isEqualTo("failed");
        verify(runtime).scanTaskItemResults("task", "0", 100);
        verify(runtime).loadTaskItems(eq("task"), argThat(ids -> ids.size() == 100));
        verifyNoMoreInteractions(runtime); verifyNoInteractions(scores);
        when(runtime.scanTaskItemResults("task", "0", 100)).thenReturn(new TaskRuntime.TaskItemResultPage("42", Map.of()));
        var sparse = service.previewTaskResults("task");
        assertThat(sparse.results()).isEmpty(); assertThat(sparse.truncated()).isTrue();
    }

    @Test void countFailureIsUnavailableAndNeverZeroOrAResultFallback() {
        when(scores.observeItemScoreCounts(List.of("task"))).thenThrow(new IllegalStateException("connection"));
        assertThatThrownBy(() -> service.observeItemScoreCounts(List.of("task"))).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_DATA_UNAVAILABLE));
        verifyNoInteractions(runtime);
    }
}
