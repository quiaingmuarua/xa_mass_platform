package com.xa.mass.server.task;

import com.xa.mass.server.task.access.TaskCaller;
import java.util.Map;

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
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskDirectory;
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
    private final ProjectDirectory projects;
    private final TaskResourceCatalog taskCatalog;
    private final TaskScoreBandCore scores;
    private final OperationGuard operations;
    private final TaskDirectory directory;
    private static final String REQUEST_KEY = "server.creationRequest";
    private static final String FINGERPRINT_KEY = "server.creationFingerprint";

    public TaskCreationService(
            WorkerResourceCatalog workerCatalog,
            WorkerMatchingCatalog matchingCatalog,
            TaskRuntime taskRuntime,
            ProjectDirectory projects,
            TaskResourceCatalog taskCatalog,
            TaskScoreBandCore scores,
            OperationGuard operations,
            TaskDirectory directory
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
        this.projects = Objects.requireNonNull(projects, "projects");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public TaskCreateResponse create(TaskCaller caller, TaskCreateRequest request) {
        request = normalize(request);
        caller.requireProject(request.projectId());
        var targets = resolveTargets(request);
        request = new TaskCreateRequest(request.requestId(), request.projectId(), request.workerGroupId(),
                request.priority(), request.maxRetryTimes(), targets, request.name(), request.metadata());
        var refill = targets.stream().map(target -> new java.util.TreeMap<>(Map.of("poolName", target.poolName(),
                "target", new java.util.TreeMap<>(target.target().query()), "count", target.count()))).toList();
        String fingerprint = digest("raw-task/v1", request.workerGroupId(), request.priority().toString(),
                request.maxRetryTimes().toString(), request.name() == null ? "N" : "S" + request.name(),
                com.xa.mass.workerdelivery.json.Jsons.toJson(new java.util.TreeMap<>(request.metadata())),
                com.xa.mass.workerdelivery.json.Jsons.toJson(Map.of("refill", refill)));
        return createForRequest(caller, TaskCaller.RAW_INPUT, request, fingerprint);
    }

    /** Business input owners supply their normalized fingerprint; caller and Project own request identity. */
    public TaskCreateResponse createForRequest(TaskCaller caller, String inputOwner, TaskCreateRequest request, String fingerprint) {
        java.util.Objects.requireNonNull(caller, "caller");
        request = normalize(request);
        if (request.projectId() == null || request.projectId().isBlank()
                || request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 128
                || inputOwner == null || inputOwner.isBlank()
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, "Invalid creation identity", null);
        caller.requireProject(request.projectId());
        String taskId = "task-" + digest("xa-mass/task-create/v2", request.projectId(), caller.identityKey(), request.requestId());
        var admitted = request;
        return operations.taskMutation(taskId, () -> {
            var existing = existingCreation(caller, inputOwner, taskId, admitted.projectId(), admitted.requestId(), fingerprint);
            if (existing != null) return existing;
            var metadata = new java.util.LinkedHashMap<>(admitted.metadata());
            metadata.put(REQUEST_KEY, admitted.requestId());
            metadata.put(FINGERPRINT_KEY, fingerprint);
            metadata.put(TaskCaller.INPUT_OWNER, inputOwner);
            try {
                return create(caller, new TaskCreateRequest(admitted.requestId(), admitted.projectId(), admitted.workerGroupId(), admitted.priority(),
                        admitted.maxRetryTimes(), admitted.refill(), admitted.name(), metadata), taskId);
            } catch (ServerException conflict) {
                if (conflict.errorCode() != ServerErrorCode.TASK_STATE_CONFLICT) throw conflict;
                existing = existingCreation(caller, inputOwner, taskId, admitted.projectId(), admitted.requestId(), fingerprint);
                if (existing != null) return existing;
                throw new TaskCreationUnconfirmedException(taskId, conflict);
            }
        });
    }

    private TaskCreateResponse existingCreation(TaskCaller caller, String inputOwner, String taskId, String projectId, String requestId, String fingerprint) {
        try {
            var descriptor = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
            if (descriptor != null) caller.requireTask(descriptor);
            var state = scores.getScoreStates(List.of(taskId)).get(taskId);
            if (descriptor == null && state == null && !directory.contains(projectId, taskId)) return null;
            if (descriptor == null || state == null)
                throw new TaskCreationUnconfirmedException(taskId, null);
            if (!inputOwner.equals(descriptor.metadata().get(TaskCaller.INPUT_OWNER))
                    || !projectId.equals(TaskAttribution.require(descriptor.metadata()).projectId()) || descriptor.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE
                    || !requestId.equals(descriptor.metadata().get(REQUEST_KEY))
                    || !fingerprint.equals(descriptor.metadata().get(FINGERPRINT_KEY)))
                throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, OPERATION,
                        "Creation request has different content", null);
            return confirmDirectory(caller, taskId);
        } catch (ServerException known) {
            if (known instanceof TaskCreationUnconfirmedException || known.errorCode() == ServerErrorCode.TASK_STATE_CONFLICT || known.errorCode() == ServerErrorCode.TASK_ACCESS_DENIED) throw known;
            throw new TaskCreationUnconfirmedException(taskId, known);
        } catch (RuntimeException unavailable) { throw new TaskCreationUnconfirmedException(taskId, unavailable); }
    }

    private static String digest(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private TaskCreateResponse create(TaskCaller caller, TaskCreateRequest request, String taskId) {
        if (request == null || request.workerGroupId() == null || request.workerGroupId().isBlank()
                || request.priority() < 0 || request.priority() > 99
                || request.maxRetryTimes() < 0 || request.maxRetryTimes() > 98) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, "Invalid Task creation request", null);
        }
        projects.requireWorkerGroup(request.projectId(), request.workerGroupId());
        requireWorkerGroup(request.workerGroupId());
        List<RefillTarget> targets = resolveTargets(request);
        var config = new java.util.LinkedHashMap<String, String>();
        config.put("priority", Integer.toString(request.priority()));
        config.put("maxRetryTimes", Integer.toString(request.maxRetryTimes()));
        TaskDescriptor descriptor;
        try {
            descriptor = new TaskDescriptor(
                taskId,
                request.workerGroupId(),
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                config,
                targets, request.name(), new TaskAttribution(request.projectId(), System.currentTimeMillis(),
                        caller.userId()).stamp(request.metadata()));
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
            case CREATED -> confirmDirectory(caller, taskId);
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

    private TaskCreateResponse confirmDirectory(TaskCaller caller, String taskId) {
        try { directory.ensure(caller, taskId); return new TaskCreateResponse(taskId); }
        catch (RuntimeException failure) { throw new TaskCreationUnconfirmedException(taskId, failure); }
    }

    private static TaskCreateRequest normalize(TaskCreateRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException("Task request is required");
            return new TaskCreateRequest(request.requestId(), request.projectId(), request.workerGroupId(), request.priority(), request.maxRetryTimes(),
                    request.refill(), request.name(), TaskAttribution.input(request.metadata()));
        } catch (IllegalArgumentException invalid) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, invalid.getMessage(), invalid);
        }
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
