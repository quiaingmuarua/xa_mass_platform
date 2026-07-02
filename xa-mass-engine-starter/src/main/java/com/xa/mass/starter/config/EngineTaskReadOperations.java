package com.xa.mass.starter.config;

import com.google.gson.Gson;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
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
        return toTaskDetailSnapshot(getTask(requireTaskId(taskId)));
    }

    @Override
    public List<TaskSummarySnapshot> listTaskSummaries(int offset, int limit) {
        return config.getTaskShellStore().listTasksPaged(offset, limit).stream()
                .map(EngineTaskReadOperations::toTaskSummarySnapshot)
                .toList();
    }

    @Override
    public List<TaskSummarySnapshot> getTaskSummariesByStatus(String status) {
        return config.getTaskShellStore().getTasksByStatus(parseTaskStatus(status)).stream()
                .map(EngineTaskReadOperations::toTaskSummarySnapshot)
                .toList();
    }

    @Override
    public boolean taskExists(String taskId) {
        return getTask(requireTaskId(taskId)) != null;
    }

    @Override
    public TaskStateSnapshot getTaskState(String taskId) {
        Task task = getTask(requireTaskId(taskId));
        return task == null ? null : toTaskStateSnapshot(task);
    }

    @Override
    public TaskAccessSnapshot getTaskAccess(String taskId) {
        Task task = getTask(requireTaskId(taskId));
        return task == null ? null : toTaskAccessSnapshot(task);
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
        return toValidationSnapshot(config.validateTaskState(requireTaskId(taskId)));
    }

    @Override
    public TaskStateResolutionSnapshot resolveTaskState(String taskId) {
        return toResolutionSnapshot(config.resolveTaskState(requireTaskId(taskId)));
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

    private Task getTask(String taskId) {
        return config.getTaskShellStore().getTask(taskId).orElse(null);
    }

    private static TaskStateValidationSnapshot toValidationSnapshot(TaskStateValidationResult result) {
        if (result == null) {
            return null;
        }
        return new TaskStateValidationSnapshot(
                result.isValid(),
                result.isNeedsResolution(),
                enumName(result.getStatus()),
                enumName(result.getTerminalReason()),
                result.getTotalMessages(),
                result.getSuccessMessages(),
                result.getFailedMessages(),
                result.getProcessingMessages(),
                enumName(result.getScope()),
                result.getViolations() == null
                        ? List.of()
                        : result.getViolations().stream().map(Enum::name).toList()
        );
    }

    private static TaskStateResolutionSnapshot toResolutionSnapshot(TaskStateResolutionResult result) {
        if (result == null) {
            return null;
        }
        return new TaskStateResolutionSnapshot(
                enumName(result.getOutcome()),
                enumName(result.getStatus()),
                enumName(result.getTerminalReason()),
                result.getTotalMessages(),
                result.getSuccessMessages(),
                result.getFailedMessages()
        );
    }

    private static TaskStateSnapshot toTaskStateSnapshot(Task task) {
        return new TaskStateSnapshot(
                task.getTid(),
                enumName(task.getStatus()),
                enumName(task.getTerminalReason()),
                enumName(task.getIntakeStatus())
        );
    }

    private static TaskAccessSnapshot toTaskAccessSnapshot(Task task) {
        return new TaskAccessSnapshot(
                task.getTid(),
                task.getProject(),
                copyMap(task.getSharedConfig()),
                enumName(task.getIntakeStatus())
        );
    }

    private static TaskSummarySnapshot toTaskSummarySnapshot(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskSummarySnapshot(
                task.getTid(),
                task.getTaskName(),
                task.getTenantId(),
                task.getProject(),
                task.getUser() == null ? null : task.getUser().getUserId(),
                enumName(task.getContract()),
                enumName(task.getStatus()),
                enumName(task.getTerminalReason()),
                toTaskExecutionOptions(task.getExecutionSpec()),
                task.getTaskSuccessNumber(),
                task.getTaskEligibleNumber(),
                task.getUpdateTime()
        );
    }

    private static TaskDetailSnapshot toTaskDetailSnapshot(Task task) {
        if (task == null) {
            return null;
        }
        return new TaskDetailSnapshot(
                task.getTid(),
                task.getTenantId(),
                task.getTaskName(),
                enumName(task.getContract()),
                task.getProject(),
                enumName(task.getStatus()),
                task.getTaskTargetNumber(),
                task.getTaskEligibleNumber(),
                task.getTaskSuccessNumber(),
                task.getTaskNonSuccessNumber(),
                task.getMinRequiredWorkerCount(),
                task.getPeakAssignedWorkerCount(),
                copyMap(task.getSharedConfig()),
                enumName(task.getHoldReason()),
                toTaskExecutionOptions(task.getExecutionSpec()),
                task.getSourceRef(),
                enumName(task.getIntakeStatus()),
                task.getUser() == null ? null : task.getUser().getUserId(),
                task.getCreateTime(),
                task.getUpdateTime(),
                task.getStartTime(),
                task.getEndTime(),
                enumName(task.getTerminalReason())
        );
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
