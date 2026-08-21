package com.xa.mass.server.taskbatch;

import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchInputUploadResponse;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchRunRequest;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchRunResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public final class TaskBatchService {

    private static final String RUN_OPERATION = "taskBatch.run";

    private final TaskBatchFileStore files;
    private final TaskDataService taskData;
    private final TaskCallItemSubmission taskCallSubmission;
    private final WorkerGroupTaskCatalog taskCatalog;
    private final TaskBatchProperties properties;
    private final Clock clock;
    private final AtomicLong lastRunMillis = new AtomicLong(-1);

    TaskBatchService(
            TaskBatchFileStore files,
            TaskDataService taskData,
            TaskCallItemSubmission taskCallSubmission,
            WorkerGroupTaskCatalog taskCatalog,
            TaskBatchProperties properties,
            Clock clock
    ) {
        this.files = files;
        this.taskData = taskData;
        this.taskCallSubmission = taskCallSubmission;
        this.taskCatalog = taskCatalog;
        this.properties = properties;
        this.clock = clock;
    }

    public TaskBatchInputUploadResponse upload(String fileName, byte[] content) {
        TaskBatchFileStore.StoredInput stored = files.upload(fileName, content);
        return new TaskBatchInputUploadResponse(
                stored.fileName(),
                stored.byteCount(),
                stored.lineCount()
        );
    }

    public TaskBatchRunResponse run(TaskBatchRunRequest request) {
        ValidatedRun validated = validate(request);
        List<String> lines = files.readInput(validated.inputFile());
        String taskId = taskId(validated.workerGroupId());
        String runId = "task-batch-" + nextRunMillis();
        long startedNanos = System.nanoTime();

        try (TaskBatchFileStore.OutputSession output = files.output(runId)) {
            if (lines.isEmpty()) {
                String outputFile = output.publish(false);
                return response(
                        runId,
                        validated,
                        0,
                        0,
                        0,
                        0,
                        durationMillis(startedNanos),
                        outputFile,
                        false
                );
            }

            List<Seed> seeds = seeds(runId, validated, lines);
            append(taskId, validated.eventCode(), seeds);
            PollOutcome outcome = poll(
                    taskId,
                    validated,
                    seeds,
                    output,
                    startedNanos
            );
            String outputFile = output.publish(outcome.partial());
            return response(
                    runId,
                    validated,
                    lines.size(),
                    outcome.resultCount(),
                    outcome.remainingCount(),
                    outcome.loadRounds(),
                    durationMillis(startedNanos),
                    outputFile,
                    outcome.partial()
            );
        } catch (ServerException error) {
            if (isTaskBatchError(error.errorCode())) {
                throw error;
            }
            throw unavailable(error);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    public byte[] download(String fileName) {
        return files.readOutput(fileName);
    }

    private PollOutcome poll(
            String taskId,
            ValidatedRun run,
            List<Seed> seeds,
            TaskBatchFileStore.OutputSession output,
            long startedNanos
    ) {
        Map<String, Seed> pending = new LinkedHashMap<>();
        seeds.forEach(seed -> pending.put(seed.messageId(), seed));
        int resultCount = 0;
        int loadRounds = 0;
        long deadlineNanos = saturatedAdd(
                startedNanos,
                run.maximumWaitMillis() * 1_000_000L
        );
        boolean firstRound = true;

        while (!pending.isEmpty()) {
            if (!firstRound && System.nanoTime() >= deadlineNanos) {
                break;
            }
            firstRound = false;
            loadRounds++;
            Map<String, String> loaded = taskData
                    .loadTaskItemSuccessResults(
                            taskId,
                            List.copyOf(pending.keySet())
                    )
                    .results();
            rejectUnexpected(loaded.keySet(), pending.keySet());

            List<TaskBatchFileStore.OutputRow> rows = new ArrayList<>();
            for (Seed seed : List.copyOf(pending.values())) {
                String encodedResult = loaded.get(seed.messageId());
                if (encodedResult == null) {
                    continue;
                }
                Map<String, Object> result;
                try {
                    result = Jsons.parseObject(encodedResult);
                } catch (IllegalArgumentException error) {
                    throw unavailable(error);
                }
                rows.add(new TaskBatchFileStore.OutputRow(
                        run.workerGroupId(),
                        seed.messageId(),
                        run.eventCode(),
                        seed.input(),
                        result
                ));
                pending.remove(seed.messageId());
            }
            if (!rows.isEmpty()) {
                output.accept(rows);
                resultCount += rows.size();
            }
            if (!pending.isEmpty()) {
                waitForNextRound(deadlineNanos);
            }
        }
        return new PollOutcome(
                resultCount,
                pending.size(),
                loadRounds,
                !pending.isEmpty()
        );
    }

    private void append(
            String taskId,
            String eventCode,
            List<Seed> seeds
    ) {
        long createdAtMillis = clock.millis();
        List<TaskItem> items = seeds.stream()
                .map(seed -> new TaskItem(
                        seed.messageId(),
                        eventCode,
                        createdAtMillis,
                        seed.input(),
                        5,
                        null,
                        Map.of()
                ))
                .toList();
        for (int start = 0; start < items.size(); start += TaskCallItemSubmission.MAX_ITEMS) {
            int end = Math.min(
                    items.size(),
                    start + TaskCallItemSubmission.MAX_ITEMS
            );
            TaskCallSubmissionResult submitted = taskCallSubmission.submit(
                    taskId,
                    items.subList(start, end)
            );
            requireSubmitted(submitted, items.subList(start, end));
        }
    }

    private static void requireSubmitted(
            TaskCallSubmissionResult submission,
            List<TaskItem> items
    ) {
        switch (submission.status()) {
            case SUBMITTED -> {
                for (TaskItem item : items) {
                    var result = submission.itemResults().get(item.messageId());
                    if (result == null) {
                        throw unavailable(new IllegalStateException(
                                "Kernel omitted a Task Batch Item result"
                        ));
                    }
                    switch (result.status()) {
                        case APPENDED -> {
                            // Continue validating this bounded chunk.
                        }
                        case NOT_FOUND -> throw batchError(
                                ServerErrorCode.TASK_BATCH_RESOURCE_NOT_FOUND,
                                result.reason()
                        );
                        case INVALID -> throw batchError(
                                ServerErrorCode.TASK_BATCH_INVALID_REQUEST,
                                result.reason()
                        );
                        case RETRYABLE -> throw unavailable(
                                new IllegalStateException(result.reason())
                        );
                    }
                }
            }
            case NOT_FOUND -> throw batchError(
                    ServerErrorCode.TASK_BATCH_RESOURCE_NOT_FOUND,
                    submission.reason()
            );
            case CLOSED, STALE -> throw batchError(
                    ServerErrorCode.TASK_BATCH_CONFLICT,
                    submission.reason()
            );
            case INVALID -> throw batchError(
                    ServerErrorCode.TASK_BATCH_INVALID_REQUEST,
                    submission.reason()
            );
            case RETRYABLE -> throw unavailable(
                    new IllegalStateException(submission.reason())
            );
        }
    }

    private List<Seed> seeds(
            String runId,
            ValidatedRun run,
            List<String> lines
    ) {
        List<Seed> seeds = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            Map<String, Object> input = Map.of(run.payloadKey(), line);
            seeds.add(new Seed(
                    messageId(runId, run.eventCode(), index + 1, line),
                    input
            ));
        }
        return List.copyOf(seeds);
    }

    private String taskId(String workerGroupId) {
        String taskId = taskCatalog.taskIdsByWorkerGroup().get(workerGroupId);
        if (taskId == null) {
            throw new ServerException(
                    ServerErrorCode.TASK_BATCH_RESOURCE_NOT_FOUND,
                    RUN_OPERATION,
                    null,
                    null
            );
        }
        return taskId;
    }

    private ValidatedRun validate(TaskBatchRunRequest request) {
        if (request == null) {
            throw invalid("request is required");
        }
        requireNonBlank(request.workerGroupId(), "workerGroupId");
        requireNonBlank(request.eventCode(), "eventCode");
        requireNonBlank(request.payloadKey(), "payloadKey");
        requireNonBlank(request.inputFile(), "inputFile");
        long maximumWaitMillis = request.maximumWaitMillis() == null
                ? properties.defaultMaximumWaitMillis()
                : request.maximumWaitMillis();
        if (maximumWaitMillis < 1
                || maximumWaitMillis > properties.maximumWaitMillis()) {
            throw invalid("maximumWaitMillis is outside the configured bound");
        }
        return new ValidatedRun(
                request.workerGroupId(),
                request.eventCode(),
                request.payloadKey(),
                request.inputFile(),
                maximumWaitMillis
        );
    }

    private void waitForNextRound(long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return;
        }
        long intervalNanos = properties.resultLoadIntervalMillis()
                * 1_000_000L;
        long waitNanos = Math.min(remainingNanos, intervalNanos);
        long waitMillis = Math.max(1L, (waitNanos + 999_999L) / 1_000_000L);
        try {
            Thread.sleep(waitMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(error);
        }
    }

    private long nextRunMillis() {
        return lastRunMillis.updateAndGet(
                previous -> Math.max(clock.millis(), previous + 1)
        );
    }

    private static String messageId(
            String runId,
            String eventCode,
            int lineNumber,
            String rawLine
    ) {
        CRC32 crc = new CRC32();
        crc.update((lineNumber + "\0" + rawLine)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return runId
                + "-"
                + eventCode
                + "-"
                + String.format("%08x", crc.getValue());
    }

    private static void rejectUnexpected(
            Set<String> loaded,
            Set<String> pending
    ) {
        Set<String> unexpected = new LinkedHashSet<>(loaded);
        unexpected.removeAll(pending);
        if (!unexpected.isEmpty()) {
            throw unavailable(new IllegalStateException(
                    "Task Batch result contains an unexpected messageId"
            ));
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long durationMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static TaskBatchRunResponse response(
            String runId,
            ValidatedRun run,
            int inputCount,
            int resultCount,
            int remainingCount,
            int loadRounds,
            long durationMillis,
            String outputFile,
            boolean partial
    ) {
        return new TaskBatchRunResponse(
                runId,
                run.workerGroupId(),
                run.eventCode(),
                run.payloadKey(),
                partial ? "partial" : "succeeded",
                run.inputFile(),
                inputCount,
                resultCount,
                remainingCount,
                loadRounds,
                durationMillis,
                outputFile
        );
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is invalid");
        }
    }

    private static boolean isTaskBatchError(ServerErrorCode errorCode) {
        return errorCode == ServerErrorCode.TASK_BATCH_INVALID_REQUEST
                || errorCode == ServerErrorCode.TASK_BATCH_RESOURCE_NOT_FOUND
                || errorCode == ServerErrorCode.TASK_BATCH_CONFLICT
                || errorCode == ServerErrorCode.TASK_BATCH_UNAVAILABLE;
    }

    private static ServerException invalid(String message) {
        return new ServerException(
                ServerErrorCode.TASK_BATCH_INVALID_REQUEST,
                RUN_OPERATION,
                message,
                null
        );
    }

    private static ServerException unavailable(Throwable cause) {
        return new ServerException(
                ServerErrorCode.TASK_BATCH_UNAVAILABLE,
                RUN_OPERATION,
                null,
                cause
        );
    }

    private static ServerException batchError(
            ServerErrorCode errorCode,
            String message
    ) {
        return new ServerException(
                errorCode,
                RUN_OPERATION,
                message,
                null
        );
    }

    private record ValidatedRun(
            String workerGroupId,
            String eventCode,
            String payloadKey,
            String inputFile,
            long maximumWaitMillis
    ) {
    }

    private record Seed(String messageId, Map<String, Object> input) {
    }

    private record PollOutcome(
            int resultCount,
            int remainingCount,
            int loadRounds,
            boolean partial
    ) {
    }
}
