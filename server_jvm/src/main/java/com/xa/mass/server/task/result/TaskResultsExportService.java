package com.xa.mass.server.task.result;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.TaskDataService.ResultEntry;
import com.xa.mass.server.task.TaskDataService.ResultPreview;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.retention.TaskResultArchive;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Common sequential result view and export across live Redis and frozen archives. */
@Service
public final class TaskResultsExportService {
    private final TaskResourceCatalog taskCatalog;
    private final TaskScoreBandCore taskScores;
    private final TaskRuntime taskRuntime;
    private final ObjectMapper mapper;
    private final Path temporaryDirectory;
    private final OperationGuard operations;
    private final TaskResultArchive archive;

    @Autowired
    public TaskResultsExportService(TaskResourceCatalog catalog, TaskScoreBandCore scores, TaskRuntime runtime,
            ObjectMapper mapper, OperationGuard operations, TaskResultArchive archive) {
        this(catalog, scores, runtime, mapper, Path.of(System.getProperty("java.io.tmpdir")), operations, archive);
    }
    TaskResultsExportService(TaskResourceCatalog catalog, TaskScoreBandCore scores, TaskRuntime runtime,
            ObjectMapper mapper, Path temporaryDirectory, OperationGuard operations, TaskResultArchive archive) {
        this.taskCatalog = catalog; this.taskScores = scores; this.taskRuntime = runtime;
        this.mapper = mapper; this.temporaryDirectory = temporaryDirectory; this.operations = operations; this.archive = archive;
    }

    public TaskResultsExport export(TaskCaller caller, String taskId) {
        return operations.taskMutation(taskId, () -> {
            Path file = null;
            try {
                // Admission precedes even temporary-file creation.
                requireTask(caller, taskId, true);
                requireReadableState(taskId);
                file = Files.createTempFile(temporaryDirectory, "xa-mass-task-results-", ".jsonl");
                try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    forEachResult(caller, taskId, row -> {
                        if (!row.result().succeeded()) return;
                        try {
                            writer.write(mapper.writeValueAsString(new ExportRow(row.messageId(), row.result().opaqueResultPayload())));
                            writer.newLine();
                        } catch (IOException failure) { throw new UncheckedIOException(failure); }
                    });
                }
                return new TaskResultsExport(file);
            } catch (IOException | RuntimeException failure) {
                deleteBestEffort(file);
                if (failure instanceof ServerException known) throw known;
                throw unavailable("export", failure);
            }
        });
    }

    /** Business CSV uses exactly the same source and original Item association. */
    public void forEachResult(TaskCaller caller, String taskId, Consumer<TaskResultRecord> consumer) {
        operations.taskMutation(taskId, () -> {
            requireTask(caller, taskId, true);
            var before = requireReadableState(taskId);
            try {
                if (before.phase() == TaskDataPhase.RELEASED) archive.forEach(taskId, consumer);
                else scanLive(taskId, consumer);
                requireUnchanged(taskId, before);
                return null;
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof ServerException known) throw known;
                throw unavailable("read", failure);
            }
        });
    }

    public ResultPreview preview(TaskCaller caller, String taskId) {
        return operations.taskMutation(taskId, () -> {
            requireTask(caller, taskId, false);
            var before = requireReadableState(taskId);
            try {
                List<TaskResultRecord> rows;
                boolean truncated;
                if (before.phase() == TaskDataPhase.RELEASED) {
                    rows = archive.preview(taskId, 101);
                    truncated = rows.size() > 100;
                } else {
                    var page = taskRuntime.scanTaskItemResults(taskId, "0", 100);
                    var ids = page.results().keySet().stream().limit(100).toList();
                    var items = ids.isEmpty() ? Map.<String, TaskItem>of() : taskRuntime.loadTaskItems(taskId, ids);
                    rows = ids.stream().map(id -> new TaskResultRecord(id, page.results().get(id), items.get(id))).toList();
                    truncated = !page.nextCursor().equals("0") || page.results().size() > 100;
                }
                requireUnchanged(taskId, before);
                return new ResultPreview(rows.stream().limit(100).map(row -> new ResultEntry(row.messageId(), row.item(),
                        TaskItemResultResponse.from(row.result()))).toList(), truncated);
            } catch (IOException | RuntimeException failure) {
                if (failure instanceof ServerException known) throw known;
                throw unavailable("preview", failure);
            }
        });
    }

    private void scanLive(String taskId, Consumer<TaskResultRecord> consumer) {
        String cursor = "0";
        var visited = new HashSet<String>();
        var seen = new HashSet<String>();
        do {
            if (!visited.add(cursor)) throw new IllegalStateException("Task Result scan cursor repeated");
            var page = taskRuntime.scanTaskItemResults(taskId, cursor, TaskRuntime.MAX_RESULT_SCAN_COUNT_HINT);
            var ids = new ArrayList<>(page.results().keySet());
            for (int start = 0; start < ids.size(); start += 100) {
                var batch = ids.subList(start, Math.min(start + 100, ids.size()));
                var items = taskRuntime.loadTaskItems(taskId, batch);
                for (String id : batch) if (seen.add(id)) consumer.accept(new TaskResultRecord(id, page.results().get(id), items.get(id)));
            }
            cursor = page.nextCursor();
        } while (!cursor.equals("0"));
    }

    private void requireTask(TaskCaller caller, String taskId, boolean export) {
        try {
            var task = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
            if (task == null) throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "taskResults.read", null, null);
            caller.requireTask(task);
            if (export) {
                if (task.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE)
                    throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskResults.export", null, null);
                var state = taskScores.getScoreStates(List.of(taskId)).get(taskId);
                if (state == null) throw new IllegalStateException("Task Score is missing");
                if (state.band() != TaskScoreBandCore.TaskScoreBand.TERMINAL)
                    throw new ServerException(ServerErrorCode.TASK_RESULTS_NOT_READY, "taskResults.export", null, null);
            }
        } catch (ServerException known) { throw known;
        } catch (RuntimeException failure) { throw unavailable("admission", failure); }
    }

    private TaskDataState requireReadableState(String taskId) {
        try {
            var state = taskRuntime.loadTaskDataStates(List.of(taskId)).get(taskId);
            if (state == null) throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "taskResults.read", null, null);
            if (state.phase() == TaskDataPhase.PURGING)
                throw new ServerException(ServerErrorCode.TASK_CLEANUP_IN_PROGRESS, "taskResults.read", null, null);
            return state;
        } catch (ServerException known) { throw known;
        } catch (RuntimeException unavailable) { throw unavailable("phase", unavailable); }
    }
    private void requireUnchanged(String taskId, TaskDataState observed) {
        if (!observed.equals(requireReadableState(taskId)))
            throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskResults.read", "Task storage changed during read; refresh the view", null);
    }

    public void transferAndDelete(Path file, OutputStream output) throws IOException {
        try { Files.copy(file, output); } finally { Files.deleteIfExists(file); }
    }
    private static ServerException unavailable(String method, Throwable cause) {
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskResults." + method, null, cause);
    }
    private static void deleteBestEffort(Path file) {
        if (file != null) try { Files.deleteIfExists(file); } catch (IOException ignored) { }
    }
    public record TaskResultsExport(Path file) { }
    private record ExportRow(String messageId, String opaqueResultPayload) { }
}
