package com.xa.mass.engine.listener;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskDispatchWakeupPort;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskWorkAttemptClosedEvent;
import com.xa.mass.engine.assignment.AssignmentRefillDecision;
import com.xa.mass.engine.assignment.AssignmentRefillPolicy;
import com.xa.mass.engine.assignment.AssignmentRefillRequest;
import com.xa.mass.engine.assignment.DefaultAssignmentRefillPolicy;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Releases runtime-only resource occupancy when a task reaches TERMINAL.
 */
public class TaskResourceReleaseListener {

    private final TaskLeaseMaintenancePort leaseMaintenancePort;
    private final TaskDispatchWakeupPort dispatchWakeupPort;
    private final WorkerAdmissionRuntime workerAdmissionRuntime;
    private final TraceEventLogger traceEventLogger;
    private final AssignmentRefillPolicy refillPolicy;
    private final WorkerDispatchResourcePolicy resourcePolicy;
    private final WorkerDispatchResourceReleaser resourceReleaser;

    public TaskResourceReleaseListener(TaskLeaseMaintenancePort leaseMaintenancePort,
                                       TaskDispatchWakeupPort dispatchWakeupPort,
                                       WorkerAdmissionRuntime workerAdmissionRuntime) {
        this(leaseMaintenancePort, dispatchWakeupPort, workerAdmissionRuntime, TraceEventLogger.noop());
    }

    public TaskResourceReleaseListener(TaskLeaseMaintenancePort leaseMaintenancePort,
                                       TaskDispatchWakeupPort dispatchWakeupPort,
                                       WorkerAdmissionRuntime workerAdmissionRuntime,
                                       TraceEventLogger traceEventLogger) {
        this(leaseMaintenancePort, dispatchWakeupPort, workerAdmissionRuntime, traceEventLogger, new DefaultAssignmentRefillPolicy());
    }

    TaskResourceReleaseListener(TaskLeaseMaintenancePort leaseMaintenancePort,
                                TaskDispatchWakeupPort dispatchWakeupPort,
                                WorkerAdmissionRuntime workerAdmissionRuntime,
                                TraceEventLogger traceEventLogger,
                                AssignmentRefillPolicy refillPolicy) {
        this(leaseMaintenancePort, dispatchWakeupPort, workerAdmissionRuntime, traceEventLogger, refillPolicy, new DefaultWorkerDispatchResourcePolicy());
    }

    TaskResourceReleaseListener(TaskLeaseMaintenancePort leaseMaintenancePort,
                                TaskDispatchWakeupPort dispatchWakeupPort,
                                WorkerAdmissionRuntime workerAdmissionRuntime,
                                TraceEventLogger traceEventLogger,
                                AssignmentRefillPolicy refillPolicy,
                                WorkerDispatchResourcePolicy resourcePolicy) {
        this(leaseMaintenancePort, dispatchWakeupPort, workerAdmissionRuntime, traceEventLogger, refillPolicy, resourcePolicy, null);
    }

    public TaskResourceReleaseListener(TaskLeaseMaintenancePort leaseMaintenancePort,
                                       TaskDispatchWakeupPort dispatchWakeupPort,
                                       WorkerAdmissionRuntime workerAdmissionRuntime,
                                       TraceEventLogger traceEventLogger,
                                       AssignmentRefillPolicy refillPolicy,
                                       WorkerDispatchResourcePolicy resourcePolicy,
                                       WorkerDispatchResourceReleaser resourceReleaser) {
        this.leaseMaintenancePort = leaseMaintenancePort;
        this.dispatchWakeupPort = dispatchWakeupPort;
        this.workerAdmissionRuntime = workerAdmissionRuntime;
        this.traceEventLogger = traceEventLogger;
        this.refillPolicy = refillPolicy == null ? new DefaultAssignmentRefillPolicy() : refillPolicy;
        this.resourcePolicy = resourcePolicy == null ? new DefaultWorkerDispatchResourcePolicy() : resourcePolicy;
        this.resourceReleaser = resourceReleaser == null
                ? new WorkerDispatchResourceReleaser(workerAdmissionRuntime, this.resourcePolicy, traceEventLogger)
                : resourceReleaser;
    }

    public void onTaskTerminal(Task task) {
        if (task == null) {
            return;
        }

        List<ActiveLeaseRecord> leases = leaseMaintenancePort.getActiveLeases(task.getTid());
        Set<String> exclusiveWorkerIds = new LinkedHashSet<>();

        for (ActiveLeaseRecord lease : leases) {
            if (lease == null || lease.workerId() == null || lease.workerId().isBlank()) {
                continue;
            }
            workerAdmissionRuntime.recordWorkFinal(admissionTarget(task.getTid(), lease));
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
        workerAdmissionRuntime.recordWorkFinal(admissionTarget(task.getTid(), event));
        if (hasOtherActiveAttempts(task.getTid(), workerId)) {
            return;
        }

        resourceReleaser.releaseAttemptLockIfExclusive(task, workerId,
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED", "TaskResourceReleaseListener", "worker has no in-flight messages");

        AssignmentRefillDecision refillDecision = refillPolicy.decide(new AssignmentRefillRequest(
                task,
                () -> dispatchWakeupPort.hasDispatchReadyWork(task.getTid())
        ));
        if (refillDecision.shouldRequestDispatch()) {
            dispatchWakeupPort.requestTaskDispatch(task);
        }
    }

    private boolean hasOtherActiveAttempts(String taskId, String workerId) {
        return leaseMaintenancePort.hasActiveWorkForWorker(taskId, workerId);
    }

    private static WorkerAdmissionTarget admissionTarget(String taskId, ActiveLeaseRecord lease) {
        return WorkerAdmissionTarget.groupScoped(lease.workerGroupId(), lease.workerId(), taskId);
    }

    private static WorkerAdmissionTarget admissionTarget(String taskId, TaskWorkAttemptClosedEvent event) {
        return WorkerAdmissionTarget.groupScoped(event.workerGroupId(), event.workerId(), taskId);
    }
}
