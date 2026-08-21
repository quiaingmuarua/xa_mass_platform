package com.xa.mass.server.workerassembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands.TaskApprovalStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ServerWorkerTaskInitializerTest {

    @Test
    void createsAndApprovesOneReusableDirectTaskPerGroup() {
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        TaskRuntime runtime = mock(TaskRuntime.class);
        TaskLifecycleCommands lifecycle = mock(TaskLifecycleCommands.class);
        when(catalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(new LinkedHashMap<>());
        when(runtime.createTask(any(), eq(0))).thenReturn(
                new TaskCreationResult(TaskCreationStatus.CREATED)
        );
        when(lifecycle.approveTask(any())).thenReturn(
                new TaskApprovalResult(TaskApprovalStatus.APPROVED, null)
        );
        ServerWorkerTaskInitializer initializer = initializer(
                twoGroupManifest(),
                catalog,
                runtime,
                lifecycle
        );

        initializer.initialize();
        initializer.initialize();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> ids = ArgumentCaptor.forClass(List.class);
        verify(catalog).loadTaskAllocationDescriptors(ids.capture());
        assertThat(ids.getValue()).containsExactly(
                "scenario-rpc-phone-group",
                "scenario-rpc-string-group"
        );
        ArgumentCaptor<TaskDescriptor> descriptors =
                ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(runtime, times(2)).createTask(descriptors.capture(), eq(0));
        assertThat(descriptors.getAllValues())
                .allSatisfy(descriptor -> {
                    assertThat(descriptor.workerAllocationMechanism())
                            .isEqualTo(
                                    WorkerAllocationMechanism.DIRECT_ITEM_RULE
                            );
                    assertThat(descriptor.idleDisposition())
                            .isEqualTo(
                                    TaskIdleDisposition.PARK_WHEN_IDLE
                            );
                    assertThat(descriptor.allocationRule()).isNull();
                    assertThat(descriptor.config()).containsExactlyInAnyOrderEntriesOf(
                            Map.of(
                                    "priority", "0",
                                    "maximumCandidateWorkers", "1",
                                    "maxRetryTimes", "3"
                            )
                    );
                });
        verify(lifecycle).approveTask("scenario-rpc-phone-group");
        verify(lifecycle).approveTask("scenario-rpc-string-group");
    }

    @Test
    void reusesAnExactTaskAndStillEnsuresApproval() {
        ServerWorkerAssemblyManifest manifest = ServerWorkerAssemblyManifest
                .fromJson("{\"phone-group\":{\"eventCodes\":[]}}");
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        TaskRuntime runtime = mock(TaskRuntime.class);
        TaskLifecycleCommands lifecycle = mock(TaskLifecycleCommands.class);
        TaskDescriptor existing = expectedDescriptor(
                "phone-group",
                "scenario-rpc-phone-group"
        );
        when(catalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(Map.of(existing.taskId(), existing));
        when(lifecycle.approveTask(existing.taskId())).thenReturn(
                new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED,
                        null
                )
        );

        initializer(manifest, catalog, runtime, lifecycle).initialize();

        verify(runtime, never()).createTask(any(), eq(0));
        verify(lifecycle).approveTask(existing.taskId());
    }

    @Test
    void rejectsAConflictingPersistentTaskBeforeApproval() {
        ServerWorkerAssemblyManifest manifest = ServerWorkerAssemblyManifest
                .fromJson("{\"phone-group\":{\"eventCodes\":[]}}");
        TaskResourceCatalog catalog = mock(TaskResourceCatalog.class);
        TaskRuntime runtime = mock(TaskRuntime.class);
        TaskLifecycleCommands lifecycle = mock(TaskLifecycleCommands.class);
        TaskDescriptor conflict = new TaskDescriptor(
                "scenario-rpc-phone-group",
                "another-group",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
        when(catalog.loadTaskAllocationDescriptors(any()))
                .thenReturn(Map.of(conflict.taskId(), conflict));

        assertThatThrownBy(
                () -> initializer(
                        manifest,
                        catalog,
                        runtime,
                        lifecycle
                ).initialize()
        ).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("phone-group")
                .hasMessageContaining("conflicts");

        verify(runtime, never()).createTask(any(), eq(0));
        verify(lifecycle, never()).approveTask(any());
    }

    private static ServerWorkerTaskInitializer initializer(
            ServerWorkerAssemblyManifest manifest,
            TaskResourceCatalog catalog,
            TaskRuntime runtime,
            TaskLifecycleCommands lifecycle
    ) {
        return new ServerWorkerTaskInitializer(
                manifest,
                catalog,
                runtime,
                lifecycle
        );
    }

    private static ServerWorkerAssemblyManifest twoGroupManifest() {
        return ServerWorkerAssemblyManifest.fromJson("""
                {
                  "phone-group":{"eventCodes":["phone.lookup"]},
                  "string-group":{"eventCodes":["string.hash"]}
                }
                """);
    }

    private static TaskDescriptor expectedDescriptor(
            String workerGroupId,
            String taskId
    ) {
        return new TaskDescriptor(
                taskId,
                workerGroupId,
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
}
