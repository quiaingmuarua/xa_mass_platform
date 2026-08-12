package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.javase.JavaWorkerManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ScenarioWorkerIndexUpdater {

    private static final System.Logger LOGGER = System.getLogger(
            ScenarioWorkerIndexUpdater.class.getName()
    );
    private static final int WORKER_START_FAILED = 14004;
    private static final int WORKER_INDEX_FAILED = 14010;
    private static final Duration RETRY_INTERVAL =
            Duration.ofMillis(250);

    private final ScenarioWorkerIndexClient indexClient;

    ScenarioWorkerIndexUpdater(ScenarioWorkerIndexClient indexClient) {
        this.indexClient = Objects.requireNonNull(
                indexClient,
                "indexClient"
        );
    }

    void update(
            ScenarioWorkerGroupConfig group,
            List<ScenarioWorkers.PreparedReplica> workers,
            JavaWorkerManager manager
    ) {
        for (ScenarioWorkers.PreparedReplica worker : workers) {
            update(group, worker, manager);
        }
    }

    private void update(
            ScenarioWorkerGroupConfig group,
            ScenarioWorkers.PreparedReplica worker,
            JavaWorkerManager manager
    ) {
        if (worker.indexedPropertyUpdates().isEmpty()) {
            return;
        }

        long deadline = System.nanoTime()
                + group.connectTimeout().toNanos();
        String workerId = workerId(manager, worker.clientWorkerKey());
        while (workerId == null && sleepBeforeDeadline(
                RETRY_INTERVAL,
                deadline,
                "scenarioWorkers.awaitWorkerIdentity",
                group.workerGroupId()
        )) {
            workerId = workerId(manager, worker.clientWorkerKey());
        }
        if (workerId == null) {
            logFailure(group, null, null, null);
            return;
        }

        String resolvedWorkerId = workerId;
        while (true) {
            Map<String, ScenarioWorkerIndexResult> results;
            try {
                results = indexClient.updateIndexedProperties(
                        group.workerGroupId(),
                        resolvedWorkerId,
                        worker.indexedPropertyUpdates(),
                        group.requestTimeout()
                );
            } catch (RuntimeException error) {
                logFailure(group, resolvedWorkerId, null, error);
                return;
            }
            boolean notFound = results.values().stream()
                    .anyMatch(ScenarioWorkerIndexResult::notFound);
            if (notFound && sleepBeforeDeadline(
                    RETRY_INTERVAL,
                    deadline,
                    "scenarioWorkers.retryIndex",
                    group.workerGroupId()
            )) {
                continue;
            }
            worker.indexedPropertyUpdates().keySet().forEach(field -> {
                ScenarioWorkerIndexResult result = results.get(field);
                if (result == null || !result.accepted()) {
                    logFailure(
                            group,
                            resolvedWorkerId,
                            field,
                            null
                    );
                }
            });
            return;
        }
    }

    private static String workerId(
            JavaWorkerManager manager,
            String clientWorkerKey
    ) {
        return manager.snapshot(clientWorkerKey).workerId();
    }

    private static void logFailure(
            ScenarioWorkerGroupConfig group,
            String workerId,
            String field,
            RuntimeException error
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode=" + WORKER_INDEX_FAILED
                        + " operation=workerPropertyIndex.update"
                        + " workerGroupId=" + group.workerGroupId()
                        + " workerId=" + workerId
                        + (field == null ? "" : " field=" + field)
                        + (error == null
                        ? ""
                        : " errorType="
                                + error.getClass().getSimpleName())
        );
    }

    private static boolean sleepBeforeDeadline(
            Duration maximumDuration,
            long deadline,
            String operation,
            String workerGroupId
    ) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return false;
        }
        try {
            Thread.sleep(Math.max(
                    1L,
                    Duration.ofNanos(Math.min(
                            maximumDuration.toNanos(),
                            remaining
                    )).toMillis()
            ));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ScenarioWorkerAssemblyException(
                    WORKER_START_FAILED,
                    operation,
                    "Interrupted while waiting for WorkerGroup "
                            + workerGroupId,
                    error
            );
        }
        return true;
    }
}
