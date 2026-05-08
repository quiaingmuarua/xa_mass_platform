package com.xa.mass.engine.testutil;

import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.model.ProjectRef;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskQueryService;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only materializer for legacy aggregate task-create DTOs after engine
 * removal of the old aggregate create entrypoint.
 */
public final class TaskCreateRequestMaterializer {

    private static final int MAX_INITIAL_INLINE_INPUTS =
            Integer.getInteger("xa.mass.engine.maxInitialInlineInputs", 10_000);
    private static final int MAX_INGEST_BATCH_ITEMS =
            Integer.getInteger("xa.mass.engine.maxIngestBatchItems", 10_000);

    private TaskCreateRequestMaterializer() {
    }

    public static Task materialize(TaskManager taskManager, TaskCreateRequestDto request) {
        Objects.requireNonNull(taskManager, "taskManager");
        validate(request);
        TaskSourceType sourceType = resolveSourceType(request);
        Task task = taskManager.createTaskShell(toShellRequest(request, sourceType));
        List<Map<String, Object>> inputs = request.getInputs() == null ? List.of() : request.getInputs();
        if (!inputs.isEmpty()) {
            taskManager.appendTaskItems(task.getTid(), inputs, request.getDefaultMsgMaxRetryCount());
        }
        if (shouldSeal(sourceType, request.isOpenEnded())) {
            taskManager.sealTask(task.getTid());
        }
        return taskManager.getTask(task.getTid());
    }

    public static Task materialize(TaskCommandService taskCommands,
                                   TaskQueryService taskQueries,
                                   TaskCreateRequestDto request) {
        Objects.requireNonNull(taskCommands, "taskCommands");
        Objects.requireNonNull(taskQueries, "taskQueries");
        validate(request);
        TaskSourceType sourceType = resolveSourceType(request);
        Task task = taskCommands.createTaskShell(toShellRequest(request, sourceType));
        List<Map<String, Object>> inputs = request.getInputs() == null ? List.of() : request.getInputs();
        if (!inputs.isEmpty()) {
            taskCommands.appendTaskItems(task.getTid(), inputs, request.getDefaultMsgMaxRetryCount());
        }
        if (shouldSeal(sourceType, request.isOpenEnded())) {
            taskCommands.sealTask(task.getTid());
        }
        return taskQueries.getTask(task.getTid());
    }

    private static void validate(TaskCreateRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        ProjectRef.require(request.getProject());
        UserRef.requireUserId(request.getUserId());
        TaskSourceType sourceType = resolveSourceType(request);
        List<Map<String, Object>> inputs = request.getInputs() == null ? List.of() : request.getInputs();
        if (sourceType == TaskSourceType.FILE
                && (request.getSourceRef() == null || request.getSourceRef().isBlank())) {
            throw new IllegalArgumentException("sourceRef is required for FILE task sources");
        }
        if (sourceType == TaskSourceType.BATCH && inputs.isEmpty()) {
            throw new IllegalArgumentException("inputs must be a non-empty list");
        }
        if (sourceType == TaskSourceType.FILE && !inputs.isEmpty()) {
            throw new IllegalArgumentException("FILE task sources must be created as a sourceRef shell; ingest work items in batches");
        }
        if (sourceType == TaskSourceType.BATCH && inputs.size() > MAX_INITIAL_INLINE_INPUTS) {
            throw new IllegalArgumentException("BATCH task initial inputs exceed inline create limit: "
                    + inputs.size() + " > " + MAX_INITIAL_INLINE_INPUTS);
        }
        if (sourceType == TaskSourceType.STREAM && inputs.size() > MAX_INGEST_BATCH_ITEMS) {
            throw new IllegalArgumentException("STREAM task initial inputs exceed ingest batch limit: "
                    + inputs.size() + " > " + MAX_INGEST_BATCH_ITEMS);
        }
    }

    private static TaskSourceType resolveSourceType(TaskCreateRequestDto request) {
        if (request.getSourceType() != null) {
            return request.getSourceType();
        }
        return request.isOpenEnded() ? TaskSourceType.STREAM : TaskSourceType.BATCH;
    }

    private static boolean shouldSeal(TaskSourceType sourceType, boolean openEnded) {
        return sourceType == TaskSourceType.BATCH && !openEnded;
    }

    private static TaskShellCreateRequestDto toShellRequest(TaskCreateRequestDto request, TaskSourceType sourceType) {
        TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
        shell.setUserId(request.getUserId());
        shell.setProject(request.getProject());
        shell.setTaskName(request.getTaskName());
        shell.setSharedConfig(request.getSharedConfig());
        shell.setBatchSize(request.getBatchSize());
        shell.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        shell.setSourceType(sourceType);
        shell.setWorkloadClass(request.getWorkloadClass());
        shell.setSourceRef(request.getSourceRef());
        return shell;
    }
}
