package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime
        .WorkerCommandAppendStatus;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

final class TaskDispatchPolicy {

    private final TaskScoreBandCore taskScore;
    private final WorkerCommandRuntime commandRuntime;
    private final TaskItemScoreBandCore itemScore;
    private final TaskItemDispatcher itemDispatcher;
    private final LongSupplier currentTimeMillis;

    TaskDispatchPolicy(
            TaskScoreBandCore taskScore,
            WorkerCommandRuntime commandRuntime,
            TaskItemScoreBandCore itemScore,
            TaskItemDispatcher itemDispatcher
    ) {
        this(
                taskScore,
                commandRuntime,
                itemScore,
                itemDispatcher,
                System::currentTimeMillis
        );
    }

    TaskDispatchPolicy(
            TaskScoreBandCore taskScore,
            WorkerCommandRuntime commandRuntime,
            TaskItemScoreBandCore itemScore,
            TaskItemDispatcher itemDispatcher,
            LongSupplier currentTimeMillis
    ) {
        this.taskScore = Objects.requireNonNull(taskScore, "taskScore");
        this.commandRuntime = Objects.requireNonNull(
                commandRuntime,
                "commandRuntime"
        );
        this.itemScore = Objects.requireNonNull(itemScore, "itemScore");
        this.itemDispatcher = Objects.requireNonNull(
                itemDispatcher,
                "itemDispatcher"
        );
        this.currentTimeMillis = Objects.requireNonNull(
                currentTimeMillis,
                "currentTimeMillis"
        );
    }

    int dispatchTasks(
            List<DueTaskObservation> tasks,
            TaskDispatchConfig config
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(config, "config");
        long dispatchTimeMillis = currentTimeMillis.getAsLong();
        long claimUntilMillis = Math.addExact(
                dispatchTimeMillis,
                config.itemClaimLeaseDurationMillis()
        );
        LinkedHashMap<String, Map<String, DeliveryCommand>> commandsByAdapter =
                new LinkedHashMap<>();
        LinkedHashMap<String, DueTaskObservation> activityRechecks =
                new LinkedHashMap<>();
        Set<String> roundWorkerIds = new LinkedHashSet<>();
        for (DueTaskObservation task : tasks) {
            List<TaskItemDispatcher.ClaimableTaskItem> claimable =
                    itemDispatcher.observeClaimableTaskItems(
                            task.taskId(),
                            config.perTaskDispatchLimit(),
                            dispatchTimeMillis
                    );
            if (claimable.isEmpty()) {
                activityRechecks.put(task.taskId(), task);
                continue;
            }
            try {
                Map<String, Map<String, DeliveryCommand>> taskCommands =
                        itemDispatcher.dispatchTaskItems(
                                task.taskId(),
                                task.descriptor(),
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
                        task.taskId(),
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
            activityRechecks.forEach((taskId, task) -> {
                if (applyActivityRecheck(
                        task,
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

    private boolean applyActivityRecheck(
            DueTaskObservation task,
            boolean hasActiveItems,
            long dispatchTimeMillis
    ) {
        if (hasActiveItems) {
            taskScore.rewriteSameBandTimeMillis(
                    task.taskId(),
                    TaskScoreBand.RUNNING_VISIBLE,
                    dispatchTimeMillis
            );
            return false;
        }
        if (task.descriptor().idleDisposition()
                == TaskIdleDisposition.CLOSE_WHEN_IDLE) {
            taskScore.closeObservedScore(
                    task.taskId(),
                    task.scoreState().score(),
                    TaskScoreBandCore.TERMINAL_SCORE_MAX
            );
            return false;
        }
        var parked = taskScore.parkObservedIdleTask(
                task.taskId(),
                task.scoreState().score()
        );
        return parked.status() == TaskScoreTransitionStatus.TRANSITIONED;
    }

    private void releaseParksWithConcurrentItems(List<String> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        Map<String, Boolean> activeItems = itemScore.hasActiveItems(taskIds);
        taskIds.forEach(taskId -> {
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
}
