package com.xa.mass.server.task.history;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreCounts;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskView;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemStateResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectTaskQueryService;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.operation.OperationAlreadyRunningException;
import com.xa.mass.server.task.TaskDataService.ResultEntry;
import com.xa.mass.server.task.TaskDataService.ResultPreview;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.result.TaskResultRecord;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Explicit Storage reads. No Runtime or live Project/Catalog lookup is needed after final purge. */
@Service
public final class TaskHistoryService {
    private final TaskHistoryStore store;
    private final String scope;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    private final OperationGuard operations;

    @Autowired
    public TaskHistoryService(TaskHistoryStore store, RedisKeyspace keyspace, ObjectMapper mapper, OperationGuard operations) {
        this(store, keyspace, mapper, operations, System::currentTimeMillis);
    }
    public TaskHistoryService(TaskHistoryStore store, RedisKeyspace keyspace, ObjectMapper mapper, OperationGuard operations, LongSupplier clock) {
        this.store = store; this.scope = keyspace.base(); this.mapper = mapper; this.clock = clock; this.operations = operations;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "TaskHistoryEntry")
    public record Entry(String historyId, String taskId, @Nullable Long createdAtMillis, TaskView task, String scoreBand,
            long terminatedAtMillis, long frozenAtMillis, long publishedAtMillis, long expiresAtMillis,
            TaskItemScoreCounts counts, long itemRows, long resultRows) { }
    @io.swagger.v3.oas.annotations.media.Schema(name = "TaskHistoryItem")
    public record Item(String messageId, @Nullable TaskItem item, @Nullable TaskItemStateResponse state,
            @Nullable TaskItemResultResponse result) { }
    public record Export(Path file) { }

    public TaskHistoryStore.Page<Entry> list(TaskCaller caller, @Nullable String projectId, @Nullable String userId,
            @Nullable String taskId, @Nullable String cursor, int limit) {
        limit(limit);
        if (projectId == null) caller.requireAdmin(); else caller.requireProject(projectId);
        userId = caller.selectUser(userId);
        try {
            var page = store.list(scope, projectId, userId, taskId, cursor, limit, clock.getAsLong());
            // SQL predicates filter before LIMIT; retain the same descriptor check as every known-ID read.
            return new TaskHistoryStore.Page<>(page.entries().stream().map(saved -> {
                authorize(caller, saved); return entry(saved);
            }).toList(), page.nextCursor());
        } catch (IllegalArgumentException invalid) { throw invalid(invalid); }
        catch (Exception failure) { throw unavailable(failure); }
    }

    public Entry get(TaskCaller caller, String historyId) { return entry(require(caller, historyId)); }

    public com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor requireInput(TaskCaller caller, String historyId, String projectId, String inputOwner) {
        caller.requireProject(projectId);
        var saved = require(caller, historyId);
        var task = saved.snapshot().task();
        caller.requireInput(task, inputOwner);
        var attribution = TaskAttribution.read(task.metadata());
        if (attribution == null || !projectId.equals(attribution.projectId()))
            throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskHistory.input", null, null);
        return task;
    }

    public TaskHistoryStore.Saved require(TaskCaller caller, String historyId) {
        try {
            var saved = store.get(scope, historyId, clock.getAsLong());
            authorize(caller, saved); return saved;
        } catch (Exception failure) { throw unavailable(failure); }
    }

    public TaskHistoryStore.Page<Item> items(TaskCaller caller, String historyId, @Nullable String cursor, int limit) {
        limit(limit);
        try {
            var page = store.rows(scope, historyId, cursor, limit, false, clock.getAsLong(), saved -> authorize(caller, saved));
            return new TaskHistoryStore.Page<>(page.entries().stream().map(row -> new Item(row.messageId(), row.item(),
                    row.state() == null ? null : new TaskItemStateResponse(row.state().band().wireValue(), row.state().tag(), row.state().timeMillis(), null),
                    row.result() == null ? null : TaskItemResultResponse.from(row.result()))).toList(), page.nextCursor());
        } catch (IllegalArgumentException invalid) { throw invalid(invalid); }
        catch (Exception failure) { throw unavailable(failure); }
    }

    public ResultPreview preview(TaskCaller caller, String historyId) {
        try {
            var page = store.rows(scope, historyId, null, 100, true, clock.getAsLong(), saved -> authorize(caller, saved));
            return new ResultPreview(page.entries().stream().map(row -> new ResultEntry(row.messageId(), row.item(),
                    TaskItemResultResponse.from(row.result()))).toList(), page.nextCursor() != null);
        } catch (Exception failure) { throw unavailable(failure); }
    }

    public void forEachResult(TaskCaller caller, String historyId, Consumer<TaskResultRecord> consumer) {
        try {
            operations.execute("task-history-export", scope + "/" + historyId, () -> {
                try {
                    store.forEachResult(scope, historyId, clock.getAsLong(), saved -> authorize(caller, saved),
                            row -> consumer.accept(new TaskResultRecord(row.messageId(), row.result(), row.item())));
                    return null;
                } catch (Exception failure) { throw unavailable(failure); }
            });
        } catch (OperationAlreadyRunningException busy) {
            throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskHistory.export", "Another history export is in progress", busy);
        } catch (Exception failure) { throw unavailable(failure); }
    }

    public Export export(TaskCaller caller, String historyId) {
        require(caller, historyId); // authorization precedes temporary-file creation
        Path file = null;
        try {
            file = Files.createTempFile("xa-mass-history-", ".jsonl");
            try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                forEachResult(caller, historyId, row -> {
                    if (!row.result().succeeded()) return;
                    try {
                        writer.write(mapper.writeValueAsString(new ExportRow(row.messageId(), row.result().opaqueResultPayload())));
                        writer.newLine();
                    } catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
            }
            return new Export(file);
        } catch (IOException | RuntimeException failure) {
            if (file != null) try { Files.deleteIfExists(file); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw unavailable(failure);
        }
    }

    public void transferAndDelete(Path file, OutputStream output) throws IOException {
        try { Files.copy(file, output); } finally { Files.deleteIfExists(file); }
    }
    private void authorize(TaskCaller caller, TaskHistoryStore.@Nullable Saved saved) {
        if (saved == null) throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, "taskHistory.read", null, null);
        if (!scope.equals(saved.snapshot().scope())) throw unavailable(new IllegalStateException("History scope association is corrupt"));
        caller.requireTask(saved.snapshot().task());
    }
    private static Entry entry(TaskHistoryStore.Saved saved) {
        var data = saved.snapshot();
        var attribution = TaskAttribution.read(data.task().metadata());
        return new Entry(data.historyId(), data.task().taskId(), attribution == null ? null : attribution.createdAtMillis(),
                ProjectTaskQueryService.toView(data.task()), data.scoreBand(), data.terminatedAtMillis(), data.frozenAtMillis(),
                saved.publishedAtMillis(), data.expiresAtMillis(), data.counts(), saved.itemRows(), saved.resultRows());
    }
    private static void limit(int limit) { if (limit < 1 || limit > 100) throw invalid(null); }
    private static ServerException invalid(@Nullable Throwable cause) {
        return new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskHistory.query", null, cause);
    }
    private static ServerException unavailable(Throwable cause) {
        if (cause instanceof ServerException known) return known;
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskHistory.read", null, cause);
    }
    private record ExportRow(String messageId, String opaqueResultPayload) { }
}
