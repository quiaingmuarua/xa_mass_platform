package com.xa.mass.engine.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;

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
    private final WorkerManager workerManager;
    private final Map<String, Object> context;

    public WorkerMatchContext(Worker worker, WorkerContext workerContext, Task task, WorkerManager workerManager) {
        this.worker = worker;
        this.workerContext = workerContext;
        this.task = task;
        this.workerManager = workerManager;
        this.context = buildContext();
    }

    private Map<String, Object> buildContext() {
        Map<String, Object> ctx = new HashMap<>();

        ctx.put("workerId", worker.getWorkerId());
        ctx.put("workerStatus", worker.getStatus().name());
        ctx.put("workerGroupId", worker.getWorkerGroupId());
        ctx.put("workerAttributes", worker.getAttributes());
        ctx.put("agentVersion", worker.getAgentVersion());
        ctx.put("supportedProjects", worker.getSupportedProjects());
        ctx.put("supportedEventCodes", worker.getSupportedEventCodes());
        ctx.put("isWorkerAvailable", worker.isAvailable());
        ctx.put("isWorkerLocked", workerManager.isLocked(worker.getWorkerId()));

        if (workerContext != null) {
            ctx.put("hasWorkerContext", true);
            ctx.put("workerContextId", workerContext.getWorkerContextId());
            ctx.put("workerContextProject", workerContext.getProject());
            ctx.put("workerContextStatus", workerContext.getStatus().name());
            ctx.put("workerContextRoutingTags", workerContext.getRoutingTags());
            ctx.put("workerContextAttributes", workerContext.getAttributes());
            ctx.put("isWorkerContextAllocatable", workerContext.isAllocatable());
            ctx.put("isWorkerContextAvailable", workerContext.isAvailable());
            ctx.put("isWorkerContextUsable", workerContext.isUsable());
            ctx.put("isWorkerContextReserved", workerContext.isReserved());
            ctx.put("isWorkerContextOccupied", workerContext.isOccupied());
        } else {
            ctx.put("hasWorkerContext", false);
            ctx.put("workerContextId", null);
            ctx.put("workerContextProject", null);
            ctx.put("workerContextStatus", null);
            ctx.put("workerContextRoutingTags", java.util.Set.of());
            ctx.put("workerContextAttributes", Map.of());
            ctx.put("isWorkerContextAllocatable", false);
            ctx.put("isWorkerContextAvailable", false);
            ctx.put("isWorkerContextUsable", false);
            ctx.put("isWorkerContextReserved", false);
            ctx.put("isWorkerContextOccupied", false);
        }

        String routingCode = TaskSharedConfig.routingCode(task);
        String taskEventCode = TaskSharedConfig.sdkEventCode(task);
        boolean taskHasRoutingRequirement = routingCode != null && !routingCode.isBlank();
        java.util.Set<String> routingTags = workerContext != null ? workerContext.getRoutingTags() : java.util.Set.of();

        ctx.put("taskId", task.getTid());
        ctx.put("taskName", task.getTaskName());
        ctx.put("taskProject", task.getProject());
        ctx.put("taskEventCode", taskEventCode);
        ctx.put("taskSharedConfig", task.getSharedConfig());
        ctx.put("routingCode", routingCode);
        ctx.put("taskHasRoutingRequirement", taskHasRoutingRequirement);
        ctx.put("taskStatus", task.getStatus().name());
        ctx.put("taskTargetNumber", task.getTaskTargetNumber());
        ctx.put("batchSize", task.getBatchSize());
        ctx.put("minRequiredWorkerCount", task.getMinRequiredWorkerCount());

        ctx.put("appCount", worker.getSupportedProjects() != null ? worker.getSupportedProjects().size() : 0);
        ctx.put("supportsProject", worker.supportsProject(task.getProject()));
        ctx.put("supportsEvent", taskEventCode == null || worker.supportsEvent(taskEventCode));
        ctx.put("workerContextProjectMatchesTaskProject",
                workerContext != null && Objects.equals(workerContext.getProject(), task.getProject()));
        ctx.put("workerContextMatchesRoutingCode",
                taskHasRoutingRequirement && !routingTags.isEmpty() && routingTags.contains(routingCode));

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

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "WorkerMatchContext{" +
                "workerId='" + worker.getWorkerId() + '\'' +
                ", taskId='" + task.getTid() + '\'' +
                ", supportsProject=" + context.get("supportsProject") +
                ", supportsEvent=" + context.get("supportsEvent") +
                ", workerContextMatchesRoutingCode=" + context.get("workerContextMatchesRoutingCode") +
                '}';
    }
}
