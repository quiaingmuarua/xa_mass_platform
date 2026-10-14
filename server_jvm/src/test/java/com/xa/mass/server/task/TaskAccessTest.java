package com.xa.mass.server.task;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.score.*;
import com.xa.mass.kernel.task.*;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.error.*;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.*;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.*;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskAccessTest {
    final TaskCaller caller = com.xa.mass.server.testing.TaskCallers.caller("1001", "operator", "p");
    final TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
    final TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
    final TaskItemScoreBandCore items = mock(TaskItemScoreBandCore.class);
    final TaskRuntime runtime = mock(TaskRuntime.class);
    final WorkerMatchingCatalog matching = mock(WorkerMatchingCatalog.class);
    final OperationGuard guard = new OperationGuard();
    final TaskDataService data = new TaskDataService(runtime, catalog, new TaskItemMapper(), items,
            new TaskItemOutcomeProperties(Map.of()), matching, scores, guard);

    TaskRuntime.TaskDescriptor task(String id, String owner, String inputOwner) {
        return new TaskRuntime.TaskDescriptor(id, "g", TaskRuntime.TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "50", "maxRetryTimes", "3"), List.of(), null,
                new TaskAttribution("p", 123, owner).stamp(Map.of(TaskCaller.INPUT_OWNER, inputOwner)));
    }
    @Test void sharedReadsAndRawWritesRejectBeforeContentOrMatchingAccess() {
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", task("t", "1002", "raw")));
        for (org.assertj.core.api.ThrowableAssert.ThrowingCallable action : List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
                () -> data.loadTaskItemResults(caller, "t", List.of("m")),
                () -> data.loadTaskItemStates(caller, "t", List.of("m")),
                () -> data.previewTaskResults(caller, "t"),
                () -> data.loadTaskItems(caller, "t", List.of("m"))))
            assertThatThrownBy(action).isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED));
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", task("t", "1001", "messages")));
        var input = new TaskItemRequest("m", "event", Map.of(), 5, null, new WorkerQuery("workerId", "w"));
        assertThatThrownBy(() -> data.appendFiniteTaskItems(caller, "t", List.of(input)))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED));
        verifyNoInteractions(runtime, items, matching);
    }
    @Test void statisticsPreflightTheCompleteProjectAndOwnershipBatch() {
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(new ProjectDefinition("p", List.of("g")))), List.of());
        var queries = new ProjectTaskQueryService(projects, catalog, scores, mock(TaskDirectory.class), items);
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("mine", task("mine", "1001", "raw"), "other", task("other", "1002", "raw")));
        assertThatThrownBy(() -> queries.stats(caller, "p", List.of("mine", "other")))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED));
        verifyNoInteractions(items, scores);
        when(items.observeItemScoreCounts(List.of("mine"))).thenReturn(Map.of("mine", new TaskItemScoreBandCore.TaskItemScoreCounts(2, Map.of(1, 2L))));
        assertThat(queries.stats(caller, "p", List.of("mine", "mine")).get("mine").total()).isEqualTo(2);
        when(items.observeItemScoreCounts(List.of("mine"))).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> queries.stats(caller, "p", List.of("mine")))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_DATA_UNAVAILABLE));
        verifyNoInteractions(runtime);
    }
    @Test void approvalChecksTheCurrentCountAndRetainsIdempotentApprovedBehavior() {
        var commands = mock(TaskLifecycleCommands.class);
        var lifecycle = new TaskLifecycleService(commands, catalog, guard, items, scores);
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", task("t", "1001", "raw")));
        when(scores.getScoreStates(List.of("t"))).thenReturn(Map.of("t", new TaskScoreBandCore.TaskScoreState("t", 1,
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW, 0L, 0)));
        when(items.observeItemScoreCounts(List.of("t"))).thenReturn(Map.of("t", new TaskItemScoreBandCore.TaskItemScoreCounts(3, Map.of(1, 3L))));
        assertThatThrownBy(() -> lifecycle.approve(caller, "t", 0)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> lifecycle.approve(caller, "t", 2)).isInstanceOf(ServerException.class);
        verifyNoInteractions(commands);
        when(commands.approveTask("t")).thenReturn(new TaskLifecycleCommands.TaskApprovalResult(TaskLifecycleCommands.TaskApprovalStatus.APPROVED));
        assertThat(lifecycle.approve(caller, "t", 3).status().name()).isEqualTo("APPLIED");
        var order = inOrder(items, commands);
        order.verify(items, times(2)).observeItemScoreCounts(List.of("t"));
        order.verify(commands).approveTask("t");
        clearInvocations(items, commands);
        when(scores.getScoreStates(List.of("t"))).thenReturn(Map.of("t", new TaskScoreBandCore.TaskScoreState("t", 100,
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE, 100L, 0)));
        when(commands.approveTask("t")).thenReturn(new TaskLifecycleCommands.TaskApprovalResult(TaskLifecycleCommands.TaskApprovalStatus.ALREADY_APPROVED));
        assertThat(lifecycle.approve(caller, "t", 99).status().name()).isEqualTo("UNCHANGED");
        verifyNoInteractions(items);
        when(scores.getScoreStates(List.of("t"))).thenReturn(Map.of("t", new TaskScoreBandCore.TaskScoreState("t", -1,
                TaskScoreBandCore.TaskScoreBand.TERMINAL, null, null)));
        when(commands.approveTask("t")).thenReturn(new TaskLifecycleCommands.TaskApprovalResult(TaskLifecycleCommands.TaskApprovalStatus.CONFLICT));
        assertThatThrownBy(() -> lifecycle.approve(caller, "t", 3)).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_STATE_CONFLICT));
        verifyNoInteractions(items);
    }
}
