package com.xa.mass.server.project;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskResourceCatalog.ProjectTaskEntry;
import com.xa.mass.kernel.task.TaskResourceCatalog.ProjectTaskPage;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectTaskQueryServiceTest {
    private final ProjectDirectory projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
            new ProjectAssemblyProperties.Project("p", List.of("g")))));
    private final TaskResourceCatalog tasks = mock(TaskResourceCatalog.class);
    private final TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
    private final ProjectTaskQueryService service = new ProjectTaskQueryService(projects, tasks, scores);

    @Test void indexOrderTimesAndMissingProjectionsArePreservedWithoutResultReads() {
        when(tasks.listProjectTasks("p", 2)).thenReturn(new ProjectTaskPage(
                List.of(new ProjectTaskEntry("b", 20), new ProjectTaskEntry("a", 10)), true));
        when(tasks.loadTaskAllocationDescriptors(List.of("b", "a"))).thenReturn(Map.of("b",
                new TaskDescriptor("b", "p", "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                        Map.of("priority", "0", "maxRetryTimes", "3"), List.of())));
        when(scores.getScoreStates(List.of("b", "a"))).thenReturn(Map.of("b",
                new TaskScoreState("b", -1, TaskScoreBand.TERMINAL, null, null)));
        var response = service.list("p", 2);
        assertThat(response.truncated()).isTrue();
        assertThat(response.tasks()).extracting(ProjectTaskQueryService.Entry::taskId).containsExactly("b", "a");
        assertThat(response.tasks().getFirst().scoreBand()).isEqualTo("terminal");
        assertThat(response.tasks().getLast().task()).isNull();
        assertThat(response.tasks().getLast().scoreBand()).isNull();
        verify(tasks).listProjectTasks("p", 2);
        verify(tasks).loadTaskAllocationDescriptors(List.of("b", "a"));
        verify(scores).getScoreStates(List.of("b", "a"));
        verifyNoMoreInteractions(tasks, scores);
    }

    @Test void configurationAdmissionPrecedesReadsAndOwnerFailureIsUnavailable() {
        assertThatThrownBy(() -> service.list("missing", 100)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.list("p", 1001)).isInstanceOf(ServerException.class);
        verifyNoInteractions(tasks, scores);
        when(tasks.listProjectTasks("p", 100)).thenThrow(new IllegalStateException("corrupt"));
        assertThatThrownBy(() -> service.list("p", 100)).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_DATA_UNAVAILABLE));
    }
}
