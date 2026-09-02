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

/** Runs the fixed ten-Task loaded operation for one scale-proof phase. */
final class ScaleLoadedOperation {

    static final int TASK_COUNT = 10;
    static final int MAXIMUM_CANDIDATE_WORKERS = 100;
    private static final int APPEND_PAGE_SIZE = 100;
    private static final int RESULT_PAGE_SIZE = 1_000;
    private static final Duration RESULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration FIRST_PROGRESS_LIMIT = Duration.ofSeconds(120);
    private static final Duration MAXIMUM_NO_PROGRESS = Duration.ofSeconds(90);
    private static final String TASK_EVENT = "extension.worker.string.md5";

    private ScaleLoadedOperation() {
    }

    static LoadedOperationResult run(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> activeWorkerIds
    ) {
        List<TaskTracker> tasks = new ArrayList<>();
        int appendBatchCount = 0;
        for (int index = 0; index < TASK_COUNT; index++) {
            TaskTracker task = prepareTask(options, api, index + 1);
            tasks.add(task);
            appendBatchCount += task.appendBatchCount();
        }

        for (TaskTracker task : tasks) {
            api.approveTask(task.taskId());
        }
        long started = System.nanoTime();

        long overallDeadline = started + options.taskResultWait().toNanos();
        long nextNetworkScan = started;
        long lastProgress = started;
        long maximumNoProgressNanos = 0;
        int previousSucceeded = 0;
        int minimumConnected = activeWorkerIds.size();

        while (System.nanoTime() < overallDeadline) {
            long now = System.nanoTime();
            if (now >= nextNetworkScan) {
                int connected = scanNetwork(options, api, activeWorkerIds);
                minimumConnected = Math.min(minimumConnected, connected);
                if (connected < options.minimumRetainedConverged()) {
                    throw new IllegalStateException(
                            "Active Worker connections fell below the loaded "
                                    + "operation threshold"
                    );
                }
                nextNetworkScan = System.nanoTime()
                        + options.scanInterval().toNanos();
            }

            for (TaskTracker task : tasks) {
                task.observe(api, started);
            }
            int succeeded = tasks.stream()
                    .mapToInt(TaskTracker::succeeded)
                    .sum();
            long observedAt = System.nanoTime();
            if (succeeded > previousSucceeded) {
                requireTimelyProgress(
                        previousSucceeded,
                        observedAt - started,
                        observedAt - lastProgress
                );
                maximumNoProgressNanos = Math.max(
                        maximumNoProgressNanos,
                        observedAt - lastProgress
                );
                previousSucceeded = succeeded;
                lastProgress = observedAt;
            }
            appendProgress(options, tasks, elapsedMillis(started, observedAt));

            boolean allExported = exportTerminalTasks(api, tasks, started);
            if (allExported) {
                int finalSucceeded = tasks.stream()
                        .mapToInt(TaskTracker::succeeded)
                        .sum();
                long completedAt = System.nanoTime();
                if (finalSucceeded > previousSucceeded) {
                    requireTimelyProgress(
                            previousSucceeded,
                            completedAt - started,
                            completedAt - lastProgress
                    );
                }
                maximumNoProgressNanos = Math.max(
                        maximumNoProgressNanos,
                        completedAt - lastProgress
                );
                ScaleEvidence.appendTimeline(options.timelineFile(), Map.of(
                        "atEpochMillis", System.currentTimeMillis(),
                        "phase", options.phase().wireValue(),
                        "event", "loaded-operation-completed",
                        "taskCount", TASK_COUNT,
                        "succeededItems", finalSucceeded
                ));
                return new LoadedOperationResult(
                        tasks.stream().map(TaskTracker::progress).toList(),
                        appendBatchCount,
                        Duration.ofNanos(maximumNoProgressNanos).toMillis(),
                        minimumConnected
                );
            }
            if (progressDeadlineExceeded(
                    succeeded,
                    observedAt - started,
                    observedAt - lastProgress
            )) {
                String failure = succeeded == 0
                        ? "Loaded operation produced no successful Result within "
                                + FIRST_PROGRESS_LIMIT.toSeconds() + " seconds"
                        : "Loaded operation made no successful progress for "
                                + MAXIMUM_NO_PROGRESS.toSeconds() + " seconds";
                throw new IllegalStateException(failure);
            }
            sleep(RESULT_POLL_INTERVAL);
        }
        throw new IllegalStateException(
                "Loaded operation did not complete within its time budget"
        );
    }

    static boolean progressDeadlineExceeded(
            int previouslySucceeded,
            long elapsedNanos,
            long noProgressNanos
    ) {
        return previouslySucceeded == 0
                ? elapsedNanos >= FIRST_PROGRESS_LIMIT.toNanos()
                : noProgressNanos >= MAXIMUM_NO_PROGRESS.toNanos();
    }

    private static void requireTimelyProgress(
            int previousSucceeded,
            long elapsedNanos,
            long noProgressNanos
    ) {
        if (!progressDeadlineExceeded(
                previousSucceeded,
                elapsedNanos,
                noProgressNanos
        )) {
            return;
        }
        if (previousSucceeded == 0) {
            throw new IllegalStateException(
                    "Loaded operation first observed a successful Result after "
                            + FIRST_PROGRESS_LIMIT.toSeconds() + " seconds"
            );
        }
        throw new IllegalStateException(
                "Loaded operation observed a success gap longer than "
                        + MAXIMUM_NO_PROGRESS.toSeconds() + " seconds"
        );
    }

    private static TaskTracker prepareTask(
            ScaleOptions options,
            ScaleApiClient api,
            int ordinal
    ) {
        String label = String.format("task-%02d", ordinal);
        String taskId = api.createTask(options.workerGroupId());
        List<TaskItem> items = new ArrayList<>();
        List<String> messageIds = new ArrayList<>();
        for (int index = 0;
                index < options.workloadItemsPerTask();
                index++) {
            String messageId = "scale-"
                    + options.phase().wireValue()
                    + "-" + label
                    + "-" + index + "-" + UUID.randomUUID();
            messageIds.add(messageId);
            items.add(new TaskItem(
                    messageId,
                    TASK_EVENT,
                    Map.of("value", label + "-item-" + index)
            ));
        }
        int batches = 0;
        for (int offset = 0; offset < items.size(); offset += APPEND_PAGE_SIZE) {
            api.appendItems(
                    taskId,
                    items.subList(
                            offset,
                            Math.min(offset + APPEND_PAGE_SIZE, items.size())
                    )
            );
            batches++;
        }
        return new TaskTracker(label, taskId, messageIds, batches);
    }

    private static boolean exportTerminalTasks(
            ScaleApiClient api,
            List<TaskTracker> tasks,
            long started
    ) {
        List<TaskTracker> pending = tasks.stream()
                .filter(task -> !task.exported())
                .toList();
        if (pending.isEmpty()) {
            return true;
        }
        Map<String, String> scoreBands = api.previewTaskScoreBands(
                pending.stream().map(TaskTracker::taskId).toList()
        );
        for (TaskTracker task : pending) {
            if ("terminal".equals(scoreBands.get(task.taskId()))) {
                task.verifyExport(api, elapsedMillis(started, System.nanoTime()));
            }
        }
        return tasks.stream().allMatch(TaskTracker::exported);
    }

    private static int scanNetwork(
            ScaleOptions options,
            ScaleApiClient api,
            List<String> workerIds
    ) {
        int connected = 0;
        for (int offset = 0;
                offset < workerIds.size();
                offset += APPEND_PAGE_SIZE) {
            List<String> chunk = workerIds.subList(
                    offset,
                    Math.min(offset + APPEND_PAGE_SIZE, workerIds.size())
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
                "stage", "loaded-operation",
                "activeWorkers", workerIds.size(),
                "connectedWorkers", connected
        ));
        return connected;
    }

    private static void appendProgress(
            ScaleOptions options,
            List<TaskTracker> tasks,
            long elapsedMillis
    ) {
        List<Map<String, Object>> progress = new ArrayList<>();
        for (TaskTracker task : tasks) {
            TaskProgress state = task.progress();
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("label", state.label());
            value.put("taskId", state.taskId());
            value.put("succeeded", state.succeeded());
            value.put("failed", state.failed());
            value.put("notObserved", state.notObserved());
            value.put("exported", state.exported());
            progress.add(value);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("atEpochMillis", System.currentTimeMillis());
        evidence.put("phase", options.phase().wireValue());
        evidence.put("event", "loaded-operation-progress");
        evidence.put("elapsedMillis", elapsedMillis);
        evidence.put("tasks", progress);
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

    private static final class TaskTracker {

        private final String label;
        private final String taskId;
        private final List<String> expectedMessageIds;
        private final Set<String> expectedMessageIdSet;
        private final Set<String> unresolvedMessageIds;
        private final Map<String, TaskResultStatus> latestStatuses;
        private final int appendBatchCount;
        private long firstSuccessElapsedMillis = -1;
        private long completionElapsedMillis = -1;
        private boolean exported;

        private TaskTracker(
                String label,
                String taskId,
                List<String> expectedMessageIds,
                int appendBatchCount
        ) {
            this.label = label;
            this.taskId = taskId;
            this.expectedMessageIds = List.copyOf(expectedMessageIds);
            this.expectedMessageIdSet = Set.copyOf(expectedMessageIds);
            this.unresolvedMessageIds = new LinkedHashSet<>(expectedMessageIds);
            this.latestStatuses = new LinkedHashMap<>();
            for (String messageId : expectedMessageIds) {
                latestStatuses.put(messageId, TaskResultStatus.NOT_OBSERVED);
            }
            this.appendBatchCount = appendBatchCount;
        }

        void observe(ScaleApiClient api, long started) {
            List<String> unresolved = List.copyOf(unresolvedMessageIds);
            for (int offset = 0;
                    offset < unresolved.size();
                    offset += RESULT_PAGE_SIZE) {
                List<String> page = unresolved.subList(
                        offset,
                        Math.min(offset + RESULT_PAGE_SIZE, unresolved.size())
                );
                Map<String, TaskResultStatus> observed =
                        api.loadResultStatuses(taskId, page);
                for (Map.Entry<String, TaskResultStatus> entry
                        : observed.entrySet()) {
                    latestStatuses.put(entry.getKey(), entry.getValue());
                    if (entry.getValue() == TaskResultStatus.SUCCEEDED) {
                        unresolvedMessageIds.remove(entry.getKey());
                    }
                }
            }
            if (firstSuccessElapsedMillis < 0 && succeeded() > 0) {
                firstSuccessElapsedMillis = elapsedMillis(
                        started,
                        System.nanoTime()
                );
            }
        }

        void verifyExport(ScaleApiClient api, long completionMillis) {
            TaskExport result = api.exportTask(taskId);
            if (!result.ready()) {
                throw new IllegalStateException(
                        "Task " + label + " was terminal but not exportable"
                );
            }
            if (!expectedMessageIdSet.equals(result.messageIds())) {
                throw new IllegalStateException(
                        "Task " + label
                                + " export does not match submitted Items"
                );
            }
            for (String messageId : unresolvedMessageIds) {
                latestStatuses.put(messageId, TaskResultStatus.SUCCEEDED);
            }
            unresolvedMessageIds.clear();
            completionElapsedMillis = completionMillis;
            exported = true;
        }

        int succeeded() {
            return expectedMessageIds.size() - unresolvedMessageIds.size();
        }

        int failed() {
            return count(TaskResultStatus.FAILED);
        }

        int notObserved() {
            return count(TaskResultStatus.NOT_OBSERVED);
        }

        private int count(TaskResultStatus status) {
            return (int) latestStatuses.values().stream()
                    .filter(status::equals)
                    .count();
        }

        String taskId() {
            return taskId;
        }

        int appendBatchCount() {
            return appendBatchCount;
        }

        boolean exported() {
            return exported;
        }

        TaskProgress progress() {
            return new TaskProgress(
                    label,
                    taskId,
                    succeeded(),
                    failed(),
                    notObserved(),
                    firstSuccessElapsedMillis,
                    completionElapsedMillis,
                    exported
            );
        }
    }

    record TaskProgress(
            String label,
            String taskId,
            int succeeded,
            int failed,
            int notObserved,
            long firstSuccessElapsedMillis,
            long completionElapsedMillis,
            boolean exported
    ) {
    }

    record LoadedOperationResult(
            List<TaskProgress> tasks,
            int appendBatchCount,
            long maximumNoProgressMillis,
            int minimumConnected
    ) {
    }
}
