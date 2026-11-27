package com.xa.mass.server.task.call;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskDirectory;
import com.xa.mass.server.task.directory.TaskRpcChannelRegistry;
import com.xa.mass.server.task.directory.TaskUserId;
import com.xa.mass.server.user.UserDirectory;
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
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class TaskRpcPreparationService {

    private static final String REGISTER_OPERATION = "taskRpc.prepare";
    private static final Map<String, String> TASK_CONFIG = Map.of("priority", "0", "maxRetryTimes", "3");

    private final ProjectDirectory projects;
    private final WorkerResourceCatalog workerCatalog;
    private final TaskResourceCatalog taskCatalog;
    private final TaskRuntime taskRuntime;
    private final WorkerMatchingCatalog matching;
    private final TaskRpcProperties rpc;
    private final TaskLifecycleCommands taskLifecycle;
    private final OperationGuard operations;
    private final TaskDirectory directory;
    private final TaskScoreBandCore scores;
    private final TaskRpcChannelRegistry channels;
    private final UserDirectory users;

    public TaskRpcPreparationService(
            WorkerResourceCatalog workerCatalog, TaskResourceCatalog taskCatalog, TaskRuntime taskRuntime,
            TaskLifecycleCommands taskLifecycle, WorkerMatchingCatalog matching, TaskRpcProperties rpc,
            ProjectDirectory projects, OperationGuard operations, TaskDirectory directory,
            TaskScoreBandCore scores, TaskRpcChannelRegistry channels, UserDirectory users) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.matching = Objects.requireNonNull(matching, "matching");
        this.workerCatalog = Objects.requireNonNull(workerCatalog, "workerCatalog");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.taskLifecycle = Objects.requireNonNull(taskLifecycle, "taskLifecycle");
        this.channels = Objects.requireNonNull(channels, "channels");
        this.users = Objects.requireNonNull(users, "users");
    }

    /**
     * Administrator-only external RPC channel provisioning. Server mints and owns the {@code taskId} per
     * active {@code (projectId, ownerUserId, workerGroupId)} slot: creates if absent, returns the existing
     * {@code taskId} if identical, and rejects conflicting configuration.
     */
    public TaskCreateResponse ensureRawChannel(TaskCaller caller, TaskRpcPrepareRequest request) {
        Objects.requireNonNull(caller, "caller");
        caller.requireAdmin();
        if (request == null
                || request.projectId() == null || request.projectId().isBlank()
                || request.workerGroupId() == null || request.workerGroupId().isBlank()) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, "Invalid RPC Task preparation", null);
        }
        caller.requireProject(request.projectId());
        projects.requireInputOwner(request.projectId(), TaskCaller.RAW_INPUT);
        projects.requireWorkerGroup(request.projectId(), request.workerGroupId());
        requireWorkerGroup(request.workerGroupId(), REGISTER_OPERATION);

        TaskCaller owner = resolveOwner(caller, request);
        final Map<String, String> inputMetadata;
        try {
            inputMetadata = TaskAttribution.input(request.metadata());
        } catch (IllegalArgumentException invalid) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, invalid.getMessage(), invalid);
        }
        List<RefillTarget> normalizedRefill = normalizeRefill(request);
        String fingerprint = channelFingerprint(request.workerGroupId(), request.name(), inputMetadata, normalizedRefill);
        TaskRpcChannelRegistry.Claim claim = channels.reserve(
                request.projectId(), owner.userId(), request.workerGroupId(), TaskCaller.RAW_INPUT, fingerprint);

        var admitted = new TaskRpcPrepareRequest(request.projectId(), request.workerGroupId(), owner.userId(),
                request.name(), inputMetadata, normalizedRefill);
        return operations.taskMutation(claim.taskId(), () -> {
            final boolean current;
            try {
                current = channels.isCurrent(claim);
            } catch (RuntimeException uncertain) {
                throw unavailable(REGISTER_OPERATION, "RPC channel slot is unconfirmed", uncertain);
            }
            if (!current) {
                throw failure(ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT, REGISTER_OPERATION,
                        "RPC channel slot changed; retry provisioning", null);
            }
            return prepareObserved(owner, TaskCaller.RAW_INPUT, claim.taskId(), claim.key(), admitted, normalizedRefill);
        });
    }

    /**
     * In-process Project Scenario RPC channel preparation (e.g. SMS). Requires a trusted service caller
     * constructed by Server assembly; never exposed over HTTP.
     */
    public TaskCreateResponse prepareServiceChannel(
            TaskCaller caller, String inputOwner, String taskId, TaskRpcPrepareRequest request) {
        Objects.requireNonNull(caller, "caller");
        if (!caller.isService()) {
            throw failure(ServerErrorCode.TASK_ACCESS_DENIED, REGISTER_OPERATION, "Service caller is required", null);
        }
        if (taskId == null || taskId.isBlank() || request == null
                || request.projectId() == null || request.projectId().isBlank()
                || request.workerGroupId() == null || request.workerGroupId().isBlank()) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, "Invalid RPC Task preparation", null);
        }
        caller.requireProject(request.projectId());
        projects.requireInputOwner(request.projectId(), inputOwner);
        final Map<String, String> inputMetadata;
        try {
            inputMetadata = TaskAttribution.input(request.metadata());
        } catch (IllegalArgumentException invalid) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, invalid.getMessage(), invalid);
        }
        var admitted = new TaskRpcPrepareRequest(request.projectId(), request.workerGroupId(), null,
                request.name(), inputMetadata, request.refill());
        return operations.taskMutation(taskId, () -> {
            projects.requireWorkerGroup(admitted.projectId(), admitted.workerGroupId());
            requireWorkerGroup(admitted.workerGroupId(), REGISTER_OPERATION);
            List<RefillTarget> normalizedRefill = normalizeRefill(admitted);
            return prepareObserved(caller, inputOwner, taskId, null, admitted, normalizedRefill);
        });
    }

    private TaskCaller resolveOwner(TaskCaller admin, TaskRpcPrepareRequest request) {
        if (request.ownerUserId() == null || request.ownerUserId().isBlank()) {
            if (admin.userId() == null) {
                throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, "Owner userId is required", null);
            }
            return admin;
        }
        final String normalized;
        try {
            normalized = TaskUserId.normalize(request.ownerUserId());
        } catch (IllegalArgumentException invalid) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, invalid.getMessage(), invalid);
        }
        if (normalized.equals(admin.userId())) {
            return admin;
        }
        TaskCaller owner = users.findCaller(normalized);
        if (owner == null) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, "Owner user was not found", null);
        }
        try {
            owner.requireProject(request.projectId());
        } catch (ServerException denied) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION,
                    "Owner user is not granted this Project", denied);
        }
        return owner;
    }

    private TaskCreateResponse prepareObserved(
            TaskCaller owner, String inputOwner, String taskId, @Nullable String channelKey,
            TaskRpcPrepareRequest request, List<RefillTarget> normalizedRefill) {
        TaskDescriptor existing = loadDescriptor(taskId, REGISTER_OPERATION);
        if (existing != null) {
            owner.requireInput(existing, inputOwner);
        }
        TaskDescriptor expected = descriptor(owner, inputOwner, taskId, channelKey, request, normalizedRefill, existing);
        if (existing == null) {
            try {
                if (scores.getScoreStates(List.of(taskId)).get(taskId) != null
                        || directory.contains(request.projectId(), taskId)) {
                    throw new IllegalStateException("RPC identity has incomplete Task resources");
                }
            } catch (RuntimeException failure) {
                throw unavailable(REGISTER_OPERATION, "RPC Task resources are unconfirmed", failure);
            }
            TaskCreationResult creation = callRequired(() -> taskRuntime.createTask(expected));
            if (creation.status() == TaskCreationStatus.CONFLICT) {
                existing = loadDescriptor(expected.taskId(), REGISTER_OPERATION);
                if (existing == null) {
                    throw unavailable(REGISTER_OPERATION, "conflicting Task is not observable", null);
                }
                owner.requireInput(existing, inputOwner);
                requireEquivalent(descriptor(owner, inputOwner, taskId, channelKey, request, normalizedRefill, existing),
                        existing, REGISTER_OPERATION);
            } else if (creation.status() != TaskCreationStatus.CREATED) {
                throw unavailable(REGISTER_OPERATION, creation.reason(), null);
            }
        } else {
            requireEquivalent(expected, existing, REGISTER_OPERATION);
        }

        try {
            directory.ensure(owner, taskId);
        } catch (RuntimeException failure) {
            throw unavailable(REGISTER_OPERATION, "Task directory is unconfirmed", failure);
        }
        TaskApprovalResult approval = callRequired(() -> taskLifecycle.approveTask(expected.taskId()));
        return switch (approval.status()) {
            case APPROVED, ALREADY_APPROVED -> new TaskCreateResponse(expected.taskId());
            case CONFLICT -> throw failure(ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT, REGISTER_OPERATION, approval.reason(), null);
            case NOT_FOUND, RETRYABLE, INVALID -> throw unavailable(REGISTER_OPERATION, approval.reason(), null);
        };
    }

    private List<RefillTarget> normalizeRefill(TaskRpcPrepareRequest request) {
        try {
            return Objects.requireNonNull(matching.normalizeRefill(request.workerGroupId(),
                    request.refill() == null
                            ? rpc.refillByWorkerGroup().getOrDefault(request.workerGroupId(), List.of())
                            : request.refill()));
        } catch (IllegalArgumentException invalid) {
            if (request.refill() != null) {
                throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, invalid.getMessage(), invalid);
            }
            throw unavailable(REGISTER_OPERATION, "Call Task targets unavailable", invalid);
        } catch (RuntimeException error) {
            throw unavailable(REGISTER_OPERATION, "Call Task targets unavailable", error);
        }
    }

    private <T> T callRequired(Supplier<T> action) {
        try {
            T result = action.get();
            if (result == null) {
                throw unavailable(REGISTER_OPERATION, null, null);
            }
            return result;
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(REGISTER_OPERATION, null, error);
        }
    }

    private void requireWorkerGroup(String workerGroupId, String operation) {
        try {
            if (workerCatalog.getWorkerGroupDescriptors(List.of(workerGroupId)).get(workerGroupId) == null) {
                throw failure(ServerErrorCode.WORKER_GROUP_NOT_FOUND, operation, null, null);
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(operation, null, error);
        }
    }

    private TaskDescriptor loadDescriptor(String taskId, String operation) {
        try {
            return taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
        } catch (RuntimeException error) {
            throw unavailable(operation, null, error);
        }
    }

    private static void requireEquivalent(TaskDescriptor expected, TaskDescriptor existing, String operation) {
        if (!expected.equals(existing)) {
            throw failure(ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT, operation,
                    "Task identity has different configuration", null);
        }
    }

    private static TaskDescriptor descriptor(
            TaskCaller owner, String inputOwner, String taskId, @Nullable String channelKey,
            TaskRpcPrepareRequest request, List<RefillTarget> refill, @Nullable TaskDescriptor existing) {
        try {
            var saved = existing == null ? null : TaskAttribution.require(existing.metadata());
            long created = saved == null ? System.currentTimeMillis() : saved.createdAtMillis();
            String creator = saved == null ? owner.userId() : saved.userId();
            var metadata = new LinkedHashMap<>(
                    new TaskAttribution(request.projectId(), created, creator).stamp(request.metadata()));
            metadata.put(TaskCaller.INPUT_OWNER, inputOwner);
            if (channelKey != null) {
                metadata.put(TaskRpcChannelRegistry.METADATA_KEY, channelKey);
            }
            return new TaskDescriptor(taskId, request.workerGroupId(), TaskIdleDisposition.PARK_WHEN_IDLE,
                    TASK_CONFIG, refill, request.name(), metadata);
        } catch (IllegalArgumentException error) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, null, error);
        }
    }

    private static String channelFingerprint(
            String workerGroupId, @Nullable String name, Map<String, String> metadata, List<RefillTarget> targets) {
        var refill = targets.stream()
                .map(target -> new TreeMap<>(Map.of(
                        "poolName", target.poolName(),
                        "target", new TreeMap<>(target.target().query()),
                        "count", target.count())))
                .toList();
        return digest("raw-rpc-channel/v1", workerGroupId, name == null ? "N" : "S" + name,
                Jsons.toJson(new TreeMap<>(metadata)), Jsons.toJson(Map.of("refill", refill)));
    }

    private static String digest(String... parts) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(4).putInt(value.length).array());
                sha.update(value);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ServerException unavailable(String operation, @Nullable String message, @Nullable Throwable cause) {
        return failure(ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE, operation, message, cause);
    }

    private static ServerException failure(
            ServerErrorCode code, String operation, @Nullable String message, @Nullable Throwable cause) {
        return new ServerException(code, operation, message, cause);
    }
}
