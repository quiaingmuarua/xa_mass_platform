package com.xa.mass.integration.workerscale;

import com.xa.mass.integration.workerscale.ScaleApiClient.TaskExport;
import com.xa.mass.integration.workerscale.ScaleApiClient.TaskItem;
import com.xa.mass.integration.workerscale.ScaleApiClient.TaskResultStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs the two-Task finite workload for one scale-proof phase. */
final class ScaleDualTaskWorkload {

    static final int TASK_COUNT = 2;
    private static final int PAGE_SIZE = 100;
    private static final Duration RESULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_NO_PROGRESS = Duration.ofSeconds(60);
    private static final String TASK_EVENT = "extension.worker.string.md5";

    private ScaleDualTaskWorkload() {
    }

    static WorkloadResult run(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> workerIds
    ) {
        TaskWorkload taskA = prepareTask(options, api, "a");
        TaskWorkload taskB = prepareTask(options, api, "b");

        long started = System.nanoTime();
        api.approveTask(taskA.taskId());
        api.approveTask(taskB.taskId());

        long overallDeadline = started + options.taskResultWait().toNanos();
        long lastProgress = started;
        long maximumNoProgressNanos = 0;
        long nextNetworkScan = started;
        int previousSucceeded = 0;
        int minimumConnected = options.offeredWorkers();
        TaskProgress taskAProgress = TaskProgress.empty();
        TaskProgress taskBProgress = TaskProgress.empty();
        boolean taskAExported = false;
        boolean taskBExported = false;

        while (System.nanoTime() < overallDeadline) {
            long now = System.nanoTime();
            if (now >= nextNetworkScan) {
                int connected = scanNetwork(options, api, workerIds);
                minimumConnected = Math.min(minimumConnected, connected);
                if (connected < options.minimumConverged()) {
                    throw new IllegalStateException(
                            "Worker connections fell below the workload "
                                    + "threshold"
                    );
                }
                nextNetworkScan = System.nanoTime()
                        + options.scanInterval().toNanos();
            }

            taskAProgress = observeTaskProgress(
                    api,
                    taskA,
                    taskAProgress,
                    started
            );
            taskBProgress = observeTaskProgress(
                    api,
                    taskB,
                    taskBProgress,
                    started
            );
            int succeeded = Math.addExact(
                    taskAProgress.succeeded(),
                    taskBProgress.succeeded()
            );
            long observedAt = System.nanoTime();
            if (succeeded > previousSucceeded) {
                maximumNoProgressNanos = Math.max(
                        maximumNoProgressNanos,
                        observedAt - lastProgress
                );
                previousSucceeded = succeeded;
                lastProgress = observedAt;
            }
            appendProgress(
                    options,
                    taskAProgress,
                    taskBProgress,
                    elapsedMillis(started, observedAt)
            );

            boolean allResultsObserved = taskAProgress.complete(
                    taskA.expectedMessageIds().size()
            ) && taskBProgress.complete(taskB.expectedMessageIds().size());
            if (hasStalled(allResultsObserved, observedAt - lastProgress)) {
                throw new IllegalStateException(
                        "Dual-Task workload made no successful progress "
                                + "within 60 seconds"
                );
            }

            if ((!taskAExported && taskAProgress.complete(
                    taskA.expectedMessageIds().size()
            )) || (!taskBExported && taskBProgress.complete(
                    taskB.expectedMessageIds().size()
            ))) {
                Map<String, String> scoreBands = api.previewTaskScoreBands(
                        List.of(taskA.taskId(), taskB.taskId())
                );
                if (!taskAExported
                        && "terminal".equals(scoreBands.get(taskA.taskId()))) {
                    taskAProgress = taskAProgress.completedAt(
                            elapsedMillis(started, System.nanoTime())
                    );
                    verifyExport(api, taskA);
                    taskAExported = true;
                }
                if (!taskBExported
                        && "terminal".equals(scoreBands.get(taskB.taskId()))) {
                    taskBProgress = taskBProgress.completedAt(
                            elapsedMillis(started, System.nanoTime())
                    );
                    verifyExport(api, taskB);
                    taskBExported = true;
                }
            }
            if (taskAExported && taskBExported) {
                ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                        "atEpochMillis", System.currentTimeMillis(),
                        "phase", options.phase().wireValue(),
                        "event", "dual-task-workload-completed",
                        "taskASucceeded", taskAProgress.succeeded(),
                        "taskBSucceeded", taskBProgress.succeeded()
                ));
                return new WorkloadResult(
                        taskAProgress,
                        taskBProgress,
                        taskA.appendBatchCount() + taskB.appendBatchCount(),
                        Duration.ofNanos(maximumNoProgressNanos).toMillis(),
                        minimumConnected
                );
            }
            sleep(RESULT_POLL_INTERVAL);
        }
        throw new IllegalStateException(
                "Dual-Task workload did not complete within its time budget"
        );
    }

    static boolean hasStalled(
            boolean allResultsObserved,
            long noProgressNanos
    ) {
        return !allResultsObserved
                && noProgressNanos >= MAXIMUM_NO_PROGRESS.toNanos();
    }

    private static TaskWorkload prepareTask(
            ScaleOptions options,
            ScaleApiClient api,
            String label
    ) {
        String taskId = api.createTask(options.workerGroupId());
        List<TaskItem> items = new ArrayList<>();
        List<String> messageIds = new ArrayList<>();
        for (int index = 0;
                index < options.workloadItemsPerTask();
                index++) {
            String messageId = "scale-"
                    + options.phase().wireValue()
                    + "-task-" + label
                    + "-" + index + "-" + UUID.randomUUID();
            messageIds.add(messageId);
            items.add(new TaskItem(
                    messageId,
                    TASK_EVENT,
                    Map.of("value", "scale-task-" + label + "-" + index)
            ));
        }
        int batches = 0;
        for (int offset = 0; offset < items.size(); offset += PAGE_SIZE) {
            api.appendItems(
                    taskId,
                    items.subList(
                            offset,
                            Math.min(offset + PAGE_SIZE, items.size())
                    )
            );
            batches++;
        }
        return new TaskWorkload(
                label,
                taskId,
                List.copyOf(messageIds),
                batches
        );
    }

    private static TaskProgress observeTaskProgress(
            ScaleApiClient api,
            TaskWorkload workload,
            TaskProgress previous,
            long workloadStarted
    ) {
        int succeeded = 0;
        int failed = 0;
        int notObserved = 0;
        List<String> messageIds = workload.expectedMessageIds();
        for (int offset = 0; offset < messageIds.size(); offset += PAGE_SIZE) {
            Map<String, TaskResultStatus> statuses = api.loadResultStatuses(
                    workload.taskId(),
                    messageIds.subList(
                            offset,
                            Math.min(offset + PAGE_SIZE, messageIds.size())
                    )
            );
            for (TaskResultStatus status : statuses.values()) {
                switch (status) {
                    case SUCCEEDED -> succeeded++;
                    case FAILED -> failed++;
                    case NOT_OBSERVED -> notObserved++;
                }
            }
        }
        long observedAt = System.nanoTime();
        long firstSuccess = previous.firstSuccessElapsedMillis();
        if (firstSuccess < 0 && succeeded > 0) {
            firstSuccess = elapsedMillis(workloadStarted, observedAt);
        }
        return new TaskProgress(
                succeeded,
                failed,
                notObserved,
                firstSuccess,
                previous.completionElapsedMillis()
        );
    }

    private static void verifyExport(
            ScaleApiClient api,
            TaskWorkload workload
    ) {
        TaskExport result = api.exportTask(workload.taskId());
        if (!result.ready()) {
            throw new IllegalStateException(
                    "Task " + workload.label()
                            + " was terminal but not exportable"
            );
        }
        Set<String> expected = new LinkedHashSet<>(
                workload.expectedMessageIds()
        );
        if (!expected.equals(result.messageIds())) {
            throw new IllegalStateException(
                    "Task " + workload.label()
                            + " export does not match submitted Items"
            );
        }
    }

    private static int scanNetwork(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> workerIds
    ) {
        int connected = 0;
        for (int offset = 0; offset < workerIds.size(); offset += PAGE_SIZE) {
            List<String> chunk = workerIds.subList(
                    offset,
                    Math.min(offset + PAGE_SIZE, workerIds.size())
            );
            for (String state : api.observeNetwork(
                    options.endpointManagerId(),
                    chunk
            ).values()) {
                if ("connected".equals(state)) {
                    connected++;
                }
            }
        }
        ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                "atEpochMillis", System.currentTimeMillis(),
                "phase", options.phase().wireValue(),
                "event", "network-scan",
                "stage", "workload",
                "offeredWorkers", workerIds.size(),
                "connectedWorkers", connected
        ));
        return connected;
    }

    private static void appendProgress(
            ScaleOptions options,
            TaskProgress taskA,
            TaskProgress taskB,
            long elapsedMillis
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("atEpochMillis", System.currentTimeMillis());
        evidence.put("phase", options.phase().wireValue());
        evidence.put("event", "dual-task-workload-progress");
        evidence.put("elapsedMillis", elapsedMillis);
        evidence.put("taskASucceeded", taskA.succeeded());
        evidence.put("taskAFailed", taskA.failed());
        evidence.put("taskANotObserved", taskA.notObserved());
        evidence.put("taskBSucceeded", taskB.succeeded());
        evidence.put("taskBFailed", taskB.failed());
        evidence.put("taskBNotObserved", taskB.notObserved());
        ScaleEvidence.appendTimeline(options.timelineFile(), evidence);
    }

    private static long elapsedMillis(long started, long observedAt) {
        return Duration.ofNanos(observedAt - started).toMillis();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Scale proof was interrupted", error);
        }
    }

    private record TaskWorkload(
            String label,
            String taskId,
            List<String> expectedMessageIds,
            int appendBatchCount
    ) {
    }

    record TaskProgress(
            int succeeded,
            int failed,
            int notObserved,
            long firstSuccessElapsedMillis,
            long completionElapsedMillis
    ) {

        static TaskProgress empty() {
            return new TaskProgress(0, 0, 0, -1, -1);
        }

        boolean complete(int expected) {
            return succeeded == expected;
        }

        TaskProgress completedAt(long elapsedMillis) {
            if (completionElapsedMillis >= 0) {
                return this;
            }
            return new TaskProgress(
                    succeeded,
                    failed,
                    notObserved,
                    firstSuccessElapsedMillis,
                    elapsedMillis
            );
        }
    }

    record WorkloadResult(
            TaskProgress taskA,
            TaskProgress taskB,
            int appendBatchCount,
            long maximumNoProgressMillis,
            int minimumConnected
    ) {
    }
}
