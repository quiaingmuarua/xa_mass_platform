package com.xa.mass.integration.workerloadedrecovery;

import com.xa.mass.integration.workerloadedrecovery.LoadedRecoveryApiClient.TaskExport;
import com.xa.mass.integration.workerloadedrecovery.LoadedRecoveryApiClient.TaskItem;
import com.xa.mass.integration.workerloadedrecovery.LoadedRecoveryApiClient.TaskResultStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Owns one fixed ten-Task workload across a proof mutation. */
final class LoadedRecoveryWorkload {

    static final int TASK_COUNT = 10;
    static final int MAXIMUM_CANDIDATE_WORKERS = 100;
    private static final int APPEND_PAGE_SIZE = 100;
    private static final int RESULT_PAGE_SIZE = 1_000;
    private static final Duration RESULT_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration FIRST_PROGRESS_LIMIT = Duration.ofSeconds(120);
    private static final Duration MAXIMUM_NO_PROGRESS = Duration.ofSeconds(90);
    private static final String TASK_EVENT = "extension.worker.string.md5";

    private LoadedRecoveryWorkload() {
    }

    static InFlightWorkload start(
            LoadedRecoveryOptions options,
            LoadedRecoveryApiClient api,
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
        return new InFlightWorkload(
                options,
                List.copyOf(tasks),
                List.copyOf(activeWorkerIds),
                appendBatchCount
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

    private static TaskTracker prepareTask(
            LoadedRecoveryOptions options,
            LoadedRecoveryApiClient api,
            int ordinal
    ) {
        String label = String.format("task-%02d", ordinal);
        String taskId = api.createTask(options.workerGroupId());
        List<TaskItem> items = new ArrayList<>();
        List<String> messageIds = new ArrayList<>();
        for (int index = 0;
                index < options.workloadItemsPerTask();
                index++) {
            String messageId = "loaded-recovery-"
                    + options.stage().wireValue()
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

    private static long elapsedMillis(long started, long observedAt) {
        return Duration.ofNanos(observedAt - started).toMillis();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Loaded recovery proof was interrupted", error);
        }
    }

    static final class InFlightWorkload {

        private final LoadedRecoveryOptions options;
        private final List<TaskTracker> tasks;
        private final List<String> activeWorkerIds;
        private final int appendBatchCount;
        private final int expectedItems;
        private final long started;
        private final long overallDeadline;

        private long nextNetworkScan;
        private long lastProgress;
        private long maximumNoProgressNanos;
        private int previousSucceeded;
        private int minimumConnected;

        private InFlightWorkload(
                LoadedRecoveryOptions options,
                List<TaskTracker> tasks,
                List<String> activeWorkerIds,
                int appendBatchCount
        ) {
            this.options = options;
            this.tasks = tasks;
            this.activeWorkerIds = activeWorkerIds;
            this.appendBatchCount = appendBatchCount;
            this.expectedItems = Math.multiplyExact(
                    TASK_COUNT,
                    options.workloadItemsPerTask()
            );
            this.started = System.nanoTime();
            this.overallDeadline = started + options.taskResultWait().toNanos();
            this.nextNetworkScan = started;
            this.lastProgress = started;
            this.minimumConnected = activeWorkerIds.size();
        }

        MutationCheckpoint awaitMutationCheckpoint(LoadedRecoveryApiClient api) {
            int maximumSucceeded = expectedItems / 2;
            int minimumUnresolved = expectedItems - maximumSucceeded;
            while (System.nanoTime() < overallDeadline) {
                observeNetworkWhenDue(api);
                observeResults(api, true);
                Map<String, String> scoreBands = requireTaskScoreBands(api);
                boolean anyTerminal = scoreBands.values().stream()
                        .anyMatch("terminal"::equals);
                int succeeded = succeeded();
                int unresolved = expectedItems - succeeded;
                appendProgress("mutation-window");

                if (anyTerminal) {
                    throw new IllegalStateException(
                            "A loaded Task became terminal before mutation"
                    );
                }
                if (succeeded > maximumSucceeded
                        || unresolved < minimumUnresolved) {
                    throw new IllegalStateException(
                            "Loaded recovery workload passed the fixed mutation window"
                    );
                }
                if (succeeded > 0) {
                    MutationCheckpoint checkpoint = new MutationCheckpoint(
                            TASK_COUNT,
                            succeeded,
                            unresolved,
                            elapsedMillis(started, System.nanoTime())
                    );
                    LoadedRecoveryEvidence.appendTimeline(options.timelineFile(), Map.of(
                            "atEpochMillis", System.currentTimeMillis(),
                            "stage", options.stage().wireValue(),
                            "event", "mutation-window-established",
                            "taskCount", checkpoint.taskCount(),
                            "succeededItems", checkpoint.succeededItems(),
                            "unresolvedItems", checkpoint.unresolvedItems()
                    ));
                    return checkpoint;
                }
                requireProgressBudget();
                sleep(RESULT_POLL_INTERVAL);
            }
            throw new IllegalStateException(
                    "Loaded recovery workload did not reach its mutation window"
            );
        }

        RecoverySnapshot observeAfterMutation(
                LoadedRecoveryApiClient api,
                boolean requireBacklog
        ) {
            lastProgress = System.nanoTime();
            observeResults(api, false);
            int succeeded = succeeded();
            int unresolved = expectedItems - succeeded;
            if (requireBacklog && unresolved == 0) {
                throw new IllegalStateException(
                        "Hard restart resumed after the workload completed"
                );
            }
            RecoverySnapshot snapshot = new RecoverySnapshot(
                    succeeded,
                    unresolved,
                    System.currentTimeMillis()
            );
            LoadedRecoveryEvidence.appendTimeline(options.timelineFile(), Map.of(
                    "atEpochMillis", snapshot.observedAtEpochMillis(),
                    "stage", options.stage().wireValue(),
                    "event", "post-mutation-result-snapshot",
                    "succeededItems", snapshot.succeededItems(),
                    "unresolvedItems", snapshot.unresolvedItems()
            ));
            lastProgress = System.nanoTime();
            return snapshot;
        }

        void awaitRetainedConnectionsAfterServerRestart(LoadedRecoveryApiClient api) {
            long convergenceDeadline = Math.min(
                    overallDeadline,
                    System.nanoTime()
                            + options.maximumConvergenceWait().toNanos()
            );
            int latestConnected = 0;
            while (System.nanoTime() < convergenceDeadline) {
                latestConnected = scanNetwork(api);
                observeResults(api, true);
                appendProgress("server-restart-connection-recovery");
                if (latestConnected >= options.minimumRetainedConverged()) {
                    minimumConnected = Math.min(
                            minimumConnected,
                            latestConnected
                    );
                    nextNetworkScan = System.nanoTime()
                            + options.scanInterval().toNanos();
                    return;
                }
                requireProgressBudget();
                sleep(options.scanInterval());
            }
            throw new IllegalStateException(
                    "Active Worker connections did not recover after the "
                            + "planned Server restart (latest="
                            + latestConnected + ")"
            );
        }

        WorkloadResult awaitCompletion(
                LoadedRecoveryApiClient api,
                RecoverySnapshot recoverySnapshot,
                boolean requirePostRecoveryProgress
        ) {
            while (System.nanoTime() < overallDeadline) {
                observeNetworkWhenDue(api);
                observeResults(api, true);
                appendProgress("completion");

                boolean allExported = exportTerminalTasks(api);
                int succeeded = succeeded();
                if (allExported) {
                    boolean postRecoveryProgress = succeeded
                            > recoverySnapshot.succeededItems();
                    if (requirePostRecoveryProgress && !postRecoveryProgress) {
                        throw new IllegalStateException(
                                "Hard restart produced no new successful Result"
                        );
                    }
                    long completedAt = System.nanoTime();
                    maximumNoProgressNanos = Math.max(
                            maximumNoProgressNanos,
                            completedAt - lastProgress
                    );
                    LoadedRecoveryEvidence.appendTimeline(options.timelineFile(), Map.of(
                            "atEpochMillis", System.currentTimeMillis(),
                            "stage", options.stage().wireValue(),
                            "event", "loaded-recovery-workload-completed",
                            "taskCount", TASK_COUNT,
                            "succeededItems", succeeded,
                            "postRecoveryProgress", postRecoveryProgress
                    ));
                    return new WorkloadResult(
                            tasks.stream().map(TaskTracker::progress).toList(),
                            appendBatchCount,
                            Duration.ofNanos(maximumNoProgressNanos).toMillis(),
                            minimumConnected,
                            postRecoveryProgress
                    );
                }
                requireProgressBudget();
                sleep(RESULT_POLL_INTERVAL);
            }
            throw new IllegalStateException(
                    "Loaded recovery workload did not complete within its time budget"
            );
        }

        private void observeResults(LoadedRecoveryApiClient api, boolean enforceBudget) {
            int before = succeeded();
            for (TaskTracker task : tasks) {
                task.observe(api, started);
            }
            int after = succeeded();
            long observedAt = System.nanoTime();
            if (after > before) {
                if (enforceBudget) {
                    requireTimelyProgress(
                            previousSucceeded,
                            observedAt - started,
                            observedAt - lastProgress
                    );
                }
                maximumNoProgressNanos = Math.max(
                        maximumNoProgressNanos,
                        observedAt - lastProgress
                );
                lastProgress = observedAt;
            }
            previousSucceeded = Math.max(previousSucceeded, after);
        }

        private void observeNetworkWhenDue(LoadedRecoveryApiClient api) {
            long now = System.nanoTime();
            if (now < nextNetworkScan) {
                return;
            }
            int connected = scanNetwork(api);
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

        private int scanNetwork(LoadedRecoveryApiClient api) {
            int connected = 0;
            for (int offset = 0;
                    offset < activeWorkerIds.size();
                    offset += APPEND_PAGE_SIZE) {
                List<String> chunk = activeWorkerIds.subList(
                        offset,
                        Math.min(
                                offset + APPEND_PAGE_SIZE,
                                activeWorkerIds.size()
                        )
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
            LoadedRecoveryEvidence.appendTimeline(options.timelineFile(), Map.of(
                    "atEpochMillis", System.currentTimeMillis(),
                    "stage", options.stage().wireValue(),
                    "event", "network-scan",
                    "checkpoint", "loaded-recovery-workload",
                    "activeWorkers", activeWorkerIds.size(),
                    "connectedWorkers", connected
            ));
            return connected;
        }

        private Map<String, String> requireTaskScoreBands(LoadedRecoveryApiClient api) {
            List<String> taskIds = tasks.stream()
                    .map(TaskTracker::taskId)
                    .toList();
            Map<String, String> scoreBands = api.previewTaskScoreBands(taskIds);
            if (!scoreBands.keySet().equals(new LinkedHashSet<>(taskIds))) {
                throw new IllegalStateException(
                        "Task preview did not contain every loaded Task"
                );
            }
            return scoreBands;
        }

        private boolean exportTerminalTasks(LoadedRecoveryApiClient api) {
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
                    task.verifyExport(
                            api,
                            elapsedMillis(started, System.nanoTime())
                    );
                }
            }
            return tasks.stream().allMatch(TaskTracker::exported);
        }

        private void appendProgress(String checkpoint) {
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
            evidence.put("stage", options.stage().wireValue());
            evidence.put("event", "loaded-recovery-workload-progress");
            evidence.put("checkpoint", checkpoint);
            evidence.put(
                    "elapsedMillis",
                    elapsedMillis(started, System.nanoTime())
            );
            evidence.put("tasks", progress);
            LoadedRecoveryEvidence.appendTimeline(options.timelineFile(), evidence);
        }

        private int succeeded() {
            return tasks.stream().mapToInt(TaskTracker::succeeded).sum();
        }

        private void requireProgressBudget() {
            long now = System.nanoTime();
            if (!progressDeadlineExceeded(
                    previousSucceeded,
                    now - started,
                    now - lastProgress
            )) {
                return;
            }
            if (previousSucceeded == 0) {
                throw new IllegalStateException(
                        "Loaded recovery workload produced no successful Result within "
                                + FIRST_PROGRESS_LIMIT.toSeconds() + " seconds"
                );
            }
            throw new IllegalStateException(
                    "Loaded recovery workload made no successful progress for "
                            + MAXIMUM_NO_PROGRESS.toSeconds() + " seconds"
            );
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
                        "Loaded recovery workload first observed a successful Result "
                                + "after " + FIRST_PROGRESS_LIMIT.toSeconds()
                                + " seconds"
                );
            }
            throw new IllegalStateException(
                    "Loaded recovery workload observed a success gap longer than "
                            + MAXIMUM_NO_PROGRESS.toSeconds() + " seconds"
            );
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

        void observe(LoadedRecoveryApiClient api, long started) {
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

        void verifyExport(LoadedRecoveryApiClient api, long completionMillis) {
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

    record MutationCheckpoint(
            int taskCount,
            int succeededItems,
            int unresolvedItems,
            long elapsedMillis
    ) {
    }

    record RecoverySnapshot(
            int succeededItems,
            int unresolvedItems,
            long observedAtEpochMillis
    ) {
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

    record WorkloadResult(
            List<TaskProgress> tasks,
            int appendBatchCount,
            long maximumNoProgressMillis,
            int minimumConnected,
            boolean postRecoveryProgress
    ) {
    }
}
