package com.xa.mass.server.taskdata;

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
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkerGroupTaskCallRegistrationServiceTest {

    private WorkerResourceCatalog workerCatalog;
    private TaskResourceCatalog taskCatalog;
    private TaskRuntime taskRuntime;
    private TaskLifecycleCommands taskLifecycle;
    private WorkerGroupTaskCallRegistrationService service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        taskRuntime = mock(TaskRuntime.class);
        taskLifecycle = mock(TaskLifecycleCommands.class);
        service = new WorkerGroupTaskCallRegistrationService(
                workerCatalog,
                taskCatalog,
                taskRuntime,
                taskLifecycle
        );
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
        when(taskLifecycle.approveTask("scenario-rpc-phone-tools"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.APPROVED
                ));

        var registration = service.register("phone-tools");

        assertThat(registration.workerGroupId()).isEqualTo("phone-tools");
        assertThat(registration.newlyRegistered()).isTrue();
        ArgumentCaptor<TaskDescriptor> descriptor =
                ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime).createTask(descriptor.capture());
        assertThat(descriptor.getValue()).isEqualTo(expectedDescriptor());
        verify(taskLifecycle).approveTask("scenario-rpc-phone-tools");
    }

    @Test
    void exactExistingRegistrationIsIdempotent() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        "scenario-rpc-phone-tools",
                        expectedDescriptor()
                ));
        when(taskLifecycle.approveTask("scenario-rpc-phone-tools"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED
                ));

        var registration = service.register("phone-tools");

        assertThat(registration.newlyRegistered()).isFalse();
        verify(taskRuntime, never()).createTask(any());
    }

    @Test
    void conflictingPersistentDescriptorRejectsRegistration() {
        TaskDescriptor conflict = new TaskDescriptor(
                "scenario-rpc-phone-tools",
                "phone-tools",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "1",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(conflict.taskId(), conflict));

        assertError(
                () -> service.register("phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                "taskCall.register"
        );
        verify(taskRuntime, never()).createTask(any());
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void createConflictRereadsExactOwnerTruth() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of())
                .thenReturn(Map.of(
                        "scenario-rpc-phone-tools",
                        expectedDescriptor()
                ));
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CONFLICT)
        );
        when(taskLifecycle.approveTask("scenario-rpc-phone-tools"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED
                ));

        assertThat(service.register("phone-tools").newlyRegistered())
                .isFalse();
    }

    @Test
    void resolveRequiresBothTheGroupAndExactRegistrationTruth() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of());

        assertError(
                () -> service.requireRegisteredTaskId("phone-tools"),
                ServerErrorCode.TASK_CALL_NOT_REGISTERED,
                "taskCall.resolve"
        );

        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        "scenario-rpc-phone-tools",
                        expectedDescriptor()
                ));
        assertThat(service.requireRegisteredTaskId("phone-tools"))
                .isEqualTo("scenario-rpc-phone-tools");

        assertError(
                () -> service.requireRegisteredTaskId("missing-group"),
                ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                "taskCall.resolve"
        );
    }

    @Test
    void unobservableCreateConflictIsRetryableInsteadOfInventingTruth() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of());
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CONFLICT)
        );

        assertError(
                () -> service.register("phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "taskCall.register"
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
                () -> service.register("phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                "taskCall.register"
        );
        verify(taskLifecycle, never()).approveTask(any());
    }

    @Test
    void terminalApprovalConflictRemainsARegistrationConflict() {
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenReturn(Map.of(
                        "scenario-rpc-phone-tools",
                        expectedDescriptor()
                ));
        when(taskLifecycle.approveTask("scenario-rpc-phone-tools"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.CONFLICT,
                        "Task is terminal"
                ));

        assertError(
                () -> service.register("phone-tools"),
                ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                "taskCall.register"
        );
        verify(taskRuntime, never()).createTask(any());
    }

    private static TaskDescriptor expectedDescriptor() {
        return new TaskDescriptor(
                "scenario-rpc-phone-tools",
                "phone-tools",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
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
