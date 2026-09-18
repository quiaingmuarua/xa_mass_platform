package com.xa.mass.server.project;
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

class ProjectTaskInitializerTest {

    private com.xa.mass.workermatching.WorkerMatchingCatalog matching;
    private WorkerResourceCatalog workerCatalog;
    private TaskResourceCatalog taskCatalog;
    private TaskRuntime taskRuntime;
    private TaskLifecycleCommands taskLifecycle;
    private ProjectTaskInitializer service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        taskRuntime = mock(TaskRuntime.class);
        taskLifecycle = mock(TaskLifecycleCommands.class);
        matching=mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.anyString(),anyList()))
                .thenAnswer(call -> call.getArgument(1));
        service = new ProjectTaskInitializer(workerCatalog, taskCatalog, taskRuntime, taskLifecycle, matching, new com.xa.mass.server.task.call.TaskRpcProperties(1000,1000,10,10,10,50,100,250,java.util.Map.of()), projects());
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
    void createsAndApprovesTheDerivedDirectParkedTask() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CREATED)
        );
        when(taskLifecycle.approveTask(projects().requireManagedTaskId("test-project", "phone-tools")))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.APPROVED
                ));

        var registration = service.prepare("test-project", "phone-tools");

        assertThat(registration.workerGroupId()).isEqualTo("phone-tools");
        assertThat(registration.taskId())
                .isEqualTo(projects().requireManagedTaskId("test-project", "phone-tools"));
        assertThat(registration.newlyRegistered()).isTrue();
        ArgumentCaptor<TaskDescriptor> descriptor =
                ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime).createTask(descriptor.capture());
        assertThat(descriptor.getValue()).isEqualTo(expectedDescriptor());
        verify(taskLifecycle).approveTask(projects().requireManagedTaskId("test-project", "phone-tools"));
    }

    @Test void explicitEmptyManagedOverrideCreatesNoSupply() {
        var rpc=new TaskRpcProperties(1000,1000,10,10,10,50,100,250,Map.of("phone-tools",List.of()));
        service=new ProjectTaskInitializer(workerCatalog,taskCatalog,taskRuntime,taskLifecycle,matching,rpc,projects());
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(new TaskCreationResult(TaskCreationStatus.CREATED));
        when(taskLifecycle.approveTask(projects().requireManagedTaskId("test-project", "phone-tools"))).thenReturn(new TaskApprovalResult(TaskApprovalStatus.APPROVED));
        service.prepare("test-project", "phone-tools");
        var descriptor=ArgumentCaptor.forClass(TaskDescriptor.class); verify(taskRuntime).createTask(descriptor.capture());
        assertThat(descriptor.getValue().refill()).isEmpty();
        verify(matching).normalizeRefill("phone-tools",List.of());
    }

    @Test
    void exactExistingRegistrationIsIdempotent() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        projects().requireManagedTaskId("test-project", "phone-tools"),
                        expectedDescriptor()
                ));
        when(taskLifecycle.approveTask(projects().requireManagedTaskId("test-project", "phone-tools")))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED
                ));

        var registration = service.prepare("test-project", "phone-tools");

        assertThat(registration.taskId())
                .isEqualTo(projects().requireManagedTaskId("test-project", "phone-tools"));
        assertThat(service.prepare("test-project", "phone-tools").taskId()).isEqualTo(registration.taskId());
        assertThat(registration.newlyRegistered()).isFalse();
        verify(taskRuntime, never()).createTask(any());
    }

    @Test void savedTargetsSurviveDefaultChangesButReregistrationConflicts() {
        var saved=expectedDescriptor();
        when(taskCatalog.loadTaskAllocationDescriptors(anyList())).thenReturn(Map.of(saved.taskId(),saved));
        org.mockito.Mockito.clearInvocations(matching);
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.eq("phone-tools"),anyList()))
                .thenReturn(List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 20)));
        assertThat(projects().requireManagedTaskId("test-project", "phone-tools")).isEqualTo(saved.taskId());
        org.mockito.Mockito.verifyNoInteractions(matching);
        assertError(()->service.prepare("test-project", "phone-tools"),ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,"project.initialize");
        verify(taskRuntime,never()).createTask(any());
        verify(taskLifecycle,never()).approveTask(any());
    }

    @Test void targetResolutionFailureCannotCreateOrApproveATask() {
        when(matching.normalizeRefill(org.mockito.ArgumentMatchers.eq("phone-tools"),anyList()))
                .thenThrow(new IllegalArgumentException("invalid targets"));
        assertError(()->service.prepare("test-project", "phone-tools"),ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,"project.initialize");
        org.mockito.Mockito.verifyNoInteractions(taskRuntime,taskCatalog,taskLifecycle);
    }

    @Test
    void conflictingPersistentDescriptorRejectsRegistration() {
        TaskDescriptor conflict = new TaskDescriptor(projects().requireManagedTaskId("test-project", "phone-tools"), "test-project", "phone-tools", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(
                        "priority", "1",
                        "maxRetryTimes", "3"
                ), java.util.List.of());
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(conflict.taskId(), conflict));

        assertError(
                () -> service.prepare("test-project", "phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                "project.initialize"
        );
        verify(taskRuntime, never()).createTask(any());
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void createConflictRereadsExactOwnerTruth() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of())
                .thenReturn(Map.of(
                        projects().requireManagedTaskId("test-project", "phone-tools"),
                        expectedDescriptor()
                ));
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CONFLICT)
        );
        when(taskLifecycle.approveTask(projects().requireManagedTaskId("test-project", "phone-tools")))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED
                ));

        assertThat(service.prepare("test-project", "phone-tools").newlyRegistered())
                .isFalse();
    }

    @Test
    void directoryLookupIsPureConfigurationAndRejectsUndeclaredGroups() {
        assertThat(projects().requireManagedTaskId("test-project", "phone-tools")).startsWith("project-rpc-");
        assertThatThrownBy(() -> projects().requireManagedTaskId("test-project", "missing-group"))
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
                () -> service.prepare("test-project", "phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "project.initialize"
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
                () -> service.prepare("test-project", "phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "project.initialize"
        );
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void terminalApprovalConflictRemainsARegistrationConflict() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        projects().requireManagedTaskId("test-project", "phone-tools"),
                        expectedDescriptor()
                ));
        when(taskLifecycle.approveTask(projects().requireManagedTaskId("test-project", "phone-tools")))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.CONFLICT,
                        "Task is terminal"
                ));

        assertError(
                () -> service.prepare("test-project", "phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                "project.initialize"
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
        assertError(service::initialize, ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, "project.initialize");
        service.initialize();
        verify(taskRuntime, org.mockito.Mockito.times(1)).createTask(expectedDescriptor());
        verify(taskLifecycle, org.mockito.Mockito.times(2)).approveTask(expectedDescriptor().taskId());
    }

    @Test void twoProjectsSharingGroupCreateIndependentManagedTasksAndRestartReusesBoth() {
        var directory = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectAssemblyProperties.Project("one", List.of("phone-tools")),
                new ProjectAssemblyProperties.Project("two", List.of("phone-tools")))));
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
        var initializer = new ProjectTaskInitializer(workerCatalog, taskCatalog, taskRuntime, taskLifecycle,
                matching, new TaskRpcProperties(1000,1000,10,10,10,50,100,250,Map.of()), directory);
        initializer.initialize();
        initializer.initialize();
        assertThat(saved.values()).extracting(TaskDescriptor::projectId).containsExactly("one", "two");
        verify(taskRuntime, org.mockito.Mockito.times(2)).createTask(any());
    }

    private static ProjectDirectory projects() {
        return new ProjectDirectory(new ProjectAssemblyProperties(List.of(
                new ProjectAssemblyProperties.Project("test-project", List.of("phone-tools")))));
    }

    private static TaskDescriptor expectedDescriptor() {
        return new TaskDescriptor(projects().requireManagedTaskId("test-project", "phone-tools"), "test-project", "phone-tools", TaskIdleDisposition.PARK_WHEN_IDLE, Map.of(
                        "priority", "0",
                        "maxRetryTimes", "3"
                ), java.util.List.of());
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
