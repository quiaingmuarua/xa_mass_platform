package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalStatus;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ServerWorkerTaskInitializer {

    private static final Map<String, String> TASK_CONFIG = Map.of(
            "priority", "0",
            "maximumCandidateWorkers", "1",
            "maxRetryTimes", "3"
    );

    private final ServerWorkerAssemblyManifest manifest;
    private final TaskResourceCatalog taskCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskLifecycleCommands taskLifecycle;
    private boolean initialized;

    ServerWorkerTaskInitializer(
            ServerWorkerAssemblyManifest manifest,
            TaskResourceCatalog taskCatalog,
            TaskRuntime taskRuntime,
            TaskLifecycleCommands taskLifecycle
    ) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.taskLifecycle = Objects.requireNonNull(
                taskLifecycle,
                "taskLifecycle"
        );
    }

    synchronized void initialize() {
        if (initialized) {
            return;
        }
        Map<String, String> taskIds = manifest.taskIdsByWorkerGroup();
        Map<String, TaskDescriptor> stored =
                taskCatalog.loadTaskAllocationDescriptors(
                        List.copyOf(taskIds.values())
                );
        for (Map.Entry<String, String> entry : taskIds.entrySet()) {
            TaskDescriptor expected = descriptor(
                    entry.getKey(),
                    entry.getValue()
            );
            TaskDescriptor existing = stored.get(entry.getValue());
            if (existing == null) {
                create(expected);
            } else if (!expected.equals(existing)) {
                throw new IllegalStateException(
                        "Profile Task descriptor conflicts for "
                                + entry.getKey()
                );
            }
            approve(entry.getKey(), entry.getValue());
        }
        initialized = true;
    }

    private void create(TaskDescriptor descriptor) {
        TaskCreationResult result = taskRuntime.createTask(descriptor);
        if (result == null || result.status() != TaskCreationStatus.CREATED) {
            throw new IllegalStateException(
                    "Profile Task creation failed for "
                            + descriptor.workerGroupId()
                            + statusAndReason(result)
            );
        }
    }

    private void approve(String workerGroupId, String taskId) {
        TaskApprovalResult result = taskLifecycle.approveTask(taskId);
        if (result == null
                || (result.status() != TaskApprovalStatus.APPROVED
                && result.status() != TaskApprovalStatus.ALREADY_APPROVED)) {
            throw new IllegalStateException(
                    "Profile Task approval failed for "
                            + workerGroupId
                            + statusAndReason(result)
            );
        }
    }

    private static TaskDescriptor descriptor(
            String workerGroupId,
            String taskId
    ) {
        return new TaskDescriptor(
                taskId,
                workerGroupId,
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                TASK_CONFIG
        );
    }

    private static String statusAndReason(TaskCreationResult result) {
        if (result == null) {
            return ": missing result";
        }
        return ": "
                + result.status().wireValue()
                + (result.reason() == null ? "" : ": " + result.reason());
    }

    private static String statusAndReason(TaskApprovalResult result) {
        if (result == null) {
            return ": missing result";
        }
        return ": "
                + result.status().wireValue()
                + (result.reason() == null ? "" : ": " + result.reason());
    }
}
