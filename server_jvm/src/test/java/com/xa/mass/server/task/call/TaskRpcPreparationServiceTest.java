package com.xa.mass.server.task.call;
import com.xa.mass.server.project.*;
import com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.call.TaskRpcProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskRpcPreparationServiceTest {

    private com.xa.mass.workermatching.WorkerMatchingCatalog matching;
    private WorkerResourceCatalog workerCatalog;
    private TaskResourceCatalog taskCatalog;
    private TaskRuntime taskRuntime;
    private TaskLifecycleCommands taskLifecycle;
    private TaskRpcPreparationService service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        taskRuntime = mock(TaskRuntime.class);
        taskLifecycle = mock(TaskLifecycleCommands.class);
        matching=mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.anyString(),anyList()))
                .thenAnswer(call -> call.getArgument(1));
        service = new TaskRpcPreparationService(workerCatalog, taskCatalog, taskRuntime, taskLifecycle, matching, new com.xa.mass.server.task.call.TaskRpcProperties(1000,1000,10,10,10,50,100,250,java.util.Map.of()), projects(), new OperationGuard(), org.mockito.Mockito.mock(com.xa.mass.server.task.directory.TaskDirectory.class), org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskScoreBandCore.class));
        when(workerCatalog.getWorkerGroupDescriptors(List.of("phone-tools")))
                .thenReturn(Map.of(
                        "phone-tools",
                        new WorkerGroupDescriptor(
                                "phone-tools",
                                Map.of(),
                                Set.of("phone.lookup")
                        )
                ));
    }

    @Test
    void createsAndApprovesTheCallerNamedParkedTask() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CREATED)
        );
        when(taskLifecycle.approveTask("rpc-test"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.APPROVED
                ));

        var registration = service.prepare("rpc-test", request());

        assertThat(registration.taskId())
                .isEqualTo("rpc-test");
        ArgumentCaptor<TaskDescriptor> descriptor =
                ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime).createTask(descriptor.capture());
        assertThat(descriptor.getValue()).usingRecursiveComparison().ignoringFields("metadata").isEqualTo(expectedDescriptor());
        var attribution = com.xa.mass.server.task.directory.TaskAttribution.require(descriptor.getValue().metadata());
        assertThat(attribution.projectId()).isEqualTo("test-project");
        assertThat(attribution.createdAtMillis()).isPositive();
        verify(taskLifecycle).approveTask("rpc-test");
    }

    @Test void explicitEmptyManagedOverrideCreatesNoSupply() {
        var rpc=new TaskRpcProperties(1000,1000,10,10,10,50,100,250,Map.of("phone-tools",List.of()));
        service=new TaskRpcPreparationService(workerCatalog,taskCatalog,taskRuntime,taskLifecycle,matching,rpc,projects(),new OperationGuard(), org.mockito.Mockito.mock(com.xa.mass.server.task.directory.TaskDirectory.class), org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskScoreBandCore.class));
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(new TaskCreationResult(TaskCreationStatus.CREATED));
        when(taskLifecycle.approveTask("rpc-test")).thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        service.prepare("rpc-test", request());
        var descriptor=ArgumentCaptor.forClass(TaskDescriptor.class); verify(taskRuntime).createTask(descriptor.capture());
        assertThat(descriptor.getValue().refill()).isEmpty();
        verify(matching).normalizeRefill("phone-tools",List.of());
    }

    @Test
    void exactExistingRegistrationIsIdempotent() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        "rpc-test",
                        expectedDescriptor()
                ));
        when(taskLifecycle.approveTask("rpc-test"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED
                ));

        var registration = service.prepare("rpc-test", request());

        assertThat(registration.taskId())
                .isEqualTo("rpc-test");
        assertThat(service.prepare("rpc-test", request()).taskId()).isEqualTo(registration.taskId());
        verify(taskRuntime, never()).createTask(any());
    }

    @Test void savedTargetsSurviveDefaultChangesButReregistrationConflicts() {
        var saved=expectedDescriptor();
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of(saved.taskId(),saved));
        org.mockito.Mockito.clearInvocations(matching);
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.eq("phone-tools"),anyList()))
                .thenReturn(List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 20)));
        assertThat("rpc-test").isEqualTo(saved.taskId());
        org.mockito.Mockito.verifyNoInteractions(matching);
        assertError(()->service.prepare("rpc-test", request()),ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,"taskRpc.prepare");
        verify(taskRuntime,never()).createTask(any());
        verify(taskLifecycle,never()).approveTask(any());
    }

    @Test void targetResolutionFailureCannotCreateOrApproveATask() {
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.eq("phone-tools"),anyList()))
                .thenThrow(new IllegalArgumentException("invalid targets"));
        assertError(()->service.prepare("rpc-test", request()),ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,"taskRpc.prepare");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime,taskLifecycle);
        verify(taskCatalog).loadTaskAllocationDescriptors(List.of("rpc-test"));
    }

    @Test
    void conflictingPersistentDescriptorRejectsRegistration() {
        TaskDescriptor conflict = new TaskDescriptor("rpc-test", "phone-tools", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(
                        "priority", "1",
                        "maxRetryTimes", "3"
                ), java.util.List.of(), null, expectedDescriptor().metadata());
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(conflict.taskId(), conflict));

        assertError(
                () -> service.prepare("rpc-test", request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                "taskRpc.prepare"
        );
        verify(taskRuntime, never()).createTask(any());
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void createConflictRereadsExactOwnerTruth() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of())
                .thenReturn(Map.of(
                        "rpc-test",
                        expectedDescriptor()
                ));
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CONFLICT)
        );
        when(taskLifecycle.approveTask("rpc-test"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED
                ));

        assertThat(service.prepare("rpc-test", request()).taskId()).isEqualTo("rpc-test");
    }

    @Test
    void directoryLookupIsPureConfigurationAndRejectsUndeclaredGroups() {
        projects().requireWorkerGroup("test-project", "phone-tools");
        assertThatThrownBy(() -> projects().requireWorkerGroup("test-project", "missing-group"))
                .isInstanceOf(ServerException.class);
        org.mockito.Mockito.verifyNoInteractions(taskCatalog,taskRuntime,taskLifecycle);
    }

    @Test
    void unobservableCreateConflictIsRetryableInsteadOfInventingTruth() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CONFLICT)
        );

        assertError(
                () -> service.prepare("rpc-test", request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "taskRpc.prepare"
        );
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void ownerInvalidCreationIsUnavailableBecauseCallerHasNoTaskBody() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(
                        TaskCreationStatus.INVALID,
                        "owner rejected derived descriptor"
                )
        );

        assertError(
                () -> service.prepare("rpc-test", request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "taskRpc.prepare"
        );
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void terminalApprovalConflictRemainsARegistrationConflict() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        "rpc-test",
                        expectedDescriptor()
                ));
        when(taskLifecycle.approveTask("rpc-test"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.CONFLICT,
                        "Task is terminal"
                ));

        assertError(
                () -> service.prepare("rpc-test", request()),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                "taskRpc.prepare"
        );
        verify(taskRuntime, never()).createTask(any());
    }

    @Test void partialApprovalFailureCanBeCompletedWithoutRecreatingTask() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of())
                .thenReturn(Map.of(expectedDescriptor().taskId(), expectedDescriptor()));
        when(taskRuntime.createTask(any())).thenReturn(new TaskCreationResult(TaskCreationStatus.CREATED));
        when(taskLifecycle.approveTask(expectedDescriptor().taskId()))
                .thenReturn(new TaskApprovalResult(TaskApprovalStatus.RETRYABLE))
                .thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        assertError(() -> service.prepare("rpc-test", request()), ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "taskRpc.prepare");
        service.prepare("rpc-test", request());
        verify(taskRuntime, org.mockito.Mockito.times(1)).createTask(any());
        verify(taskLifecycle, org.mockito.Mockito.times(2)).approveTask(expectedDescriptor().taskId());
    }

    @Test void twoProjectsSharingGroupExplicitlyPrepareIndependentTasksAndReuseBoth() {
        var directory = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("one", List.of("phone-tools")),
                new ProjectDefinition("two", List.of("phone-tools")))), java.util.List.of());
        var saved = new java.util.LinkedHashMap<String, TaskDescriptor>();
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenAnswer(call -> {
            var result = new java.util.LinkedHashMap<String, TaskDescriptor>();
            for (String id : call.<List<String>>getArgument(0)) result.put(id, saved.get(id));
            return result;
        });
        when(taskRuntime.createTask(any())).thenAnswer(call -> {
            TaskDescriptor descriptor = call.getArgument(0);
            saved.put(descriptor.taskId(), descriptor);
            return new TaskCreationResult(TaskCreationStatus.CREATED);
        });
        when(taskLifecycle.approveTask(any())).thenReturn(new TaskApprovalResult(TaskApprovalStatus.ALREADY_APPROVED));
        var initializer = new TaskRpcPreparationService(workerCatalog, taskCatalog, taskRuntime, taskLifecycle,
                matching, new TaskRpcProperties(1000,1000,10,10,10,50,100,250,Map.of()), directory, new OperationGuard(), org.mockito.Mockito.mock(com.xa.mass.server.task.directory.TaskDirectory.class), org.mockito.Mockito.mock(com.xa.mass.kernel.score.TaskScoreBandCore.class));
        for (int repeat = 0; repeat < 2; repeat++) {
            initializer.prepare("rpc-one", new TaskRpcPrepareRequest("one", "phone-tools", null, Map.of()));
            initializer.prepare("rpc-two", new TaskRpcPrepareRequest("two", "phone-tools", null, Map.of()));
        }
        assertThat(saved.values()).extracting(task -> com.xa.mass.server.task.directory.TaskAttribution.require(task.metadata()).projectId()).containsExactly("one", "two");
        verify(taskRuntime, org.mockito.Mockito.times(2)).createTask(any());
    }

    @Test void callerIdentityAndMetadataAreOpaqueAndImmutable() {
        var attributes = new java.util.HashMap<>(Map.of("userId", "alice"));
        var request = new TaskRpcPrepareRequest("test-project", "phone-tools", "RPC", attributes);
        attributes.put("userId", "bobby");
        when(taskRuntime.createTask(any())).thenReturn(new TaskCreationResult(TaskCreationStatus.CREATED));
        when(taskLifecycle.approveTask("caller-chosen"))
                .thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        assertThat(service.prepare("caller-chosen", request).taskId()).isEqualTo("caller-chosen");
        var descriptor = ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime).createTask(descriptor.capture());
        assertThat(descriptor.getValue().metadata()).containsEntry("userId", "alice")
                .containsOnlyKeys("userId", "server.projectId", "server.createdAtMillis");
        assertThat(descriptor.getValue().name()).isEqualTo("RPC");
        assertThatThrownBy(() -> request.metadata().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void invalidDisplayDataAndUndeclaredBindingDoNotCreateOrApprove() {
        assertError(() -> service.prepare("rpc-test", new TaskRpcPrepareRequest("test-project", "phone-tools", " ", Map.of())),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskRpc.prepare");
        assertError(() -> service.prepare("rpc-test", new TaskRpcPrepareRequest("test-project", "absent", null, Map.of())),
                ServerErrorCode.INVALID_TASK_DATA_REQUEST, "project.requireWorkerGroup");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime, taskLifecycle);
    }

    @Test void concurrentPreparationUsesOneMutationAdmissionAndReleasesItAfterFailure() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(taskRuntime.createTask(any())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("barrier timed out");
            throw new IllegalStateException("commit not confirmed");
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> service.prepare("rpc-test", request()));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertError(() -> service.prepare("rpc-test", request()), ServerErrorCode.TASK_STATE_CONFLICT, "taskMutation.execute");
            } finally { release.countDown(); }
            assertThatThrownBy(first::get).hasCauseInstanceOf(ServerException.class);
        }
        when(taskCatalog.loadTaskAllocationDescriptors(List.of("rpc-test")))
                .thenReturn(Map.of("rpc-test", expectedDescriptor()));
        when(taskLifecycle.approveTask("rpc-test")).thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        assertThat(service.prepare("rpc-test", request()).taskId()).isEqualTo("rpc-test");
        verify(taskRuntime).createTask(any());
        verify(taskLifecycle).approveTask("rpc-test");
    }

    private static TaskRpcPrepareRequest request() {
        return new TaskRpcPrepareRequest("test-project", "phone-tools", null, Map.of());
    }

    private static ProjectDirectory projects() {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectDefinition("test-project", List.of("phone-tools")))), java.util.List.of());
    }

    private static TaskDescriptor expectedDescriptor() {
        return new TaskDescriptor("rpc-test", "phone-tools", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(
                        "priority", "0",
                        "maxRetryTimes", "3"
                ), java.util.List.of(), null, new com.xa.mass.server.task.directory.TaskAttribution("test-project", 123L, null).stamp(Map.of()));
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
