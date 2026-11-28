package com.xa.mass.server.task.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalStatus;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskDirectory;
import com.xa.mass.server.task.directory.TaskRpcChannelRegistry;
import com.xa.mass.server.testing.TaskCallers;
import com.xa.mass.server.testsupport.TaskRetentionFixtures;
import com.xa.mass.server.user.UserDirectory;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskRpcPreparationServiceTest {
    private static final TaskCaller ADMIN = TaskCallers.caller("proof", "admin");
    private static final TaskCaller OPERATOR = TaskCallers.caller("alice", "operator", "test-project");
    private static final TaskCaller SERVICE = TaskCaller.forProjectService(new ProjectDefinition("sms", List.of("phone-tools"), null));

    private WorkerMatchingCatalog matching;
    private WorkerResourceCatalog workerCatalog;
    private TaskResourceCatalog taskCatalog;
    private TaskRuntime taskRuntime;
    private TaskLifecycleCommands taskLifecycle;
    private TaskRpcChannelRegistry channels;
    private TaskDirectory taskDirectory;
    private TaskScoreBandCore taskScores;
    private UserDirectory users;
    private Map<String, TaskDescriptor> stored;
    private Map<String, TaskScoreState> scoreStates;
    private TaskRpcPreparationService service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        taskRuntime = mock(TaskRuntime.class);
        taskLifecycle = mock(TaskLifecycleCommands.class);
        matching = mock(WorkerMatchingCatalog.class);
        channels = TaskRetentionFixtures.rpcChannels();
        taskDirectory = mock(TaskDirectory.class);
        taskScores = mock(TaskScoreBandCore.class);
        users = TaskCallers.directory(
                new UserDirectory.User("proof", "Proof", "admin", List.of()),
                new UserDirectory.User("alice", "Alice", "operator", List.of("test-project")),
                new UserDirectory.User("bobby", "Bobby", "operator", List.of("other-project"))
        );
        stored = new LinkedHashMap<>();
        scoreStates = new LinkedHashMap<>();
        when(taskScores.getScoreStates(anyList())).thenAnswer(call -> {
            var result = new LinkedHashMap<String, TaskScoreState>();
            for (String id : call.<List<String>>getArgument(0)) {
                if (scoreStates.containsKey(id)) result.put(id, scoreStates.get(id));
            }
            return result;
        });
        when(matching.normalizeRefill(anyString(), anyList())).thenAnswer(call -> call.getArgument(1));
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenAnswer(call -> {
            var result = new LinkedHashMap<String, TaskDescriptor>();
            for (String id : call.<List<String>>getArgument(0)) {
                if (stored.containsKey(id)) result.put(id, stored.get(id));
            }
            return result;
        });
        when(taskRuntime.createTask(any())).thenAnswer(call -> {
            TaskDescriptor descriptor = call.getArgument(0);
            if (stored.containsKey(descriptor.taskId())) {
                return new TaskCreationResult(TaskCreationStatus.CONFLICT);
            }
            stored.put(descriptor.taskId(), descriptor);
            return new TaskCreationResult(TaskCreationStatus.CREATED);
        });
        when(taskLifecycle.approveTask(anyString())).thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        when(workerCatalog.getWorkerGroupDescriptors(List.of("phone-tools")))
                .thenReturn(Map.of("phone-tools", new WorkerGroupDescriptor("phone-tools", Map.of(), Set.of("phone.lookup"))));
        service = newService(defaultProperties(Map.of()), projects());
    }

    private TaskRpcPreparationService newService(TaskRpcProperties rpcProperties, ProjectDirectory projectDirectory) {
        return new TaskRpcPreparationService(
                workerCatalog, taskCatalog, taskRuntime, taskLifecycle, matching,
                rpcProperties, projectDirectory, new OperationGuard(),
                taskDirectory, taskScores, channels, users
        );
    }

    @Test
    void createsServerMintedParkedTaskForAdminAndReusesIdempotently() {
        var first = service.ensureRawChannel(ADMIN, request());
        assertThat(first.taskId()).matches("rpc-[0-9a-f]{32}");

        var second = service.ensureRawChannel(ADMIN, request());
        assertThat(second.taskId()).isEqualTo(first.taskId());

        ArgumentCaptor<TaskDescriptor> descriptor = ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime, org.mockito.Mockito.times(1)).createTask(descriptor.capture());
        assertThat(descriptor.getValue().idleDisposition()).isEqualTo(TaskIdleDisposition.PARK_WHEN_IDLE);
        assertThat(descriptor.getValue().workerGroupId()).isEqualTo("phone-tools");
        assertThat(descriptor.getValue().metadata())
                .containsEntry("userId", "proof")
                .containsEntry("server.inputOwner", "raw")
                .containsKey(TaskRpcChannelRegistry.METADATA_KEY);
        var attribution = TaskAttribution.require(descriptor.getValue().metadata());
        assertThat(attribution.projectId()).isEqualTo("test-project");
        assertThat(attribution.userId()).isEqualTo("proof");
        verify(taskLifecycle, org.mockito.Mockito.times(2)).approveTask(first.taskId());
    }

    @Test
    void rejectsOperatorCallerAndValidatesExplicitOwnerUserId() {
        assertError(() -> service.ensureRawChannel(OPERATOR, request()),
                ServerErrorCode.TASK_ACCESS_DENIED, "taskAccess.require");
        assertError(() -> service.ensureRawChannel(ADMIN,
                        new TaskRpcPrepareRequest("test-project", "phone-tools", "missing", null, Map.of(), null)),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskRpc.prepare");
        assertError(() -> service.ensureRawChannel(ADMIN,
                        new TaskRpcPrepareRequest("test-project", "phone-tools", "bobby", null, Map.of(), null)),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskRpc.prepare");
        verify(taskRuntime, never()).createTask(any());

        var aliceChannel = service.ensureRawChannel(ADMIN,
                new TaskRpcPrepareRequest("test-project", "phone-tools", "alice", "Alice Channel", Map.of(), null));
        var adminChannel = service.ensureRawChannel(ADMIN, request());
        assertThat(aliceChannel.taskId()).isNotEqualTo(adminChannel.taskId());
        assertThat(stored.get(aliceChannel.taskId()).metadata()).containsEntry("userId", "alice");
        assertThat(stored.get(adminChannel.taskId()).metadata()).containsEntry("userId", "proof");
    }

    @Test
    void explicitEmptyAndHostSupplyWorkAndConflictingConfigIsRejected() {
        var host = List.of(new RefillTarget("host", new EligibilityQuery(Map.of()), 40));
        var explicit = List.of(new RefillTarget("chosen", new EligibilityQuery(Map.of()), 100));
        service = newService(defaultProperties(Map.of("phone-tools", host)), projects());

        var first = service.ensureRawChannel(ADMIN,
                new TaskRpcPrepareRequest("test-project", "phone-tools", null, null, Map.of(), explicit));
        assertThat(stored.get(first.taskId()).refill()).isEqualTo(explicit);

        // Same config returns existing taskId.
        assertThat(service.ensureRawChannel(ADMIN,
                new TaskRpcPrepareRequest("test-project", "phone-tools", null, null, Map.of(), explicit)).taskId())
                .isEqualTo(first.taskId());

        // Conflicting refill on the same (project, owner, group) slot is rejected without creating a second task.
        assertError(() -> service.ensureRawChannel(ADMIN,
                        new TaskRpcPrepareRequest("test-project", "phone-tools", null, null, Map.of(), List.of())),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT, "taskRpc.prepare");
        verify(taskRuntime, org.mockito.Mockito.times(1)).createTask(any());
    }

    @Test
    void invalidExplicitSupplyRejectsWithoutCreatingOrApproving() {
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.eq("phone-tools"), anyList()))
                .thenThrow(new IllegalArgumentException("unknown Pool"));
        assertError(() -> service.ensureRawChannel(ADMIN,
                        new TaskRpcPrepareRequest("test-project", "phone-tools", null, Map.of(), List.of())),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskRpc.prepare");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);
    }

    @Test
    void partialApprovalFailureCanBeCompletedWithoutRecreatingTask() {
        when(taskLifecycle.approveTask(anyString()))
                .thenReturn(new TaskApprovalResult(TaskApprovalStatus.RETRYABLE))
                .thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        assertError(() -> service.ensureRawChannel(ADMIN, request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        var completed = service.ensureRawChannel(ADMIN, request());
        assertThat(completed.taskId()).matches("rpc-[0-9a-f]{32}");
        verify(taskRuntime, org.mockito.Mockito.times(1)).createTask(any());
        verify(taskLifecycle, org.mockito.Mockito.times(2)).approveTask(completed.taskId());
    }

    @Test
    void partialRawCreationRetriesTheSameSlotThroughKernel() {
        org.mockito.Mockito.doAnswer(call -> {
            TaskDescriptor descriptor = call.getArgument(0);
            if (!scoreStates.containsKey(descriptor.taskId())) {
                scoreStates.put(descriptor.taskId(), observedBand(TaskScoreBand.PRE_REVIEW));
                return new TaskCreationResult(TaskCreationStatus.RETRYABLE);
            }
            stored.put(descriptor.taskId(), descriptor);
            return new TaskCreationResult(TaskCreationStatus.CREATED);
        }).when(taskRuntime).createTask(any());
        assertError(() -> service.ensureRawChannel(ADMIN, request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        String retainedId = scoreStates.keySet().iterator().next();
        assertThat(stored).isEmpty();
        service = newService(defaultProperties(Map.of()), projects());
        assertThat(service.ensureRawChannel(ADMIN, request()).taskId()).isEqualTo(retainedId);
        ArgumentCaptor<TaskDescriptor> descriptors = ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime, org.mockito.Mockito.times(2)).createTask(descriptors.capture());
        assertThat(descriptors.getAllValues()).extracting(TaskDescriptor::taskId)
                .containsExactly(retainedId, retainedId);
        verify(taskLifecycle).approveTask(retainedId);
    }

    @Test
    void missingDescriptorWithNonReviewScoreIsNotRecreated() {
        for (TaskScoreBand band : List.of(TaskScoreBand.RUNNING_VISIBLE, TaskScoreBand.TERMINAL)) {
            org.mockito.Mockito.doAnswer(call ->
                    Map.of(call.<List<String>>getArgument(0).getFirst(), observedBand(band)))
                    .when(taskScores).getScoreStates(anyList());
            assertError(() -> service.ensureRawChannel(ADMIN, request()),
                    ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        }
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);
    }

    @Test
    void scoreOnlyRecoveryStillRequiresAnAbsentReadableDirectoryAndReadableScore() {
        org.mockito.Mockito.doAnswer(call ->
                Map.of(call.<List<String>>getArgument(0).getFirst(), observedBand(TaskScoreBand.PRE_REVIEW)))
                .when(taskScores).getScoreStates(anyList());
        when(taskDirectory.contains(anyString(), anyString())).thenReturn(true);
        assertError(() -> service.ensureRawChannel(ADMIN, request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        when(taskDirectory.contains(anyString(), anyString())).thenThrow(new IllegalStateException("directory unavailable"));
        assertError(() -> service.ensureRawChannel(ADMIN, request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        org.mockito.Mockito.doThrow(new IllegalStateException("score unavailable"))
                .when(taskScores).getScoreStates(anyList());
        assertError(() -> service.ensureRawChannel(ADMIN, request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);
    }

    @Test
    void serviceChannelDoesNotRepairAnUnclaimedScoreOnlyIdentity() {
        scoreStates.put("project-rpc-sms", observedBand(TaskScoreBand.PRE_REVIEW));
        var smsService = newService(defaultProperties(Map.of()), new ProjectDirectory(new ProjectAssemblyProperties(List.of()),
                List.of(new ProjectDefinition("sms", List.of("phone-tools"), "sms"))));
        assertError(() -> smsService.prepareServiceChannel(SERVICE, "sms", "project-rpc-sms",
                        new TaskRpcPrepareRequest("sms", "phone-tools", null, Map.of(), null)),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);
    }

    @Test
    void twoProjectsSharingGroupProvisionIndependentSlots() {
        var directory = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("one", List.of("phone-tools"), null),
                new ProjectDefinition("two", List.of("phone-tools"), null))), List.of());
        var initializer = newService(defaultProperties(Map.of()), directory);

        String oneId = initializer.ensureRawChannel(ADMIN, new TaskRpcPrepareRequest("one", "phone-tools", null, Map.of(), null)).taskId();
        String twoId = initializer.ensureRawChannel(ADMIN, new TaskRpcPrepareRequest("two", "phone-tools", null, Map.of(), null)).taskId();
        assertThat(oneId).isNotEqualTo(twoId);
        assertThat(initializer.ensureRawChannel(ADMIN, new TaskRpcPrepareRequest("one", "phone-tools", null, Map.of(), null)).taskId()).isEqualTo(oneId);
        assertThat(initializer.ensureRawChannel(ADMIN, new TaskRpcPrepareRequest("two", "phone-tools", null, Map.of(), null)).taskId()).isEqualTo(twoId);
        verify(taskRuntime, org.mockito.Mockito.times(2)).createTask(any());
    }

    @Test
    void metadataIsImmutableAndServiceChannelRequiresServiceCaller() {
        var attributes = new HashMap<>(Map.of("label", "alice"));
        var request = new TaskRpcPrepareRequest("test-project", "phone-tools", "RPC", attributes, null);
        attributes.put("label", "bobby");
        String id = service.ensureRawChannel(ADMIN, request).taskId();
        assertThat(stored.get(id).metadata()).containsEntry("label", "alice").containsEntry("userId", "proof");
        assertThat(stored.get(id).name()).isEqualTo("RPC");
        assertThatThrownBy(() -> request.metadata().clear()).isInstanceOf(UnsupportedOperationException.class);

        // Service channel rejects non-service callers and succeeds for Project service callers.
        assertError(() -> service.prepareServiceChannel(ADMIN, "sms", "project-rpc-sms", new TaskRpcPrepareRequest("sms", "phone-tools", null, Map.of(), null)),
                ServerErrorCode.TASK_ACCESS_DENIED, "taskRpc.prepare");
        var smsService = newService(defaultProperties(Map.of()), new ProjectDirectory(new ProjectAssemblyProperties(List.of()),
                List.of(new ProjectDefinition("sms", List.of("phone-tools"), "sms"))));
        var smsChannel = smsService.prepareServiceChannel(SERVICE, "sms", "project-rpc-sms",
                new TaskRpcPrepareRequest("sms", "phone-tools", null, Map.of(), null));
        assertThat(smsChannel.taskId()).isEqualTo("project-rpc-sms");
    }

    @Test
    void invalidDisplayDataAndUndeclaredBindingDoNotCreateOrApprove() {
        assertError(() -> service.ensureRawChannel(ADMIN, new TaskRpcPrepareRequest("test-project", "phone-tools", " ", Map.of(), null)),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskRpc.prepare");
        assertError(() -> service.ensureRawChannel(ADMIN, new TaskRpcPrepareRequest("test-project", "absent", null, Map.of(), null)),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "project.requireWorkerGroup");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);
        verify(channels, never()).reserve(any(), anyString(), anyString());
        assertThat(service.ensureRawChannel(ADMIN, request()).taskId()).matches("rpc-[0-9a-f]{32}");
    }

    @Test
    void fullStampedMetadataIsValidatedBeforeReservingAndAcceptsItsExactBounds() {
        var tooMany = new LinkedHashMap<String, String>();
        for (int i = 0; i < 28; i++) tooMany.put("label-" + i, "value");
        for (Map<String, String> invalid : List.of(tooMany, Map.of(" ", "value"),
                Map.of("k".repeat(129), "value"), Map.of("label", "v".repeat(4097)))) {
            assertError(() -> service.ensureRawChannel(ADMIN,
                            new TaskRpcPrepareRequest("test-project", "phone-tools", null, invalid, null)),
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskRpc.prepare");
        }
        verify(channels, never()).reserve(any(), anyString(), anyString());
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);

        var boundary = new LinkedHashMap<String, String>();
        for (int i = 0; i < 26; i++) boundary.put("label-" + i, "value");
        boundary.put("k".repeat(128), "v".repeat(4096));
        String id = service.ensureRawChannel(ADMIN,
                new TaskRpcPrepareRequest("test-project", "phone-tools", "n".repeat(128), boundary, null)).taskId();
        assertThat(stored.get(id).metadata()).hasSize(32);
        assertThat(service.ensureRawChannel(ADMIN,
                new TaskRpcPrepareRequest("test-project", "phone-tools", "n".repeat(128), boundary, null)).taskId()).isEqualTo(id);
    }

    @Test
    void concurrentPreparationUsesOneMutationAdmissionAndReleasesItAfterFailure() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
            throw new IllegalStateException("commit not confirmed");
        }).when(taskRuntime).createTask(any());
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.ensureRawChannel(ADMIN, request()));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertError(() -> service.ensureRawChannel(ADMIN, request()),
                        ServerErrorCode.TASK_STATE_CONFLICT, "taskMutation.execute");
            } finally {
                release.countDown();
            }
            assertThatThrownBy(first::get).hasCauseInstanceOf(ServerException.class);
        }
    }

    private static TaskRpcPrepareRequest request() {
        return new TaskRpcPrepareRequest("test-project", "phone-tools", null, Map.of(), null);
    }

    private static TaskScoreState observedBand(TaskScoreBand band) {
        TaskScoreState state = mock(TaskScoreState.class);
        when(state.band()).thenReturn(band);
        return state;
    }

    private static TaskRpcProperties defaultProperties(Map<String, List<RefillTarget>> defaults) {
        return new TaskRpcProperties(Duration.ofDays(7), 1000, 1000, 10, 10, 10, 50, 100, 250, defaults);
    }

    private static ProjectDirectory projects() {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("test-project", List.of("phone-tools"), null),
                new ProjectDefinition("other-project", List.of("phone-tools"), null))), List.of());
    }

    private static void assertError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            ServerErrorCode errorCode,
            String operation
    ) {
        assertThatThrownBy(action).isInstanceOfSatisfying(
                ServerException.class,
                error -> {
                    assertThat(error.errorCode()).isEqualTo(errorCode);
                    assertThat(error.operation()).isEqualTo(operation);
                }
        );
    }
}
