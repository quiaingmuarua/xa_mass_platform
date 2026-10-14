package com.xa.mass.server.project;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.task.directory.TaskDirectory;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskUserId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectTaskQueryServiceTest {
    private static final com.xa.mass.server.task.access.TaskCaller CALLER = com.xa.mass.server.testing.TaskCallers.caller("proof", "admin");
    private final ProjectDirectory projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
            new ProjectDefinition("p", List.of("g")))), List.of());
    private final TaskResourceCatalog tasks = mock(TaskResourceCatalog.class);
    private final TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
    private final TaskDirectory directory = mock(TaskDirectory.class);
    private final ProjectTaskQueryService service = new ProjectTaskQueryService(projects, tasks, scores, directory, org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskItemScoreBandCore.class));

    private TaskDescriptor task(String id, String project, long time, String user) {
        return new TaskDescriptor(id, "g", TaskIdleDisposition.CLOSE_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "3"),
                List.of(), null, new TaskAttribution(project, time, user).stamp(user == null ? Map.of() : Map.of("userId", user)));
    }

    @Test void sortsOnlyTheSelectedBatchAndPreservesUnknownProjectionsWithoutFabricatedTime() {
        when(directory.list("p", null, 2)).thenReturn(new TaskDirectory.Batch(List.of(
                new TaskDirectory.Member("a", 0), new TaskDirectory.Member("b", 0)), true));
        when(tasks.loadTaskAllocationDescriptors(List.of("a", "b"))).thenReturn(Map.of("b", task("b", "p", 20, null)));
        when(scores.getScoreStates(List.of("a", "b"))).thenReturn(Map.of("b", new TaskScoreState("b", -1, TaskScoreBand.TERMINAL, null, null)));
        var result = service.list(CALLER, "p", 2, null);
        assertThat(result.truncated()).isTrue();
        assertThat(result.tasks()).extracting(ProjectTaskQueryService.Entry::taskId).containsExactly("b", "a");
        assertThat(result.tasks().getFirst().createdAtMillis()).isEqualTo(20);
        assertThat(result.tasks().getLast().createdAtMillis()).isNull();
        assertThat(result.tasks().getLast().task()).isNull();
        verify(directory).list("p", null, 2);
        verifyNoMoreInteractions(directory);
    }

    @Test void userBatchDoesNotRefillMissingDescriptorsAndRejectsConflictingAttribution() {
        when(directory.list("p", "1001", 2)).thenReturn(new TaskDirectory.Batch(List.of(
                new TaskDirectory.Member("missing", TaskUserId.score("1001")), new TaskDirectory.Member("mine", TaskUserId.score("1001"))), true));
        when(tasks.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("mine", task("mine", "p", 5, "1001")));
        when(scores.getScoreStates(anyList())).thenReturn(Map.of());
        assertThat(service.list(CALLER, "p", 2, "1001").tasks()).extracting(ProjectTaskQueryService.Entry::taskId).containsExactly("mine");
        verify(directory).list("p", "1001", 2);
        when(tasks.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("mine", task("mine", "p", 5, "1002")));
        assertThatThrownBy(() -> service.list(CALLER, "p", 2, "1001")).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_DATA_UNAVAILABLE));
    }

    @Test void knownTaskReadsDoNotDependOnOrRepairTheDirectoryAndCheckProjectBeforeScores() {
        when(tasks.loadTaskAllocationDescriptors(List.of("known"))).thenReturn(Map.of("known", task("known", "p", 123, "1001")));
        when(scores.getScoreStates(anyList())).thenReturn(Map.of());
        assertThat(service.get(CALLER, "p", "known").createdAtMillis()).isEqualTo(123);
        verifyNoInteractions(directory);
        clearInvocations(scores);
        when(tasks.loadTaskAllocationDescriptors(List.of("foreign"))).thenReturn(Map.of("foreign", task("foreign", "other", 123, null)));
        assertThatThrownBy(() -> service.get(CALLER, "p", "foreign")).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_NOT_FOUND));
        verifyNoInteractions(scores);
        assertThat(ProjectTaskQueryService.toView(new TaskDescriptor("pure", "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "0", "maxRetryTimes", "3"), List.of(), null, Map.of())).projectId()).isNull();
    }
}
