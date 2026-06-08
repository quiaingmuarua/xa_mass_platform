package com.xa.mass.engine.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Diagnostic and rule-evaluation context for worker matching.
 *
 * <p>The full context keeps runtime evidence for assignment records and traces.
 * The rule context is narrower: it exposes declarative task intent, worker
 * capability, and static worker metadata only. Live admission, load, lease,
 * lock, and reachability evidence belongs to prefilter, ranking, reserve, and
 * diagnostic paths instead of rule evaluation.
 */
public class WorkerMatchContext {
    private final WorkerCandidateRow candidateRow;
    private final Task task;
    private final WorkerSchedulingView schedulingView;
    private final Map<String, Object> context;
    private final Map<String, Object> ruleContext;

    public WorkerMatchContext(WorkerSchedulingCandidate candidate,
                              Task task,
                              TaskDispatchIntent dispatchIntent) {
        this(candidate, task, dispatchIntent, null);
    }

    public WorkerMatchContext(WorkerSchedulingCandidate candidate,
                              Task task,
                              TaskDispatchIntent dispatchIntent,
                              ResolvedTaskSchedulingPolicy taskSchedulingPolicy) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(dispatchIntent, "dispatchIntent");
        this.candidateRow = candidate.getCandidateRow();
        this.task = task;
        this.schedulingView = candidate.getSchedulingView();
        this.context = buildContext(candidate, task, dispatchIntent, taskSchedulingPolicy);
        this.ruleContext = buildRuleContext(candidate, task, dispatchIntent);
    }

    public static Map<String, Object> contextSnapshot(WorkerSchedulingCandidate candidate,
                                                      Task task,
                                                      TaskDispatchIntent dispatchIntent) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(task, "task");
        return buildContext(candidate, task, dispatchIntent, null);
    }

    public static Map<String, Object> contextSnapshot(WorkerSchedulingCandidate candidate,
                                                      Task task,
                                                      TaskDispatchIntent dispatchIntent,
                                                      ResolvedTaskSchedulingPolicy taskSchedulingPolicy) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(task, "task");
        return buildContext(candidate, task, dispatchIntent, taskSchedulingPolicy);
    }

    public static Map<String, Object> ruleContextSnapshot(WorkerSchedulingCandidate candidate,
                                                          Task task,
                                                          TaskDispatchIntent dispatchIntent) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(task, "task");
        return buildRuleContext(candidate, task, dispatchIntent);
    }

    private static Map<String, Object> buildContext(WorkerSchedulingCandidate candidate,
                                                    Task task,
                                                    TaskDispatchIntent dispatchIntent,
                                                    ResolvedTaskSchedulingPolicy taskSchedulingPolicy) {
        WorkerSchedulingView schedulingView = candidate.getSchedulingView();
        Map<String, Object> ctx = new LinkedHashMap<>();

        putWorkerSchedulingFields(ctx, schedulingView);
        putResolvedTaskSchedulingPolicyFields(ctx, taskSchedulingPolicy);

        Objects.requireNonNull(dispatchIntent, "dispatchIntent");
        String routingCode = dispatchIntent.routingCode();
        String taskEventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = dispatchIntent.targetWorkerId();
        Map<String, String> targetWorkerAttributes = dispatchIntent.targetWorkerAttributes();
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        boolean taskUsesEventCapability = taskEventCode != null && !taskEventCode.isBlank();
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
        ctx.put("taskUsesEventCapability", taskUsesEventCapability);
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
        ctx.put("supportsEvent", !taskUsesEventCapability || schedulingView.supportsEvent(taskEventCode));
        ctx.put("matchesTargetWorkerId", targetWorkerId == null || Objects.equals(schedulingView.workerId(), targetWorkerId));
        ctx.put("matchesTargetWorkerAttributes", targetWorkerAttributes.isEmpty()
                || workerAttributesMatch(schedulingView.workerAttributes(), targetWorkerAttributes));
        ctx.put("workerSchedulingProjectMatchesTaskProject", workerSchedulingProjectMatchesTaskProject);
        ctx.put("workerSchedulingMatchesRoutingCode", workerSchedulingMatchesRoutingCode);

        return ctx;
    }

    private static void putResolvedTaskSchedulingPolicyFields(Map<String, Object> ctx,
                                                              ResolvedTaskSchedulingPolicy taskSchedulingPolicy) {
        if (taskSchedulingPolicy == null) {
            return;
        }
        ctx.put("resolvedTaskPolicyPreset", taskSchedulingPolicy.taskPolicyPreset());
        ctx.put("resolvedTaskWorkloadClass", taskSchedulingPolicy.workloadClass().name());
        ctx.put("resolvedDispatchLane", taskSchedulingPolicy.dispatchLane().name());
        ctx.put("resolvedDispatchPriority", taskSchedulingPolicy.dispatchPriority().name());
        ctx.put("resolvedBatchPolicy", taskSchedulingPolicy.batchPolicy().name());
        ctx.put("resolvedLeaseProfile", taskSchedulingPolicy.leaseProfile().name());
        ctx.put("resolvedBackpressureClass", taskSchedulingPolicy.backpressureClass().name());
        ctx.put("resolvedDispatchCadence", taskSchedulingPolicy.dispatchCadence().name());
        ctx.put("resolvedWorkerResourceMode", taskSchedulingPolicy.workerResourceMode().name());
        ctx.put("resolvedIdleCloseEnabled", taskSchedulingPolicy.idleClosePolicy().enabled());
        ctx.put("resolvedIdleCloseRequiresSealed", taskSchedulingPolicy.idleClosePolicy().requireIntakeSealed());
        ctx.put("resolvedClaimSmallPerWorkerCapacityLimit",
                taskSchedulingPolicy.claimPolicy().smallPerWorkerCapacityLimit());
        ctx.put("resolvedClaimShortLeaseSeconds", taskSchedulingPolicy.claimPolicy().shortLeaseSeconds());
        ctx.put("resolvedRetryWorkloadClass", taskSchedulingPolicy.retryPolicy().workloadClass().name());
        ctx.put("resolvedInteractiveAssignmentRetryDelayMillis",
                taskSchedulingPolicy.retryPolicy().interactiveAssignmentRetryDelayMillis());
        ctx.put("resolvedInteractiveWorkRetryDelayMillis",
                taskSchedulingPolicy.retryPolicy().interactiveWorkRetryDelayMillis());
        ctx.put("resolvedBulkWorkRetryDelayMillis", taskSchedulingPolicy.retryPolicy().bulkWorkRetryDelayMillis());
        ctx.put("resolvedExpiredLeaseRetryFromAnyActiveState",
                taskSchedulingPolicy.resultFinalityPolicy().retryExpiredLeaseFromAnyActiveState());
        ctx.put("resolvedExpiredLeaseFinalizesAsFailure",
                taskSchedulingPolicy.resultFinalityPolicy().expiredLeaseFinalizesAsFailure());
        ctx.put("resolvedBackpressureMaxReadyItemsPerTask",
                taskSchedulingPolicy.backpressurePolicy().maxReadyItemsPerTask());
    }

    private static Map<String, Object> buildRuleContext(WorkerSchedulingCandidate candidate,
                                                        Task task,
                                                        TaskDispatchIntent dispatchIntent) {
        WorkerSchedulingView schedulingView = candidate.getSchedulingView();
        Map<String, Object> ctx = new LinkedHashMap<>();

        Objects.requireNonNull(dispatchIntent, "dispatchIntent");
        String routingCode = dispatchIntent.routingCode();
        String taskEventCode = TaskSharedConfig.sdkEventCode(task);
        String targetWorkerId = dispatchIntent.targetWorkerId();
        Map<String, String> targetWorkerAttributes = dispatchIntent.targetWorkerAttributes();
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        boolean taskUsesEventCapability = taskEventCode != null && !taskEventCode.isBlank();
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
        ctx.put("taskUsesEventCapability", taskUsesEventCapability);
        ctx.put("taskTargetWorkerId", targetWorkerId);
        ctx.put("taskTargetWorkerAttributes", targetWorkerAttributes);
        ctx.put("routingCode", routingCode);
        ctx.put("taskHasRoutingRequirement", taskHasRoutingRequirement);

        ctx.put("workerId", schedulingView.workerId());
        ctx.put("workerGroupId", schedulingView.workerGroupId());
        ctx.put("workerAttributes", schedulingView.workerAttributes());
        ctx.put("supportedProjects", schedulingView.supportedProjects());
        ctx.put("supportedEventCodes", schedulingView.supportedEventCodes());

        ctx.put("workerSchedulingResourceId", schedulingView.schedulingResourceId());
        ctx.put("workerSchedulingProject", schedulingView.schedulingProject());
        ctx.put("workerSchedulingRoutingTags", schedulingView.schedulingRoutingTags());
        ctx.put("workerSchedulingAttributes", schedulingView.schedulingAttributes());
        ctx.put("hasWorkerSchedulingResource", schedulingView.schedulingResourceId() != null);

        ctx.put("supportsProject", schedulingView.supportsProject(task.getProject()));
        ctx.put("supportsEvent", !taskUsesEventCapability || schedulingView.supportsEvent(taskEventCode));
        ctx.put("matchesTargetWorkerId", targetWorkerId == null || Objects.equals(schedulingView.workerId(), targetWorkerId));
        ctx.put("matchesTargetWorkerAttributes", targetWorkerAttributes.isEmpty()
                || workerAttributesMatch(schedulingView.workerAttributes(), targetWorkerAttributes));
        ctx.put("workerSchedulingProjectMatchesTaskProject", workerSchedulingProjectMatchesTaskProject);
        ctx.put("workerSchedulingMatchesRoutingCode", workerSchedulingMatchesRoutingCode);

        return ctx;
    }

    public WorkerCandidateRow getCandidateRow() {
        return candidateRow;
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

    public Map<String, Object> getRuleContext() {
        return ruleContext;
    }

    private static boolean workerAttributesMatch(Map<String, String> workerAttributes,
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

    private static void putWorkerSchedulingFields(Map<String, Object> ctx, WorkerSchedulingView schedulingView) {
        ctx.put("workerId", schedulingView.workerId());
        ctx.put("workerStatus", schedulingView.workerStatusName());
        ctx.put("transportReachability", schedulingView.reachability().name());
        ctx.put("isTransportReachable", schedulingView.isTransportReachable());
        ctx.put("workerGroupId", schedulingView.workerGroupId());
        ctx.put("workerAttributes", schedulingView.workerAttributes());
        ctx.put("agentVersion", schedulingView.agentVersion());
        ctx.put("supportedProjects", schedulingView.supportedProjects());
        ctx.put("supportedEventCodes", schedulingView.supportedEventCodes());
        ctx.put("workerReadinessState", schedulingView.readinessState().name());
        ctx.put("workerOccupancyState", schedulingView.occupancyState().name());
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
        ctx.put("hasWorkerSchedulingResource", schedulingView.schedulingResourceId() != null);
        ctx.put("isWorkerSchedulingResourceAllocatable", schedulingView.schedulingResourceAllocatable());
        ctx.put("isWorkerSchedulingResourceAvailable", schedulingView.schedulingResourceAvailable());
        ctx.put("isWorkerSchedulingResourceUsable", schedulingView.schedulingResourceUsable());
        ctx.put("isWorkerSchedulingResourceReserved", schedulingView.schedulingResourceReserved());
        ctx.put("isWorkerSchedulingResourceOccupied", schedulingView.schedulingResourceOccupied());
    }

    @Override
    public String toString() {
        return "WorkerMatchContext{" +
                "workerId='" + candidateRow.workerId() + '\'' +
                ", taskId='" + task.getTid() + '\'' +
                ", supportsProject=" + context.get("supportsProject") +
                ", supportsEvent=" + context.get("supportsEvent") +
                ", matchesTargetWorkerId=" + context.get("matchesTargetWorkerId") +
                ", matchesTargetWorkerAttributes=" + context.get("matchesTargetWorkerAttributes") +
                ", workerSchedulingMatchesRoutingCode=" + context.get("workerSchedulingMatchesRoutingCode") +
                '}';
    }
}
