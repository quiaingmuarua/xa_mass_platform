package com.xa.mass.starter.config;

import com.google.gson.Gson;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.sdk.TaskReadOperations;
import com.xa.mass.sdk.model.TaskAccessSnapshot;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskDetailSnapshot;
import com.xa.mass.sdk.model.TaskExecutionOptions;
import com.xa.mass.sdk.model.TaskResultArchiveSnapshot;
import com.xa.mass.sdk.model.TaskResultItemSnapshot;
import com.xa.mass.sdk.model.TaskResultWindowSnapshot;
import com.xa.mass.sdk.model.TaskStateResolutionSnapshot;
import com.xa.mass.sdk.model.TaskStateSnapshot;
import com.xa.mass.sdk.model.TaskStateValidationSnapshot;
import com.xa.mass.sdk.model.TaskSummarySnapshot;
import com.xa.mass.sdk.model.TaskWorkFinalSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

final class EngineTaskReadOperations implements TaskReadOperations {

    private static final int ARCHIVE_STREAM_WINDOW =
            Integer.getInteger("xa.mass.sdk.resultArchiveStreamWindow", 1000);
    private static final int STATUS_SCAN_WINDOW =
            Integer.getInteger("xa.mass.sdk.taskReadStatusScanWindow", 1000);
    private static final String RESULT_ARCHIVE_FORMAT = "ndjson";
    private static final String RESULT_ARCHIVE_CONTENT_TYPE = "application/x-ndjson";
    private static final String RESULT_ARCHIVE_CONTENT_ENCODING = "gzip";
    private static final Gson RESULT_JSON = new Gson();

    private final EngineConfig config;

    EngineTaskReadOperations(EngineConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public TaskDetailSnapshot getTaskDetail(String taskId) {
        return config.taskReadViewProjection()
                .get(requireTaskId(taskId))
                .map(this::toTaskDetailSnapshot)
                .orElse(null);
    }

    @Override
    public List<TaskSummarySnapshot> listTaskSummaries(int offset, int limit) {
        return config.taskReadViewProjection().list(offset, limit).stream()
                .map(this::toTaskSummarySnapshot)
                .toList();
    }

    @Override
    public List<TaskSummarySnapshot> getTaskSummariesByStatus(String status) {
        TaskStatus parsedStatus = parseTaskStatus(status);
        return config.taskReadViewProjection().listByStatus(parsedStatus, STATUS_SCAN_WINDOW).stream()
                .map(this::toTaskSummarySnapshot)
                .toList();
    }

    @Override
    public boolean taskExists(String taskId) {
        return config.taskReadViewProjection().get(requireTaskId(taskId)).isPresent();
    }

    @Override
    public TaskStateSnapshot getTaskState(String taskId) {
        return config.taskReadViewProjection()
                .get(requireTaskId(taskId))
                .map(EngineTaskReadOperations::toTaskStateSnapshot)
                .orElse(null);
    }

    @Override
    public TaskAccessSnapshot getTaskAccess(String taskId) {
        return config.taskReadViewProjection()
                .get(requireTaskId(taskId))
                .map(EngineTaskReadOperations::toTaskAccessSnapshot)
                .orElse(null);
    }

    @Override
    public TaskResultWindowSnapshot readTaskResults(String taskId, long afterSeq, int limit) {
        return config.readTaskResults(requireTaskId(taskId), Math.max(0L, afterSeq), Math.max(1, limit));
    }

    @Override
    public Optional<TaskWorkFinalSnapshot> getTaskWorkFinal(String taskId, String messageId) {
        return config.getVisibleTaskResultByMessageId(requireTaskId(taskId), requireMessageId(messageId));
    }

    @Override
    public TaskResultArchiveSnapshot getTaskResultArchiveManifest(String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        TaskDetailSnapshot task = getTaskDetail(normalizedTaskId);
        boolean ready = task != null && "TERMINAL".equalsIgnoreCase(task.getStatus());
        long itemCount = config.countVisibleTaskResults(normalizedTaskId);
        return new TaskResultArchiveSnapshot(
                normalizedTaskId,
                ready,
                RESULT_ARCHIVE_FORMAT,
                RESULT_ARCHIVE_CONTENT_TYPE,
                RESULT_ARCHIVE_CONTENT_ENCODING,
                ready ? itemCount : 0L,
                null,
                null
        );
    }

    @Override
    public void writeTaskResultArchiveContent(String taskId, OutputStream sink) {
        Objects.requireNonNull(sink, "sink");
        String normalizedTaskId = requireTaskId(taskId);
        try {
            GZIPOutputStream gzip = new GZIPOutputStream(sink);
            long afterSeq = 0L;
            while (true) {
                TaskResultWindowSnapshot window = readTaskResults(normalizedTaskId, afterSeq, ARCHIVE_STREAM_WINDOW);
                for (TaskResultItemSnapshot row : window.getItems()) {
                    gzip.write(RESULT_JSON.toJson(row).getBytes(StandardCharsets.UTF_8));
                    gzip.write('\n');
                }
                if (!window.isHasMore()) {
                    gzip.finish();
                    gzip.flush();
                    return;
                }
                afterSeq = window.getNextAfterSeq();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stream task result archive: " + e.getMessage(), e);
        }
    }

    @Override
    public TaskStateValidationSnapshot validateTaskState(String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        Optional<TaskReadViewProjectionStore.TaskReadViewRecord> record =
                config.taskReadViewProjection().get(normalizedTaskId);
        TaskWorkStatsSnapshot stats = getTaskWorkStats(normalizedTaskId);
        if (record.isEmpty()) {
            return new TaskStateValidationSnapshot(
                    false,
                    false,
                    null,
                    null,
                    stats.totalCount(),
                    stats.successCount(),
                    stats.failedCount(),
                    stats.processingCount(),
                    "RUNTIME",
                    List.of("TASK_NOT_FOUND"));
        }
        TaskReadViewProjectionStore.TaskReadViewRecord task = record.get();
        List<String> violations = validationViolations(task, stats);
        return new TaskStateValidationSnapshot(
                violations.isEmpty(),
                needsResolution(task, stats),
                enumName(task.status()),
                enumName(task.terminalReason()),
                stats.totalCount(),
                stats.successCount(),
                stats.failedCount(),
                stats.processingCount(),
                "RUNTIME",
                violations);
    }

    @Override
    public TaskStateResolutionSnapshot resolveTaskState(String taskId) {
        String normalizedTaskId = requireTaskId(taskId);
        Optional<TaskReadViewProjectionStore.TaskReadViewRecord> record =
                config.taskReadViewProjection().get(normalizedTaskId);
        TaskWorkStatsSnapshot stats = getTaskWorkStats(normalizedTaskId);
        if (record.isEmpty()) {
            return new TaskStateResolutionSnapshot("TASK_NOT_FOUND", null, null, 0, 0, 0);
        }
        TaskReadViewProjectionStore.TaskReadViewRecord task = record.get();
        String outcome = task.status() != null && task.status().isFinal()
                ? "ALREADY_FINAL"
                : "NOT_FINALIZED";
        return new TaskStateResolutionSnapshot(
                outcome,
                enumName(task.status()),
                enumName(task.terminalReason()),
                stats.totalCount(),
                stats.successCount(),
                stats.failedCount());
    }

    @Override
    public TaskWorkStatsSnapshot getTaskWorkStats(String taskId) {
        TaskWorkStatsSnapshot snapshot = config.getTaskWorkStats(requireTaskId(taskId));
        return snapshot == null ? TaskWorkStatsSnapshot.EMPTY : snapshot;
    }

    @Override
    public List<TaskActiveLeaseSnapshot> getActiveLeases(String taskId) {
        return List.copyOf(config.getActiveLeases(requireTaskId(taskId)));
    }

    private TaskDetailSnapshot toTaskDetailSnapshot(TaskReadViewProjectionStore.TaskReadViewRecord task) {
        TaskWorkStatsSnapshot stats = getTaskWorkStats(task.taskId());
        int totalCount = boundedInt(Math.max(task.taskTargetNumber(), stats.totalCount()));
        int eligibleCount = boundedInt(Math.max(task.taskEligibleNumber(), stats.totalCount()));
        int successCount = boundedInt(Math.max(task.taskSuccessNumber(), stats.successCount()));
        int failedCount = boundedInt(stats.failedCount() + stats.expiredCount());
        int nonSuccessCount = Math.max(task.taskNonSuccessNumber(), Math.max(eligibleCount - successCount, failedCount));
        return new TaskDetailSnapshot(
                task.taskId(),
                task.tenantId(),
                task.taskName(),
                enumName(task.contract()),
                task.project(),
                enumName(task.status()),
                totalCount,
                eligibleCount,
                successCount,
                nonSuccessCount,
                task.minRequiredWorkerCount(),
                task.peakAssignedWorkerCount(),
                copyMap(task.sharedConfig()),
                enumName(task.holdReason()),
                toTaskExecutionOptions(task.executionSpec()),
                task.sourceRef(),
                enumName(task.intakeStatus()),
                task.userId(),
                task.createTime(),
                task.updateTime(),
                task.startTime(),
                task.endTime(),
                enumName(task.terminalReason())
        );
    }

    private TaskSummarySnapshot toTaskSummarySnapshot(TaskReadViewProjectionStore.TaskReadViewRecord task) {
        TaskWorkStatsSnapshot stats = getTaskWorkStats(task.taskId());
        return new TaskSummarySnapshot(
                task.taskId(),
                task.taskName(),
                task.tenantId(),
                task.project(),
                task.userId(),
                enumName(task.contract()),
                enumName(task.status()),
                enumName(task.terminalReason()),
                toTaskExecutionOptions(task.executionSpec()),
                boundedInt(Math.max(task.taskSuccessNumber(), stats.successCount())),
                boundedInt(Math.max(task.taskEligibleNumber(), stats.totalCount())),
                task.updateTime()
        );
    }

    private static TaskStateSnapshot toTaskStateSnapshot(TaskReadViewProjectionStore.TaskReadViewRecord task) {
        return new TaskStateSnapshot(
                task.taskId(),
                enumName(task.status()),
                enumName(task.terminalReason()),
                enumName(task.intakeStatus())
        );
    }

    private static TaskAccessSnapshot toTaskAccessSnapshot(TaskReadViewProjectionStore.TaskReadViewRecord task) {
        return new TaskAccessSnapshot(
                task.taskId(),
                task.project(),
                copyMap(task.sharedConfig()),
                enumName(task.intakeStatus())
        );
    }

    private static List<String> validationViolations(TaskReadViewProjectionStore.TaskReadViewRecord task,
                                                     TaskWorkStatsSnapshot stats) {
        List<String> violations = new ArrayList<>();
        if (task.taskEligibleNumber() < 0) {
            violations.add("NEGATIVE_ELIGIBLE_COUNT");
        }
        if (task.taskSuccessNumber() < 0) {
            violations.add("NEGATIVE_SUCCESS_COUNT");
        }
        if (task.taskSuccessNumber() > task.taskEligibleNumber()) {
            violations.add("SUCCESS_EXCEEDS_ELIGIBLE");
        }
        if (task.taskNonSuccessNumber() != task.taskEligibleNumber() - task.taskSuccessNumber()) {
            violations.add("NON_SUCCESS_COUNT_MISMATCH");
        }
        if (task.status() == TaskStatus.BLOCKED && task.holdReason() == null) {
            violations.add("BLOCKED_HOLD_REASON_MISSING");
        }
        if (task.status() != TaskStatus.BLOCKED && task.holdReason() != null) {
            violations.add("HOLD_REASON_PRESENT_ON_NON_BLOCKED");
        }
        boolean finalStatus = task.status() != null && task.status().isFinal();
        boolean hasTerminalReason = task.terminalReason() != null;
        if (finalStatus && task.intakeStatus() == com.xa.mass.base.enums.task.TaskIntakeStatus.OPEN) {
            violations.add("TERMINAL_TASK_WITH_OPEN_INTAKE");
        }
        if (finalStatus && !hasTerminalReason) {
            violations.add("TERMINAL_REASON_MISSING");
        }
        if (!finalStatus && hasTerminalReason) {
            violations.add("TERMINAL_REASON_PRESENT_ON_NON_TERMINAL");
        }
        if (finalStatus && hasTerminalReason) {
            addTerminalReasonMismatch(task.terminalReason(), stats, violations);
        }
        return List.copyOf(violations);
    }

    private static void addTerminalReasonMismatch(TaskTerminalReason reason,
                                                  TaskWorkStatsSnapshot stats,
                                                  List<String> violations) {
        switch (reason) {
            case ALL_MESSAGES_SUCCEEDED -> {
                if (!(stats.totalCount() > 0
                        && stats.successCount() == stats.totalCount()
                        && stats.failedCount() == 0
                        && stats.expiredCount() == 0
                        && stats.processingCount() == 0)) {
                    violations.add("TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED");
                }
            }
            case ALL_MESSAGES_FAILED -> {
                if (!(stats.totalCount() > 0
                        && stats.failedCount() + stats.expiredCount() == stats.totalCount()
                        && stats.successCount() == 0
                        && stats.processingCount() == 0)) {
                    violations.add("TERMINAL_REASON_MISMATCH_ALL_FAILED");
                }
            }
            case MIXED_MESSAGE_RESULTS -> {
                boolean mixed = stats.totalCount() > 0
                        && stats.successCount() > 0
                        && stats.failedCount() + stats.expiredCount() > 0
                        && stats.finalCount() == stats.totalCount()
                        && stats.processingCount() == 0;
                if (!mixed) {
                    violations.add("TERMINAL_REASON_MISMATCH_MIXED_RESULTS");
                }
            }
            case MANUAL_CANCELLED, MAX_RUNTIME_REACHED, SUCCESS_RATE_REACHED, RETRY_BUDGET_EXHAUSTED -> {
                // These reasons are allowed to close independently of the current runtime counter shape.
            }
        }
    }

    private static boolean needsResolution(TaskReadViewProjectionStore.TaskReadViewRecord task,
                                           TaskWorkStatsSnapshot stats) {
        return task.status() != null
                && !task.status().isFinal()
                && stats.totalCount() > 0
                && stats.finalCount() == stats.totalCount()
                && stats.processingCount() == 0;
    }

    private static TaskExecutionOptions toTaskExecutionOptions(com.xa.mass.base.model.TaskExecutionSpec spec) {
        TaskExecutionOptions view = new TaskExecutionOptions();
        if (spec == null) {
            return view;
        }
        view.setProfile(enumName(spec.getProfile()));
        view.setWorkloadClass(enumName(spec.getWorkloadClass()));
        view.setBatchSize(spec.getBatchSize());
        view.setMaxRuntimeSeconds(spec.getMaxRuntimeSeconds());
        view.setDefaultMaxRetryCount(spec.getDefaultMaxRetryCount());
        view.setForeground(spec.isForeground());
        return view;
    }

    private static TaskStatus parseTaskStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return TaskStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static int boundedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if (source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                throw new NullPointerException("map key");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static String requireTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        return taskId.trim();
    }

    private static String requireMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        return messageId.trim();
    }
}
