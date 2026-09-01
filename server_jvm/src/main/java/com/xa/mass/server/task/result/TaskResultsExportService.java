package com.xa.mass.server.task.result;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationAlreadyRunningException;
import com.xa.mass.server.operation.OperationGuard;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class TaskResultsExportService {

    static final String OPERATION_NAMESPACE = "task-results-export";

    private final TaskResourceCatalog taskCatalog;
    private final TaskScoreBandCore taskScores;
    private final TaskRuntime taskRuntime;
    private final ObjectMapper mapper;
    private final Path temporaryDirectory;
    private final OperationGuard operations;

    @Autowired
    public TaskResultsExportService(
            TaskResourceCatalog taskCatalog,
            TaskScoreBandCore taskScores,
            TaskRuntime taskRuntime,
            ObjectMapper mapper,
            OperationGuard operations
    ) {
        this(
                taskCatalog,
                taskScores,
                taskRuntime,
                mapper,
                Path.of(System.getProperty("java.io.tmpdir")),
                operations
        );
    }

    TaskResultsExportService(
            TaskResourceCatalog taskCatalog,
            TaskScoreBandCore taskScores,
            TaskRuntime taskRuntime,
            ObjectMapper mapper,
            Path temporaryDirectory,
            OperationGuard operations
    ) {
        this.taskCatalog = java.util.Objects.requireNonNull(
                taskCatalog,
                "taskCatalog"
        );
        this.taskScores = java.util.Objects.requireNonNull(
                taskScores,
                "taskScores"
        );
        this.taskRuntime = java.util.Objects.requireNonNull(
                taskRuntime,
                "taskRuntime"
        );
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.temporaryDirectory = java.util.Objects.requireNonNull(
                temporaryDirectory,
                "temporaryDirectory"
        );
        this.operations = java.util.Objects.requireNonNull(
                operations,
                "operations"
        );
    }

    public TaskResultsExport export(String taskId) {
        validateFiniteTask(taskId);
        requireTerminal(taskId);
        try {
            return operations.execute(
                    OPERATION_NAMESPACE,
                    taskId,
                    () -> new TaskResultsExport(writeExport(taskId))
            );
        } catch (OperationAlreadyRunningException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    "taskResultsExport.export",
                    null,
                    error
            );
        }
    }

    public void transferAndDelete(
            Path file,
            OutputStream output
    ) throws IOException {
        try {
            Files.copy(file, output);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void validateFiniteTask(String taskId) {
        try {
            TaskDescriptor descriptor = taskCatalog
                    .loadTaskAllocationDescriptors(List.of(taskId))
                    .get(taskId);
            if (descriptor == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_NOT_FOUND,
                        "taskResultsExport.validate",
                        null,
                        null
                );
            }
            if (descriptor.workerAllocationMechanism()
                    != WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                    || descriptor.idleDisposition()
                    != TaskIdleDisposition.CLOSE_WHEN_IDLE) {
                throw new ServerException(
                        ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED,
                        "taskResultsExport.validate",
                        "Task does not support Result export",
                        null
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("validate", error);
        }
    }

    private void requireTerminal(String taskId) {
        try {
            var state = taskScores.getScoreStates(List.of(taskId))
                    .get(taskId);
            if (state == null) {
                throw new IllegalStateException(
                        "Task score was not available"
                );
            }
            if (state.band() != TaskScoreBand.TERMINAL) {
                throw new ServerException(
                        ServerErrorCode.TASK_RESULTS_NOT_READY,
                        "taskResultsExport.requireTerminal",
                        null,
                        null
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable("requireTerminal", error);
        }
    }

    private Path writeExport(String taskId) {
        Path file = null;
        try {
            file = Files.createTempFile(
                    temporaryDirectory,
                    "xa-mass-task-results-",
                    ".jsonl"
            );
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                scanResults(taskId, writer);
            }
            return file;
        } catch (IOException | RuntimeException error) {
            deleteBestEffort(file);
            throw unavailable("write", error);
        }
    }

    private void scanResults(
            String taskId,
            BufferedWriter writer
    ) throws IOException {
        String cursor = "0";
        Set<String> visitedCursors = new HashSet<>();
        Set<String> writtenMessageIds = new HashSet<>();
        do {
            if (!visitedCursors.add(cursor)) {
                throw new IllegalStateException(
                        "Task Result scan cursor repeated"
                );
            }
            var page = taskRuntime.scanTaskItemResults(
                    taskId,
                    cursor,
                    TaskRuntime.MAX_RESULT_SCAN_COUNT_HINT
            );
            for (var entry : page.results().entrySet()) {
                if (entry.getValue().succeeded()
                        && writtenMessageIds.add(entry.getKey())) {
                    writer.write(mapper.writeValueAsString(
                            new ExportRow(
                                    entry.getKey(),
                                    java.util.Objects.requireNonNull(
                                            entry.getValue()
                                                    .opaqueResultPayload()
                                    )
                            )
                    ));
                    writer.newLine();
                }
            }
            cursor = page.nextCursor();
        } while (!"0".equals(cursor));
    }

    private static ServerException unavailable(
            String method,
            Throwable cause
    ) {
        return new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                "taskResultsExport." + method,
                null,
                cause
        );
    }

    private static void deleteBestEffort(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The request has already failed; no payload is logged here.
        }
    }

    public record TaskResultsExport(Path file) {
        public TaskResultsExport {
            java.util.Objects.requireNonNull(file, "file");
        }
    }

    private record ExportRow(
            String messageId,
            String opaqueResultPayload
    ) {
    }
}
