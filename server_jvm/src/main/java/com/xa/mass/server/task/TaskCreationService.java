package com.xa.mass.server.task;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskDataPhase;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskCreationRegistry;
import com.xa.mass.server.task.directory.TaskDirectory;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class TaskCreationService {

    private static final String OPERATION = "taskCreation.create";
    private static final String REQUEST_KEY = "server.creationRequest";
    private static final String FINGERPRINT_KEY = "server.creationFingerprint";

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerMatchingCatalog matchingCatalog;
    private final TaskRuntime taskRuntime;
    private final ProjectDirectory projects;
    private final TaskResourceCatalog taskCatalog;
    private final TaskScoreBandCore scores;
    private final OperationGuard operations;
    private final TaskDirectory directory;
    private final TaskCreationRegistry creations;

    public TaskCreationService(
            WorkerResourceCatalog workerCatalog, WorkerMatchingCatalog matchingCatalog,
            TaskRuntime taskRuntime, ProjectDirectory projects, TaskResourceCatalog taskCatalog,
            TaskScoreBandCore scores, OperationGuard operations, TaskDirectory directory,
            TaskCreationRegistry creations) {
        this.workerCatalog = Objects.requireNonNull(workerCatalog, "workerCatalog");
        this.matchingCatalog = Objects.requireNonNull(matchingCatalog, "matchingCatalog");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.creations = Objects.requireNonNull(creations, "creations");
    }

    public TaskCreateResponse create(TaskCaller caller, TaskCreateRequest request) {
        request = normalize(request);
        caller.requireProject(request.projectId());
        var targets = resolveTargets(request);
        request = new TaskCreateRequest(request.requestId(), request.projectId(), request.workerGroupId(),
                request.priority(), request.maxRetryTimes(), targets, request.name(), request.metadata());
        var refill = targets.stream()
                .map(target -> new TreeMap<>(Map.of("poolName", target.poolName(),
                        "target", new TreeMap<>(target.target().query()), "count", target.count())))
                .toList();
        String fingerprint = digest("raw-task/v1", request.workerGroupId(), request.priority().toString(),
                request.maxRetryTimes().toString(), request.name() == null ? "N" : "S" + request.name(),
                Jsons.toJson(new TreeMap<>(request.metadata())), Jsons.toJson(Map.of("refill", refill)));
        return createForRequest(caller, TaskCaller.RAW_INPUT, request, fingerprint);
    }

    /** Business input owners supply their normalized fingerprint; caller and Project own request identity. */
    public TaskCreateResponse createForRequest(
            TaskCaller caller, String inputOwner, TaskCreateRequest request, String fingerprint) {
        Objects.requireNonNull(caller, "caller");
        request = normalize(request);
        if (request.projectId() == null || request.projectId().isBlank()
                || request.requestId() == null || request.requestId().isBlank() || request.requestId().length() > 128
                || inputOwner == null || inputOwner.isBlank()
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw invalid("Invalid creation identity", null);
        }
        caller.requireProject(request.projectId());
        projects.requireInputOwner(request.projectId(), inputOwner);
        TaskCreationRegistry.Claim retained;
        try {
            retained = creations.find(request.projectId(), caller.identityKey(), request.requestId(), inputOwner, fingerprint);
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException unavailable) {
            throw unavailable(unavailable);
        }
        List<RefillTarget> normalizedTargets = null;
        if (retained == null) {
            normalizedTargets = validateNewCreation(caller, request, inputOwner, fingerprint);
            retained = creations.reserve(request.projectId(), caller.identityKey(), request.requestId(), inputOwner, fingerprint);
        }
        var claim = retained;
        var admittedTargets = normalizedTargets;
        String taskId = claim.taskId();
        var admitted = request;
        return operations.taskMutation(taskId, () -> {
            final boolean current;
            try {
                current = creations.isCurrent(claim);
            } catch (RuntimeException uncertain) {
                throw new TaskCreationUnconfirmedException(taskId, uncertain);
            }
            if (!current) {
                throw failure(ServerErrorCode.TASK_STATE_CONFLICT, "Creation association changed; retry the same requestId");
            }
            var existing = existingCreation(caller, inputOwner, taskId, admitted.projectId(), admitted.requestId(), fingerprint);
            if (existing != null) {
                return existing;
            }
            var metadata = creationMetadata(admitted.metadata(), admitted.requestId(), fingerprint, inputOwner, claim.key());
            var stamped = new TaskCreateRequest(admitted.requestId(), admitted.projectId(), admitted.workerGroupId(),
                    admitted.priority(), admitted.maxRetryTimes(), admitted.refill(), admitted.name(), metadata);
            try {
                return create(caller, stamped, taskId, admittedTargets);
            } catch (ServerException conflict) {
                if (conflict.errorCode() != ServerErrorCode.TASK_STATE_CONFLICT) {
                    throw conflict;
                }
                existing = existingCreation(caller, inputOwner, taskId, admitted.projectId(), admitted.requestId(), fingerprint);
                if (existing != null) {
                    return existing;
                }
                throw new TaskCreationUnconfirmedException(taskId, conflict);
            }
        });
    }

    private TaskCreateResponse existingCreation(
            TaskCaller caller, String inputOwner, String taskId, String projectId, String requestId, String fingerprint) {
        try {
            var descriptor = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
            if (descriptor != null) {
                caller.requireTask(descriptor);
                var dataState = taskRuntime.loadTaskDataStates(List.of(taskId)).get(taskId);
                if (dataState == null) {
                    throw new TaskCreationUnconfirmedException(taskId, null);
                }
                if (dataState.phase() == TaskDataPhase.PURGING) {
                    throw failure(ServerErrorCode.TASK_CLEANUP_IN_PROGRESS, null);
                }
            }
            var state = scores.getScoreStates(List.of(taskId)).get(taskId);
            if (descriptor == null && state == null && !directory.contains(projectId, taskId)) {
                return null;
            }
            // A reserved identity with only a PRE_REVIEW Score is an incomplete
            // Kernel creation. Re-enter its existing create reconciliation.
            if (descriptor == null && state != null && state.band() == TaskScoreBand.PRE_REVIEW) {
                return null;
            }
            if (descriptor == null || state == null) {
                throw new TaskCreationUnconfirmedException(taskId, null);
            }
            if (!inputOwner.equals(descriptor.metadata().get(TaskCaller.INPUT_OWNER))
                    || !projectId.equals(TaskAttribution.require(descriptor.metadata()).projectId())
                    || descriptor.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE
                    || !requestId.equals(descriptor.metadata().get(REQUEST_KEY))
                    || !fingerprint.equals(descriptor.metadata().get(FINGERPRINT_KEY))) {
                throw failure(ServerErrorCode.TASK_STATE_CONFLICT, "Creation request has different content");
            }
            return confirmDirectory(caller, taskId);
        } catch (ServerException known) {
            if (known instanceof TaskCreationUnconfirmedException
                    || known.errorCode() == ServerErrorCode.TASK_STATE_CONFLICT
                    || known.errorCode() == ServerErrorCode.TASK_CLEANUP_IN_PROGRESS
                    || known.errorCode() == ServerErrorCode.TASK_ACCESS_DENIED) {
                throw known;
            }
            throw new TaskCreationUnconfirmedException(taskId, known);
        } catch (RuntimeException unavailable) {
            throw new TaskCreationUnconfirmedException(taskId, unavailable);
        }
    }

    private List<RefillTarget> validateNewCreation(
            TaskCaller caller, TaskCreateRequest request, String inputOwner, String fingerprint) {
        projects.requireWorkerGroup(request.projectId(), request.workerGroupId());
        requireWorkerGroup(request.workerGroupId());
        var targets = resolveTargets(request);
        var metadata = creationMetadata(request.metadata(), request.requestId(), fingerprint, inputOwner, "0".repeat(64));
        // Validate the complete stored shape before reserving any durable intent.
        buildDescriptor("validation", caller, request, targets, metadata, true);
        return targets;
    }

    private TaskCreateResponse create(
            TaskCaller caller, TaskCreateRequest request, String taskId, List<RefillTarget> admittedTargets) {
        if (request == null || request.workerGroupId() == null || request.workerGroupId().isBlank()
                || request.priority() < 0 || request.priority() > 99
                || request.maxRetryTimes() < 0 || request.maxRetryTimes() > 98) {
            throw invalid("Invalid Task creation request", null);
        }
        List<RefillTarget> targets = admittedTargets;
        if (targets == null) {
            projects.requireWorkerGroup(request.projectId(), request.workerGroupId());
            requireWorkerGroup(request.workerGroupId());
            targets = resolveTargets(request);
        }
        TaskDescriptor descriptor = buildDescriptor(taskId, caller, request, targets, request.metadata(), false);
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
            case CONFLICT -> throw failure(ServerErrorCode.TASK_STATE_CONFLICT, null);
            case INVALID -> throw invalid(null, null);
            case RETRYABLE -> throw new TaskCreationUnconfirmedException(taskId, null);
        };
    }

    private TaskCreateResponse confirmDirectory(TaskCaller caller, String taskId) {
        try {
            directory.ensure(caller, taskId);
            return new TaskCreateResponse(taskId);
        } catch (RuntimeException failure) {
            throw new TaskCreationUnconfirmedException(taskId, failure);
        }
    }

    private static TaskDescriptor buildDescriptor(
            String taskId, TaskCaller caller, TaskCreateRequest request,
            List<RefillTarget> targets, Map<String, String> metadata, boolean exposeMessage) {
        var config = new LinkedHashMap<String, String>();
        config.put("priority", Integer.toString(request.priority()));
        config.put("maxRetryTimes", Integer.toString(request.maxRetryTimes()));
        try {
            return new TaskDescriptor(taskId, request.workerGroupId(), TaskIdleDisposition.CLOSE_WHEN_IDLE, config,
                    targets, request.name(),
                    new TaskAttribution(request.projectId(), System.currentTimeMillis(), caller.userId()).stamp(metadata));
        } catch (IllegalArgumentException invalid) {
            throw invalid(exposeMessage ? invalid.getMessage() : null, invalid);
        }
    }

    private static Map<String, String> creationMetadata(
            Map<String, String> base, String requestId, String fingerprint, String inputOwner, String claimKey) {
        var metadata = new LinkedHashMap<>(base);
        metadata.put(REQUEST_KEY, requestId);
        metadata.put(FINGERPRINT_KEY, fingerprint);
        metadata.put(TaskCaller.INPUT_OWNER, inputOwner);
        metadata.put(TaskCreationRegistry.METADATA_KEY, claimKey);
        return metadata;
    }

    private static String digest(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static TaskCreateRequest normalize(TaskCreateRequest request) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("Task request is required");
            }
            return new TaskCreateRequest(request.requestId(), request.projectId(), request.workerGroupId(),
                    request.priority(), request.maxRetryTimes(), request.refill(), request.name(),
                    TaskAttribution.input(request.metadata()));
        } catch (IllegalArgumentException invalid) {
            throw invalid(invalid.getMessage(), invalid);
        }
    }

    private List<RefillTarget> resolveTargets(TaskCreateRequest request) {
        try {
            return Objects.requireNonNull(matchingCatalog.normalizeRefill(request.workerGroupId(), request.refill()));
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage(), error);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private void requireWorkerGroup(String workerGroupId) {
        try {
            if (workerCatalog.getWorkerGroupDescriptors(List.of(workerGroupId)).get(workerGroupId) == null) {
                throw failure(ServerErrorCode.TASK_WORKER_GROUP_NOT_FOUND, null);
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private static ServerException invalid(@Nullable String message, @Nullable Throwable cause) {
        return new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, message, cause);
    }

    private static ServerException failure(ServerErrorCode code, @Nullable String message) {
        return new ServerException(code, OPERATION, message, null);
    }

    private static ServerException unavailable(Throwable cause) {
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, OPERATION, null, cause);
    }
}
