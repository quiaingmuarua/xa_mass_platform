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
            new TaskItemOutcomeProperties(Map.of()), matching, scores, guard, com.xa.mass.server.testsupport.TaskRetentionFixtures.results(runtime, catalog, scores, guard));

    TaskAccessTest() { com.xa.mass.server.testsupport.TaskRetentionFixtures.openData(runtime); }

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

    @Test void archivedPointResultsAreExplicitlyRejectedWhileItemStatesRemainReadable() {
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", task("t", "1001", "raw")));
        when(runtime.loadTaskDataStates(anyList())).thenReturn(Map.of("t", new TaskRuntime.TaskDataState(TaskRuntime.TaskDataPhase.RELEASED, 1000L)));
        assertThatThrownBy(() -> data.loadTaskItemResults(caller, "t", List.of("one")))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_RESULTS_ARCHIVED));
        verify(runtime, never()).loadTaskItemResults(anyString(), anyList());
        when(items.getItemScoreStates("t", List.of("one"))).thenReturn(Map.of("one",
                new TaskItemScoreBandCore.TaskItemScoreState(1, TaskItemScoreBandCore.TaskItemScoreBand.ACTIVE, 1, 1000, 1)));
        assertThat(data.loadTaskItemStates(caller, "t", List.of("one")).get("one").tag()).isEqualTo(1);
    }
    @Test void consumptionRequiresOwnershipRpcTypeAndTheBoundInputOwnerBeforeReadingContent() {
        var other = task("t", "1002", "raw");
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", other));
        assertThatThrownBy(() -> data.takeTaskItemResults(caller, "raw", "t", List.of("m")))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED));
        for (String input : List.of("raw", "sms")) {
            when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", task("t", "1001", input)));
            assertThatThrownBy(() -> data.takeTaskItemResults(caller, "raw", "t", List.of("m")))
                    .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED));
        }
        verifyNoInteractions(runtime, items, matching);
        var base = task("t", "1001", "raw");
        var rpc = new TaskRuntime.TaskDescriptor("t", "g", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, base.config(), List.of(), null, base.metadata());
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("t", rpc));
        when(runtime.takeTaskItemResults("t", List.of("m"))).thenReturn(Map.of("m", TaskRuntime.TaskItemResult.succeeded("taken")));
        assertThat(data.takeTaskItemResults(caller, "raw", "t", List.of("m", "m")).get("m").opaqueResultPayload()).isEqualTo("taken");
        verify(runtime).takeTaskItemResults("t", List.of("m"));
    }
    @Test void statisticsPreflightTheCompleteProjectAndOwnershipBatch() {
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(new ProjectDefinition("p", List.of("g")))), List.of());
        var queries = new ProjectTaskQueryService(projects, catalog, scores, mock(TaskDirectory.class), items, runtime, com.xa.mass.server.testsupport.TaskRetentionFixtures.properties());
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of("mine", task("mine", "1001", "raw"), "other", task("other", "1002", "raw")));
        assertThatThrownBy(() -> queries.stats(caller, "p", List.of("mine", "other")))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED));
        verifyNoInteractions(items, scores);
        when(items.observeItemScoreCounts(List.of("mine"))).thenReturn(Map.of("mine", new TaskItemScoreBandCore.TaskItemScoreCounts(2, Map.of(1, 2L))));
        assertThat(queries.stats(caller, "p", List.of("mine", "mine")).get("mine").total()).isEqualTo(2);
        when(items.observeItemScoreCounts(List.of("mine"))).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> queries.stats(caller, "p", List.of("mine")))
                .isInstanceOfSatisfying(ServerException.class, error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_DATA_UNAVAILABLE));
        verify(runtime, never()).loadTaskItems(anyString(), anyList());
        verify(runtime, never()).loadTaskItemResults(anyString(), anyList());
    }
    @Test void approvalChecksTheCurrentCountAndRetainsIdempotentApprovedBehavior() {
        var commands = mock(TaskLifecycleCommands.class);
        var lifecycle = new TaskLifecycleService(commands, catalog, guard, items, scores, runtime);
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
        when(scores.getScoreStates(List.of("t"))).thenReturn(Map.of("t", new TaskScoreBandCore.TaskScoreState("t", -2_000_000_000_000L,
                TaskScoreBandCore.TaskScoreBand.TERMINAL, null, null)));
        when(commands.approveTask("t")).thenReturn(new TaskLifecycleCommands.TaskApprovalResult(TaskLifecycleCommands.TaskApprovalStatus.CONFLICT));
        assertThatThrownBy(() -> lifecycle.approve(caller, "t", 3)).isInstanceOfSatisfying(ServerException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ServerErrorCode.TASK_STATE_CONFLICT));
        verifyNoInteractions(items);
    }
}
