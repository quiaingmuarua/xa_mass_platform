package com.xa.mass.engine.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Rule-evaluation context for worker matching.
 *
 * <p>The routing signal is task-owned input, but the country truth used
 * for matching should come from workerContext/account-facing data rather than worker
 * grouping. Worker group remains exposed only as a diagnostic signal.
 */
public class WorkerMatchContext {
    private final Worker worker;
    private final WorkerContext workerContext;
    private final Task task;
    private final WorkerSchedulingView schedulingView;
    private final Map<String, Object> context;

    public WorkerMatchContext(WorkerSchedulingCandidate candidate, Task task) {
        Objects.requireNonNull(candidate, "candidate");
        this.worker = candidate.getWorker();
        this.workerContext = candidate.getWorkerContext();
        this.task = task;
        this.schedulingView = candidate.getSchedulingView();
        this.context = buildContext();
    }

    private Map<String, Object> buildContext() {
        Map<String, Object> ctx = new HashMap<>();

        putWorkerSchedulingFields(ctx);

        String routingCode = TaskSharedConfig.routingCode(task);
        String taskEventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = TaskSharedConfig.targetWorkerId(task);
        Map<String, String> targetWorkerAttributes = TaskSharedConfig.targetWorkerAttributes(task);
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        java.util.Set<String> routingTags = schedulingView.schedulingRoutingTags();
        boolean workerSchedulingProjectMatchesTaskProject =
                schedulingView.schedulingProject() != null
                        && Objects.equals(schedulingView.schedulingProject(), task.getProject());
        boolean workerSchedulingMatchesRoutingCode =
                taskHasRoutingRequirement && !routingTags.isEmpty() && routingTags.contains(routingCode);

        ctx.put("taskId", task.getTid());
        ctx.put("taskName", task.getTaskName());
        ctx.put("taskProject", task.getProject());
        ctx.put("taskEventCode", taskEventCode);
        ctx.put("taskTargetWorkerId", targetWorkerId);
        ctx.put("taskTargetWorkerAttributes", targetWorkerAttributes);
        ctx.put("taskSharedConfig", task.getSharedConfig());
        ctx.put("routingCode", routingCode);
        ctx.put("taskHasRoutingRequirement", taskHasRoutingRequirement);
        ctx.put("taskStatus", task.getStatus().name());
        ctx.put("taskTargetNumber", task.getTaskTargetNumber());
        ctx.put("batchSize", task.getExecutionSpec().getBatchSize());
        ctx.put("minRequiredWorkerCount", task.getMinRequiredWorkerCount());

        ctx.put("appCount", schedulingView.supportedProjects().size());
        ctx.put("supportsProject", schedulingView.supportsProject(task.getProject()));
        ctx.put("supportsEvent", taskEventCode == null || schedulingView.supportsEvent(taskEventCode));
        ctx.put("matchesTargetWorkerId", targetWorkerId == null || Objects.equals(schedulingView.workerId(), targetWorkerId));
        ctx.put("matchesTargetWorkerAttributes", targetWorkerAttributes.isEmpty()
                || workerAttributesMatch(schedulingView.workerAttributes(), targetWorkerAttributes));
        ctx.put("workerSchedulingProjectMatchesTaskProject", workerSchedulingProjectMatchesTaskProject);
        ctx.put("workerSchedulingMatchesRoutingCode", workerSchedulingMatchesRoutingCode);
        ctx.put("workerContextProjectMatchesTaskProject",
                schedulingView.hasWorkerContext() && workerSchedulingProjectMatchesTaskProject);
        ctx.put("workerContextMatchesRoutingCode", workerSchedulingMatchesRoutingCode);

        return ctx;
    }

    public Worker getWorker() {
        return worker;
    }

    public WorkerContext getWorkerContext() {
        return workerContext;
    }

    public Task getTask() {
        return task;
    }

    public WorkerSchedulingView getSchedulingView() {
        return schedulingView;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    private boolean workerAttributesMatch(Map<String, String> workerAttributes,
                                          Map<String, String> requiredAttributes) {
        if (requiredAttributes == null || requiredAttributes.isEmpty()) {
            return true;
        }
        if (workerAttributes == null || workerAttributes.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, String> entry : requiredAttributes.entrySet()) {
            if (!Objects.equals(workerAttributes.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private void putWorkerSchedulingFields(Map<String, Object> ctx) {
        ctx.put("workerId", schedulingView.workerId());
        ctx.put("workerStatus", schedulingView.workerStatusName());
        ctx.put("transportReachability", schedulingView.reachability().name());
        ctx.put("isTransportReachable", schedulingView.isTransportReachable());
        ctx.put("workerGroupId", schedulingView.workerGroupId());
        ctx.put("workerAttributes", schedulingView.workerAttributes());
        ctx.put("agentVersion", schedulingView.agentVersion());
        ctx.put("supportedProjects", schedulingView.supportedProjects());
        ctx.put("supportedEventCodes", schedulingView.supportedEventCodes());
        ctx.put("isWorkerAvailable", schedulingView.dispatchEnabled() && schedulingView.isTransportReachable());
        ctx.put("isWorkerLocked", schedulingView.workerLocked());
        ctx.put("workerActiveLeaseCount", schedulingView.activeLeaseCount());
        ctx.put("workerReservedCount", schedulingView.reservedCount());
        ctx.put("workerDeclaredCapacity", schedulingView.declaredCapacity());
        ctx.put("workerEstimatedLoadRatio", schedulingView.estimatedLoadRatio());
        ctx.put("currentActiveLeaseCount", schedulingView.activeLeaseCount());
        ctx.put("estimatedLoadRatio", schedulingView.estimatedLoadRatio());

        ctx.put("workerSchedulingResourceId", schedulingView.schedulingResourceId());
        ctx.put("workerSchedulingProject", schedulingView.schedulingProject());
        ctx.put("workerSchedulingRoutingTags", schedulingView.schedulingRoutingTags());
        ctx.put("workerSchedulingAttributes", schedulingView.schedulingAttributes());
        ctx.put("hasWorkerSchedulingResource", schedulingView.hasWorkerContext());
        ctx.put("isWorkerSchedulingResourceAllocatable", schedulingView.schedulingResourceAllocatable());
        ctx.put("isWorkerSchedulingResourceAvailable", schedulingView.schedulingResourceAvailable());
        ctx.put("isWorkerSchedulingResourceUsable", schedulingView.schedulingResourceUsable());
        ctx.put("isWorkerSchedulingResourceReserved", schedulingView.schedulingResourceReserved());
        ctx.put("isWorkerSchedulingResourceOccupied", schedulingView.schedulingResourceOccupied());

        ctx.put("hasWorkerContext", schedulingView.hasWorkerContext());
        ctx.put("workerContextId", schedulingView.workerContextId());
        ctx.put("workerContextProject", schedulingView.workerContextProject());
        ctx.put("workerContextStatus", schedulingView.workerContextStatusName());
        ctx.put("workerContextRoutingTags", schedulingView.workerContextRoutingTags());
        ctx.put("workerContextAttributes", schedulingView.workerContextAttributes());
        ctx.put("isWorkerContextAllocatable", schedulingView.workerContextAllocatable());
        ctx.put("isWorkerContextAvailable", schedulingView.workerContextAvailable());
        ctx.put("isWorkerContextUsable", schedulingView.workerContextUsable());
        ctx.put("isWorkerContextReserved", schedulingView.workerContextReserved());
        ctx.put("isWorkerContextOccupied", schedulingView.workerContextOccupied());
    }

    @Override
    public String toString() {
        return "WorkerMatchContext{" +
                "workerId='" + worker.getWorkerId() + '\'' +
                ", taskId='" + task.getTid() + '\'' +
                ", supportsProject=" + context.get("supportsProject") +
                ", supportsEvent=" + context.get("supportsEvent") +
                ", matchesTargetWorkerId=" + context.get("matchesTargetWorkerId") +
                ", matchesTargetWorkerAttributes=" + context.get("matchesTargetWorkerAttributes") +
                ", workerContextMatchesRoutingCode=" + context.get("workerContextMatchesRoutingCode") +
                '}';
    }
}
