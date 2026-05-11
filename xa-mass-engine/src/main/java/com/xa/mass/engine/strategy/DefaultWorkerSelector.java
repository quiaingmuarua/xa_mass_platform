package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default worker selector.
 *
 * <p>Selection priority stays worker-centric. It must not inject routing-country
 * assumptions from {@code worker.workerGroupId}; routing country belongs to rule/workerContext
 * matching instead.
 *
 * <p>This selector reasons only over fields carried on {@link Worker}. Runtime
 * lock ownership is enforced by the active matching strategy rather than by
 * stale state on the worker model itself.
 */
public class DefaultWorkerSelector implements WorkerSelector {

    @Override
    public List<Worker> selectWorkers(Task task, List<Worker> availableWorkers, int requiredCount) {
        return availableWorkers.stream()
                .filter(worker -> isWorkerSuitable(worker, task))
                .sorted(Comparator.comparingDouble(worker -> -getWorkerPriority(worker, task)))
                .limit(requiredCount)
                .collect(Collectors.toList());
    }

    @Override
    public boolean isWorkerSuitable(Worker worker, Task task) {
        if (worker == null || worker.getStatus() == null || worker.getStatus() == com.xa.mass.base.enums.worker.WorkerStatus.EXPIRED) {
            return false;
        }
        String eventCode = TaskSharedConfig.sdkEventCode(task);
        boolean sdkEventTask = eventCode != null && !eventCode.isBlank();
        if (sdkEventTask && !worker.supportsEvent(eventCode)) {
            return false;
        }
        if (!sdkEventTask && !worker.supportsProject(task.getProject())) {
            return false;
        }
        return true;
    }

    @Override
    public double getWorkerPriority(Worker worker, Task task) {
        double priority = 0.0;

        if (worker.getStatus().isAvailable()) {
            priority += 100.0;
        }

        if (worker.getLastHeartbeat() != null) {
            long secondsSinceHeartbeat = java.time.Duration.between(
                    worker.getLastHeartbeat(),
                    java.time.LocalDateTime.now()
            ).getSeconds();

            if (secondsSinceHeartbeat <= 30) {
                priority += (30 - secondsSinceHeartbeat) * 2.0;
            }
        }

        if (worker.getAgentVersion() != null && worker.getAgentVersion().startsWith("1.")) {
            priority += 10.0;
        }

        return priority;
    }
}
