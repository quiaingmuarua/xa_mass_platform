package com.xa.mass.server.task;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.*;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskInputWorkflowTest {
    private static final com.xa.mass.server.task.access.TaskCaller CALLER = com.xa.mass.server.testing.TaskCallers.caller("proof", "admin");
    final TaskRuntime runtime = mock(TaskRuntime.class);
    final TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
    final TaskScoreBandCore scores = mock(TaskScoreBandCore.class);
    final TaskItemScoreBandCore itemScores = mock(TaskItemScoreBandCore.class);
    final WorkerMatchingCatalog matching = mock(WorkerMatchingCatalog.class);
    final OperationGuard guard = new OperationGuard();
    final TaskDataService data = new TaskDataService(runtime, catalog, new TaskItemMapper(Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)),
            itemScores, new TaskItemOutcomeProperties(Map.of()), matching, scores, guard);
    final Map<String, TaskRuntime.TaskDescriptor> descriptors = new HashMap<>();
    final Map<String, TaskScoreBandCore.TaskScoreState> states = new HashMap<>();
    final com.xa.mass.server.task.directory.TaskDirectory directory = mock(com.xa.mass.server.task.directory.TaskDirectory.class);

    TaskInputWorkflowTest() {
        when(catalog.loadTaskAllocationDescriptors(anyList())).thenAnswer(c -> descriptors);
        when(scores.getScoreStates(anyList())).thenAnswer(c -> states);
        when(matching.normalizeQuery(anyString(), any())).thenAnswer(c -> c.getArgument(1));
        when(matching.normalizeRefill(anyString(), anyList())).thenReturn(List.of());
    }

    TaskCreationService creator() {
        var workers = mock(WorkerResourceCatalog.class);
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Collections.singletonMap("g", mock(com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor.class)));
        return new TaskCreationService(workers, matching, runtime, mock(ProjectDirectory.class), catalog, scores, guard, directory);
    }
    TaskRuntime.TaskDescriptor descriptor(String id) {
        return new TaskRuntime.TaskDescriptor(id, "g", TaskRuntime.TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of("priority", "50", "maxRetryTimes", "3"), List.of(), null, Map.of("server.inputOwner", "raw"));
    }
    void pending(String id) {
        descriptors.put(id, descriptor(id));
        states.put(id, new TaskScoreBandCore.TaskScoreState(id, 1, TaskScoreBandCore.TaskScoreBand.PRE_REVIEW, 0L, 0));
    }
    TaskItemRequest input(String id) { return new TaskItemRequest(id, "event", Map.of("number", "+86123"), 5, null, new WorkerQuery("worker.any", Map.of())); }

    @Test void creationUsesExistingTruthAcrossServiceReconstructionAndRejectsConflicts() {
        when(runtime.createTask(any())).thenAnswer(c -> {
            TaskRuntime.TaskDescriptor value = c.getArgument(0);
            descriptors.put(value.taskId(), value);
            states.put(value.taskId(), new TaskScoreBandCore.TaskScoreState(value.taskId(), 1, TaskScoreBandCore.TaskScoreBand.PRE_REVIEW, 0L, 0));
            return new TaskRuntime.TaskCreationResult(TaskRuntime.TaskCreationStatus.CREATED);
        });
        var request = new TaskCreateRequest("request", "p", "g", 50, 3, List.of(), "first", Map.of());
        String id = creator().createForRequest(CALLER, "raw", request, "a".repeat(64)).taskId();
        var later = new TaskCreateRequest("request", "p", "g", 50, 3, List.of(), "generated-later", Map.of("saltDate", "later"));
        assertThat(creator().createForRequest(CALLER, "raw", later, "a".repeat(64)).taskId()).isEqualTo(id);
        assertThat(descriptors.get(id).name()).isEqualTo("first");
        assertThatThrownBy(() -> creator().createForRequest(CALLER, "raw", request, "b".repeat(64))).hasMessageContaining("different content");
        states.clear();
        assertThatThrownBy(() -> creator().createForRequest(CALLER, "raw", request, "a".repeat(64)))
                .isInstanceOfSatisfying(TaskCreationUnconfirmedException.class, e -> assertThat(e.taskId()).isEqualTo(id));
        verify(runtime, times(1)).createTask(any());
    }

    @Test void lostCreationResponseRetainsTheSameIdentityAcrossExplicitRequests() {
        when(runtime.createTask(any())).thenThrow(new IllegalStateException("lost"));
        var request = new TaskCreateRequest("request", "p", "g", 50, 3, List.of(), null, Map.of());
        var first = catchThrowableOfType(TaskCreationUnconfirmedException.class,
                () -> creator().createForRequest(CALLER, "raw", request, "a".repeat(64)));
        var second = catchThrowableOfType(TaskCreationUnconfirmedException.class,
                () -> creator().createForRequest(CALLER, "raw", request, "a".repeat(64)));
        assertThat(second.taskId()).isEqualTo(first.taskId());
    }

    @Test void rawCreationCorrelatesByCallerAndRequestWhileServerOwnsCreatorAndInputAuthority() {
        when(runtime.createTask(any())).thenAnswer(call -> {
            TaskRuntime.TaskDescriptor task = call.getArgument(0);
            descriptors.put(task.taskId(), task);
            states.put(task.taskId(), new TaskScoreBandCore.TaskScoreState(task.taskId(), 1,
                    TaskScoreBandCore.TaskScoreBand.PRE_REVIEW, 0L, 0));
            return new TaskRuntime.TaskCreationResult(TaskRuntime.TaskCreationStatus.CREATED);
        });
        var alice = com.xa.mass.server.testing.TaskCallers.caller("alice", "operator", "p");
        var bobby = com.xa.mass.server.testing.TaskCallers.caller("bobby", "operator", "p");
        var request = new TaskCreateRequest("same-request", "p", "g", 50, 3, List.of(), "name", Map.of("label", "one"));
        String first = creator().create(alice, request).taskId();
        assertThat(creator().create(com.xa.mass.server.testing.TaskCallers.caller("alice", "admin"), request).taskId()).isEqualTo(first);
        assertThat(creator().create(bobby, request).taskId()).isNotEqualTo(first);
        assertThat(descriptors.get(first).metadata()).containsEntry("userId", "alice").containsEntry("server.inputOwner", "raw").doesNotContainKey("role");
        assertThatThrownBy(() -> creator().create(alice,
                new TaskCreateRequest("same-request", "p", "g", 50, 3, List.of(), "different", Map.of("label", "one"))))
                .hasMessageContaining("different content");
        for (var metadata : List.of(Map.of("userId", "bobby"), Map.of("server.inputOwner", "messages")))
            assertThatThrownBy(() -> creator().create(alice,
                    new TaskCreateRequest("forged", "p", "g", 50, 3, List.of(), "name", metadata)))
                    .isInstanceOf(com.xa.mass.server.error.ServerException.class);
        verify(runtime, times(2)).createTask(any());
    }

    @Test void lostDirectoryCommitCanBeExplicitlyConfirmedWithoutRecreatingOrRetiming() {
        when(runtime.createTask(any())).thenAnswer(call -> {
            TaskRuntime.TaskDescriptor task = call.getArgument(0);
            descriptors.put(task.taskId(), task);
            states.put(task.taskId(), new TaskScoreBandCore.TaskScoreState(task.taskId(), 1, TaskScoreBandCore.TaskScoreBand.PRE_REVIEW, 0L, 0));
            return new TaskRuntime.TaskCreationResult(TaskRuntime.TaskCreationStatus.CREATED);
        });
        when(directory.ensure(org.mockito.ArgumentMatchers.eq(CALLER), anyString())).thenThrow(new IllegalStateException("lost directory reply")).thenReturn(ActionOutcome.unchanged());
        var request = new TaskCreateRequest("request", "p", "g", 50, 3, List.of(), null, Map.of());
        var failure = catchThrowableOfType(TaskCreationUnconfirmedException.class,
                () -> creator().createForRequest(CALLER, "raw", request, "a".repeat(64)));
        var original = descriptors.get(failure.taskId());
        assertThat(creator().createForRequest(CALLER, "raw", request, "a".repeat(64)).taskId()).isEqualTo(failure.taskId());
        assertThat(descriptors.get(failure.taskId())).isSameAs(original);
        verify(runtime, times(1)).createTask(any());
        verify(directory, times(2)).ensure(CALLER, failure.taskId());
    }

    @Test void repeatedInputsPreserveStoredTimesAndExplicitReimportCompletesOnlyMissingScores() {
        pending("t");
        var item = new TaskRuntime.TaskItem("one", "event", 4L, Map.of("number", "+86123"), 5, 900000L, new WorkerQuery("worker.any", Map.of()));
        when(runtime.loadTaskItems(eq("t"), anyList())).thenReturn(Map.of("one", item));
        when(itemScores.getItemScoreStates(eq("t"), anyList())).thenReturn(Map.of("one",
                new TaskItemScoreBandCore.TaskItemScoreState(1, TaskItemScoreBandCore.TaskItemScoreBand.ACTIVE, 1, 0, 3)));
        assertThat(data.importFiniteTaskItems(CALLER, "raw", "t", List.of(input("one"))).get("one").status()).isEqualTo(ActionOutcome.Status.UNCHANGED);
        verify(runtime, never()).appendItems(anyString(), anyList());
        when(itemScores.getItemScoreStates(eq("t"), anyList())).thenReturn(Map.of());
        when(runtime.appendItems(eq("t"), anyList())).thenReturn(Map.of("one", new TaskRuntime.TaskItemAppendResult(TaskRuntime.TaskItemAppendStatus.APPENDED)));
        assertThat(data.importFiniteTaskItems(CALLER, "raw", "t", List.of(input("one"))).get("one").status()).isEqualTo(ActionOutcome.Status.APPLIED);
        verify(runtime).appendItems("t", List.of(item));
        assertThatThrownBy(() -> data.importFiniteTaskItems(CALLER, "raw", "t", List.of(new TaskItemRequest("one", "event", Map.of("number", "+86999"), 5, null, new WorkerQuery("worker.any", Map.of())))))
                .hasMessageContaining("differs");
    }

    @Test void importRejectsAnOrphanScoreAndNonReviewTaskBeforeWriting() {
        pending("t");
        when(runtime.loadTaskItems(eq("t"), anyList())).thenReturn(Map.of());
        when(itemScores.getItemScoreStates(eq("t"), anyList())).thenReturn(Map.of("one",
                new TaskItemScoreBandCore.TaskItemScoreState(1, TaskItemScoreBandCore.TaskItemScoreBand.ACTIVE, 1, 0, 3)));
        assertThatThrownBy(() -> data.importFiniteTaskItems(CALLER, "raw", "t", List.of(input("one")))).isInstanceOf(com.xa.mass.server.error.ServerException.class);
        states.put("t", new TaskScoreBandCore.TaskScoreState("t", -1, TaskScoreBandCore.TaskScoreBand.TERMINAL, null, null));
        assertThatThrownBy(() -> data.importFiniteTaskItems(CALLER, "raw", "t", List.of(input("one")))).hasMessageContaining("await review");
        verify(runtime, never()).appendItems(anyString(), anyList());
    }

    @Test void importComparesJsonValuesAcrossStorageNumberRepresentations() {
        pending("t");
        var query = new WorkerQuery("worker.any", Map.of());
        var item = new TaskRuntime.TaskItem("one", "event", 4L, Map.of("bounds", List.of(0, 1000)), 5, 900000L, query);
        when(runtime.loadTaskItems(eq("t"), anyList())).thenReturn(Map.of("one", item));
        when(itemScores.getItemScoreStates(eq("t"), anyList())).thenReturn(Map.of("one",
                new TaskItemScoreBandCore.TaskItemScoreState(1, TaskItemScoreBandCore.TaskItemScoreBand.ACTIVE, 1, 0, 3)));
        var input = new TaskItemRequest("one", "event", Map.of("bounds", List.of(0L, 1000L)), 5, null, query);
        assertThat(data.importFiniteTaskItems(CALLER, "raw", "t", List.of(input)).get("one").status()).isEqualTo(ActionOutcome.Status.UNCHANGED);
        verify(runtime, never()).appendItems(anyString(), anyList());
    }

    @Test void importAdmissionBlocksEveryOtherMutationButAllowsItsOwnBatches() throws Exception {
        pending("t");
        var commands = mock(TaskLifecycleCommands.class);
        var lifecycle = new TaskLifecycleService(commands, catalog, guard, org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskItemScoreBandCore.class), org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskScoreBandCore.class));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var operation = executor.submit(() -> guard.taskMutation("t", () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return guard.taskMutation("t", () -> 1);
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> lifecycle.approve(CALLER, "t", 1L)).hasMessageContaining("in progress");
                assertThatThrownBy(() -> lifecycle.close(CALLER, "t")).hasMessageContaining("in progress");
                assertThatThrownBy(() -> data.appendFiniteTaskItems(CALLER, "t", List.of(input("new")))).hasMessageContaining("in progress");
                verifyNoInteractions(commands);
            } finally { release.countDown(); }
            assertThat(operation.get()).isEqualTo(1);
        }
    }
}
