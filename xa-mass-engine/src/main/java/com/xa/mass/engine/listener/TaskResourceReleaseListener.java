package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskWorkAttemptClosedEvent;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.engine.assignment.AssignmentRefillDecision;
import com.xa.mass.engine.assignment.AssignmentRefillPolicy;
import com.xa.mass.engine.assignment.AssignmentRefillRequest;
import com.xa.mass.engine.assignment.DefaultAssignmentRefillPolicy;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Releases runtime-only resource occupancy when a task reaches TERMINAL.
 */
public class TaskResourceReleaseListener {

    private final TaskRuntimeMaintenancePort maintenancePort;
    private final WorkerManager workerManager;
    private final TraceEventLogger traceEventLogger;
    private final AssignmentRefillPolicy refillPolicy;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerDispatchResourceReleaser resourceReleaser;

    public TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                       WorkerManager workerManager) {
        this(maintenancePort, workerManager, TraceEventLogger.noop());
    }

    public TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                       WorkerManager workerManager,
                                       TraceEventLogger traceEventLogger) {
        this(maintenancePort, workerManager, traceEventLogger, new DefaultAssignmentRefillPolicy());
    }

    TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                WorkerManager workerManager,
                                TraceEventLogger traceEventLogger,
                                AssignmentRefillPolicy refillPolicy) {
        this(maintenancePort, workerManager, traceEventLogger, refillPolicy, new DefaultWorkerDispatchResourcePolicy());
    }

    TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                WorkerManager workerManager,
                                TraceEventLogger traceEventLogger,
                                AssignmentRefillPolicy refillPolicy,
                                WorkerDispatchResourcePolicy resourcePolicy) {
        this(maintenancePort, workerManager, traceEventLogger, refillPolicy, resourcePolicy, null);
    }

    TaskResourceReleaseListener(TaskRuntimeMaintenancePort maintenancePort,
                                WorkerManager workerManager,
                                TraceEventLogger traceEventLogger,
                                AssignmentRefillPolicy refillPolicy,
                                WorkerDispatchResourcePolicy resourcePolicy,
                                WorkerDispatchResourceReleaser resourceReleaser) {
        this.maintenancePort = maintenancePort;
        this.workerManager = workerManager;
        this.traceEventLogger = traceEventLogger;
        this.refillPolicy = refillPolicy == null ? new DefaultAssignmentRefillPolicy() : refillPolicy;
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.resourceReleaser = resourceReleaser == null
                ? new WorkerDispatchResourceReleaser(workerManager, this.resourcePolicy, traceEventLogger)
                : resourceReleaser;
    }

    public void onTaskTerminal(Task task) {
        if (task == null) {
            return;
        }

        List<ActiveLeaseRecord> leases = maintenancePort.getActiveLeases(task.getTid());
        Set<String> exclusiveWorkerIds = new LinkedHashSet<>();

        for (ActiveLeaseRecord lease : leases) {
            if (lease == null || lease.workerId() == null || lease.workerId().isBlank()) {
                continue;
            }
            workerManager.recordWorkFinal(lease.workerId(), task.getTid());
            WorkerDispatchResourceUsage usage = resourcePolicy.usageForAttempt(task);
            if (usage.exclusiveWorkerLock()) {
                exclusiveWorkerIds.add(lease.workerId());
            }
        }

        for (String workerId : exclusiveWorkerIds) {
            resourceReleaser.releaseAttemptLockIfExclusive(task, workerId,
                    "ON_TASK_TERMINAL", "TaskResourceReleaseListener", "task reached terminal");
        }
    }

    public void onTaskWorkAttemptClosed(Task task, TaskWorkAttemptClosedEvent event) {
        if (task == null || event == null) {
            return;
        }
        String workerId = event.workerId();
        if (workerId == null || workerId.isBlank()) {
            return;
        }
        workerManager.recordWorkFinal(workerId, task.getTid());
        if (hasOtherActiveAttempts(task.getTid(), workerId)) {
            return;
        }

        resourceReleaser.releaseAttemptLockIfExclusive(task, workerId,
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED", "TaskResourceReleaseListener", "worker has no in-flight messages");

        AssignmentRefillDecision refillDecision = refillPolicy.decide(new AssignmentRefillRequest(
                task,
                () -> maintenancePort.hasDispatchReadyWork(task.getTid())
        ));
        if (refillDecision.shouldRequestDispatch()) {
            maintenancePort.requestTaskDispatch(task);
        }
    }

    private boolean hasOtherActiveAttempts(String taskId, String workerId) {
        return maintenancePort.hasActiveWorkForWorker(taskId, workerId);
    }
}

