package com.xa.mass.server.task;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.kernel.assignment.RefillTarget;
import java.util.List;
import java.util.Objects;
import java.util.HexFormat;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

@Service
public final class TaskCreationService {

    private static final String OPERATION = "taskCreation.create";

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerMatchingCatalog matchingCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskIdGenerator taskIds;
    private final ProjectDirectory projects;
    private final TaskResourceCatalog taskCatalog;
    private final TaskScoreBandCore scores;
    private final OperationGuard operations;
    private static final String REQUEST_KEY = "server.creationRequest";
    private static final String FINGERPRINT_KEY = "server.creationFingerprint";

    public TaskCreationService(
            WorkerResourceCatalog workerCatalog,
            WorkerMatchingCatalog matchingCatalog,
            TaskRuntime taskRuntime,
            TaskIdGenerator taskIds,
            ProjectDirectory projects,
            TaskResourceCatalog taskCatalog,
            TaskScoreBandCore scores,
            OperationGuard operations
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.matchingCatalog = Objects.requireNonNull(
                matchingCatalog,
                "matchingCatalog"
        );
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.taskIds = Objects.requireNonNull(taskIds, "taskIds");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public TaskCreateResponse create(TaskCreateRequest request) {
        return create(request, taskIds.nextTaskId());
    }

    /** The caller fingerprints normalized creation inputs, excluding first-creation display defaults. */
    public TaskCreateResponse createForRequest(TaskCreateRequest request, String requestId, String fingerprint) {
        if (request == null || request.projectId() == null || request.projectId().isBlank()
                || requestId == null || requestId.isBlank() || requestId.length() > 128
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || request.metadata().containsKey(REQUEST_KEY) || request.metadata().containsKey(FINGERPRINT_KEY))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, "Invalid creation identity", null);
        String taskId = correlatedTaskId(request.projectId(), requestId);
        return operations.taskMutation(taskId, () -> {
            var existing = existingCreation(taskId, request.projectId(), requestId, fingerprint);
            if (existing != null) return existing;
            var metadata = new java.util.LinkedHashMap<>(request.metadata());
            metadata.put(REQUEST_KEY, requestId);
            metadata.put(FINGERPRINT_KEY, fingerprint);
            try {
                return create(new TaskCreateRequest(request.projectId(), request.workerGroupId(), request.priority(),
                        request.maxRetryTimes(), request.refill(), request.name(), metadata), taskId);
            } catch (ServerException conflict) {
                if (conflict.errorCode() != ServerErrorCode.TASK_STATE_CONFLICT) throw conflict;
                existing = existingCreation(taskId, request.projectId(), requestId, fingerprint);
                if (existing != null) return existing;
                throw new TaskCreationUnconfirmedException(taskId, conflict);
            }
        });
    }

    private TaskCreateResponse existingCreation(String taskId, String projectId, String requestId, String fingerprint) {
        try {
            var descriptor = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
            var directory = taskCatalog.getProjectTask(projectId, taskId);
            var state = scores.getScoreStates(List.of(taskId)).get(taskId);
            if (descriptor == null && directory == null && state == null) return null;
            if (descriptor == null || directory == null || state == null)
                throw new TaskCreationUnconfirmedException(taskId, null);
            if (!projectId.equals(descriptor.projectId()) || descriptor.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE
                    || !requestId.equals(descriptor.metadata().get(REQUEST_KEY))
                    || !fingerprint.equals(descriptor.metadata().get(FINGERPRINT_KEY)))
                throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, OPERATION,
                        "Creation request has different content", null);
            return new TaskCreateResponse(taskId);
        } catch (ServerException known) { throw known;
        } catch (RuntimeException unavailable) { throw new TaskCreationUnconfirmedException(taskId, unavailable); }
    }

    private static String correlatedTaskId(String project, String request) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("xa-mass/task-create/v1", project, request)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return "task-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private TaskCreateResponse create(TaskCreateRequest request, String taskId) {
        if (request == null || request.workerGroupId() == null || request.workerGroupId().isBlank()
                || request.priority() < 0 || request.priority() > 99
                || request.maxRetryTimes() < 0 || request.maxRetryTimes() > 98) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, "Invalid Task creation request", null);
        }
        projects.requireManagedTaskId(request.projectId(), request.workerGroupId());
        requireWorkerGroup(request.workerGroupId());
        List<RefillTarget> targets = resolveTargets(request);
        var config = new java.util.LinkedHashMap<String, String>();
        config.put("priority", Integer.toString(request.priority()));
        config.put("maxRetryTimes", Integer.toString(request.maxRetryTimes()));
        TaskDescriptor descriptor;
        try {
            descriptor = new TaskDescriptor(
                taskId,
                request.projectId(),
                request.workerGroupId(),
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                config,
                targets, request.name(), request.metadata());
        } catch (IllegalArgumentException error) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, null, error);
        }
        TaskCreationResult result;
        try {
            result = taskRuntime.createTask(descriptor);
        } catch (RuntimeException error) {
            throw new TaskCreationUnconfirmedException(taskId, error);
        }
        if (result == null) {
            throw new TaskCreationUnconfirmedException(taskId, null);
        }
        return switch (result.status()) {
            case CREATED -> new TaskCreateResponse(taskId);
            case CONFLICT -> throw new ServerException(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    OPERATION,
                    null,
                    null
            );
            case INVALID -> throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    OPERATION,
                    null,
                    null
            );
            case RETRYABLE -> throw new TaskCreationUnconfirmedException(taskId, null);
        };
    }

    private List<RefillTarget> resolveTargets(TaskCreateRequest request) {
        try {
            return java.util.Objects.requireNonNull(matchingCatalog.normalizeRefill(
                    request.workerGroupId(), request.refill()));
        } catch (IllegalArgumentException error) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION,
                    error.getMessage(), error);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private void requireWorkerGroup(String workerGroupId) {
        try {
            if (workerCatalog.getWorkerGroupDescriptors(
                    List.of(workerGroupId)
            ).get(workerGroupId) == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_WORKER_GROUP_NOT_FOUND,
                        OPERATION,
                        null,
                        null
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private static ServerException unavailable(Throwable cause) {
        return new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                OPERATION,
                null,
                cause
        );
    }
}
