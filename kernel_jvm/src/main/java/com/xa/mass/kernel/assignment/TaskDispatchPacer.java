package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime
        .WorkerCommandAppendStatus;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

public final class TaskDispatchPacer {

    private final TaskScoreBandCore taskScore;
    private final TaskResourceCatalog taskCatalog;
    private final WorkerCommandRuntime commandRuntime;
    private final TaskItemScoreBandCore itemScore;
    private final TaskItemDispatcher itemDispatcher;
    private final LongSupplier currentTimeMillis;

    public TaskDispatchPacer(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerCommandRuntime commandRuntime,
            TaskItemScoreBandCore itemScore,
            TaskItemDispatcher itemDispatcher
    ) {
        this(
                taskScore,
                taskCatalog,
                commandRuntime,
                itemScore,
                itemDispatcher,
                System::currentTimeMillis
        );
    }

    TaskDispatchPacer(
            TaskScoreBandCore taskScore,
            TaskResourceCatalog taskCatalog,
            WorkerCommandRuntime commandRuntime,
            TaskItemScoreBandCore itemScore,
            TaskItemDispatcher itemDispatcher,
            LongSupplier currentTimeMillis
    ) {
        this.taskScore = java.util.Objects.requireNonNull(
                taskScore,
                "taskScore"
        );
        this.taskCatalog = java.util.Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
        this.commandRuntime = java.util.Objects.requireNonNull(
                commandRuntime,
                "commandRuntime"
        );
        this.itemScore = java.util.Objects.requireNonNull(
                itemScore,
                "itemScore"
        );
        this.itemDispatcher = java.util.Objects.requireNonNull(
                itemDispatcher,
                "itemDispatcher"
        );
        this.currentTimeMillis = java.util.Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    public int dispatchTasks(TaskDispatchConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        long dispatchTimeMillis = currentTimeMillis.getAsLong();
        long claimUntilMillis = Math.addExact(
                dispatchTimeMillis,
                config.itemClaimLeaseDurationMillis()
        );
        List<ActiveTask> activeTasks = acquireDispatchableTasks(
                config.taskBatchLimit()
        );
        LinkedHashMap<String, Map<String, DeliveryCommand>> commandsByAdapter =
                new LinkedHashMap<>();
        LinkedHashMap<String, ActiveTask> activityRechecks =
                new LinkedHashMap<>();
        Set<String> roundWorkerIds = new LinkedHashSet<>();
        for (ActiveTask active : activeTasks) {
            List<TaskItemDispatcher.ClaimableTaskItem> claimable =
                    itemDispatcher.observeClaimableTaskItems(
                            active.taskId(),
                            config.perTaskDispatchLimit(),
                            dispatchTimeMillis
                    );
            if (claimable.isEmpty()) {
                activityRechecks.put(active.taskId(), active);
                continue;
            }
            try {
                Map<String, Map<String, DeliveryCommand>> taskCommands =
                        itemDispatcher.dispatchTaskItems(
                                active.taskId(),
                                active.descriptor(),
                                claimable,
                                claimUntilMillis,
                                dispatchTimeMillis
                        );
                taskCommands.forEach((adapterId, workerCommands) -> {
                    for (String workerId : workerCommands.keySet()) {
                        if (!roundWorkerIds.add(workerId)) {
                            throw new IllegalStateException(
                                    "one Worker received multiple commands "
                                            + "in one round"
                            );
                        }
                    }
                    commandsByAdapter.computeIfAbsent(
                            adapterId,
                            ignored -> new LinkedHashMap<>()
                    ).putAll(workerCommands);
                });
            } finally {
                taskScore.rewriteSameBandTimeMillis(
                        active.taskId(),
                        TaskScoreBand.RUNNING_VISIBLE,
                        dispatchTimeMillis
                );
            }
        }
        int published = publish(commandsByAdapter);
        if (!activityRechecks.isEmpty()) {
            Map<String, Boolean> activeItems = itemScore.hasActiveItems(
                    List.copyOf(activityRechecks.keySet())
            );
            List<String> parked = new ArrayList<>();
            activityRechecks.forEach((taskId, active) -> {
                if (applyActivityRecheck(
                        active,
                        activeItems.getOrDefault(taskId, false),
                        dispatchTimeMillis
                )) {
                    parked.add(taskId);
                }
            });
            releaseParksWithConcurrentItems(parked);
        }
        return published;
    }

    private List<ActiveTask> acquireDispatchableTasks(int limit) {
        List<String> taskIds = taskScore.acquireDispatchWorkTasks(limit);
        if (taskIds.isEmpty()) {
            return List.of();
        }
        Map<String, TaskScoreState> states = taskScore.getScoreStates(taskIds);
        Map<String, TaskDescriptor> descriptors =
                taskCatalog.loadTaskAllocationDescriptors(taskIds);
        List<ActiveTask> result = new ArrayList<>();
        for (String taskId : taskIds) {
            TaskScoreState state = states.get(taskId);
            TaskDescriptor descriptor = descriptors.get(taskId);
            if (descriptor != null
                    && state != null
                    && state.band() == TaskScoreBand.RUNNING_VISIBLE
                    && state.suffix() != null
                    && state.suffix() == TaskScoreBandCore.MIN_SUFFIX) {
                result.add(new ActiveTask(taskId, descriptor, state));
            }
        }
        return List.copyOf(result);
    }

    private boolean applyActivityRecheck(
            ActiveTask active,
            boolean hasActiveItems,
            long dispatchTimeMillis
    ) {
        if (hasActiveItems) {
            taskScore.rewriteSameBandTimeMillis(
                    active.taskId(),
                    TaskScoreBand.RUNNING_VISIBLE,
                    dispatchTimeMillis
            );
            return false;
        }
        if (active.descriptor().idleDisposition()
                == TaskIdleDisposition.CLOSE_WHEN_IDLE) {
            taskScore.closeObservedScore(
                    active.taskId(),
                    active.state().score(),
                    TaskScoreBandCore.TERMINAL_SCORE_MAX
            );
            return false;
        }
        var parked = taskScore.parkObservedIdleTask(
                active.taskId(),
                active.state().score()
        );
        return parked.status() == TaskScoreTransitionStatus.TRANSITIONED;
    }

    private void releaseParksWithConcurrentItems(List<String> parkedTaskIds) {
        if (parkedTaskIds.isEmpty()) {
            return;
        }
        Map<String, Boolean> activeItems = itemScore.hasActiveItems(
                parkedTaskIds
        );
        parkedTaskIds.forEach(taskId -> {
            if (activeItems.getOrDefault(taskId, false)) {
                taskScore.tryReleaseIdlePark(taskId);
            }
        });
    }

    private int publish(
            Map<String, Map<String, DeliveryCommand>> commandsByAdapter
    ) {
        int published = 0;
        for (Map.Entry<String, Map<String, DeliveryCommand>> adapter
                : commandsByAdapter.entrySet()) {
            Map<String, WorkerCommandAppendStatus> results =
                    commandRuntime.appendWorkerCommands(
                            adapter.getKey(),
                            adapter.getValue()
                    );
            published += (int) results.values().stream()
                    .filter(status -> status == WorkerCommandAppendStatus.APPENDED
                            || status == WorkerCommandAppendStatus.REPLACED)
                    .count();
        }
        return published;
    }

    private record ActiveTask(
            String taskId,
            TaskDescriptor descriptor,
            TaskScoreState state
    ) {
    }
}
