package com.xa.mass.server.worker.binding;

import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.identity.WorkerIdentityService;
import com.xa.mass.server.worker.identity.WorkerRegistrationKind;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public final class WorkerBindingService {

    private final WorkerBindingRegistry registry;
    private final WorkerEndpointDirectory endpoints;
    private final WorkerIdentityService identities;
    private final WorkerRuntime workerRuntime;

    WorkerBindingService(
            WorkerBindingRegistry registry,
            WorkerEndpointDirectory endpoints,
            WorkerIdentityService identities,
            WorkerRuntime workerRuntime
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.workerRuntime = Objects.requireNonNull(
                workerRuntime,
                "workerRuntime"
        );
    }

    public WorkerEndpointBinding bind(
            String workerGroupId,
            String workerId,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties
    ) {
        return bind(
                workerGroupId,
                workerId,
                WorkerRegistrationKind.CLIENT_KEY,
                transportType,
                workerProperties
        );
    }

    public WorkerEndpointBinding bind(
            String workerGroupId,
            String workerId,
            WorkerRegistrationKind workerKind,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties
    ) {
        String operation = "workerBinding.bind";
        requireNonBlank(workerGroupId, "workerGroupId", operation);
        requireNonBlank(workerId, "workerId", operation);
        Objects.requireNonNull(workerKind, "workerKind");
        Objects.requireNonNull(transportType, "transportType");
        Objects.requireNonNull(workerProperties, "workerProperties");
        identities.requireRegistration(
                workerGroupId,
                workerKind,
                workerProperties,
                workerId
        );
        return bindRegistered(
                workerGroupId,
                workerId,
                transportType,
                workerProperties,
                operation
        );
    }

    private WorkerEndpointBinding bindRegistered(
            String workerGroupId,
            String workerId,
            WorkerTransportType transportType,
            Map<String, Object> workerProperties,
            String operation
    ) {
        String endpointManagerId;
        try {
            endpointManagerId = registry.getEndpointManagerId(workerId);
            if (endpointManagerId == null) {
                WorkerEndpointBinding selected = endpoints.select(
                        workerId,
                        transportType
                );
                if (selected == null) {
                    throw failure(
                            ServerErrorCode.WORKER_ENDPOINT_UNAVAILABLE,
                            operation,
                            "No endpoint is configured for "
                                    + transportType,
                            null
                    );
                }
                endpointManagerId = registry.bindIfAbsent(
                        workerId,
                        selected.endpointManagerId()
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(
                    ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                    operation,
                    null,
                    error
            );
        }
        WorkerEndpointBinding binding = endpoints.find(endpointManagerId);
        if (binding == null) {
            throw failure(
                    ServerErrorCode.WORKER_ENDPOINT_UNAVAILABLE,
                    operation,
                    "Bound endpoint is not present in the endpoint directory",
                    null
            );
        }
        if (binding.transportType() != transportType) {
            throw failure(
                    ServerErrorCode.WORKER_BINDING_CONFLICT,
                    operation,
                    "Worker is already bound to a different transport",
                    null
            );
        }

        WorkerRuntimeResult result;
        try {
            result = workerRuntime.upsertWorker(new WorkerDeclaration(
                    workerId,
                    workerGroupId,
                    endpointManagerId,
                    workerProperties
            ));
        } catch (RuntimeException error) {
            throw failure(
                    ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                    operation,
                    null,
                    error
            );
        }
        requireUpsertAccepted(result, operation);
        return binding;
    }

    public void requireCurrentEndpoint(
            String endpointManagerId,
            String workerId
    ) {
        String operation = "workerBinding.requireCurrentEndpoint";
        requireNonBlank(endpointManagerId, "endpointManagerId", operation);
        requireNonBlank(workerId, "workerId", operation);
        String current;
        try {
            current = registry.getEndpointManagerId(workerId);
        } catch (RuntimeException error) {
            throw failure(
                    ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                    operation,
                    null,
                    error
            );
        }
        if (current == null) {
            throw failure(
                    ServerErrorCode.WORKER_BINDING_NOT_FOUND,
                    operation,
                    "Worker has not been bound to an endpoint",
                    null
            );
        }
        if (!current.equals(endpointManagerId)) {
            throw failure(
                    ServerErrorCode.WORKER_BINDING_CONFLICT,
                    operation,
                    "Worker is bound to a different endpoint",
                    null
            );
        }
    }

    public Map<String, String> currentEndpointManagerIds(
            List<String> workerIds
    ) {
        try {
            return currentEndpointManagerIdsAsync(workerIds)
                    .toCompletableFuture()
                    .join();
        } catch (CompletionException error) {
            Throwable cause = unwrap(error);
            if (cause instanceof ServerException serverError) {
                throw serverError;
            }
            throw failure(
                    ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                    "workerBinding.currentEndpointManagerIds",
                    null,
                    cause
            );
        }
    }

    public CompletionStage<Map<String, String>> currentEndpointManagerIdsAsync(
            List<String> workerIds
    ) {
        String operation = "workerBinding.currentEndpointManagerIds";
        if (workerIds == null
                || workerIds.isEmpty()
                || workerIds.size() > 100) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_BINDING_REQUEST,
                    operation,
                    "workerIds must contain between 1 and 100 entries",
                    null
            );
        }
        LinkedHashMap<String, Boolean> unique = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            requireNonBlank(workerId, "workerId", operation);
            if (unique.put(workerId, Boolean.TRUE) != null) {
                throw failure(
                        ServerErrorCode.INVALID_WORKER_BINDING_REQUEST,
                        operation,
                        "workerIds must be unique",
                        null
                );
            }
        }
        CompletionStage<Map<String, String>> loaded;
        try {
            loaded = registry.getEndpointManagerIdsAsync(List.copyOf(
                    workerIds
            ));
        } catch (RuntimeException error) {
            return java.util.concurrent.CompletableFuture.failedFuture(failure(
                    ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                    operation,
                    null,
                    error
            ));
        }
        return loaded.handle((bindings, error) -> {
            if (error != null) {
                throw failure(
                        ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                        operation,
                        null,
                        unwrap(error)
                );
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(bindings, "bindings")
            ));
        });
    }

    private static void requireUpsertAccepted(
            WorkerRuntimeResult result,
            String operation
    ) {
        switch (result.status()) {
            case OK, NOOP -> {
                return;
            }
            case NOT_FOUND -> throw failure(
                    ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                    operation,
                    result.reason(),
                    null
            );
            case CONFLICT -> throw failure(
                    ServerErrorCode.WORKER_BINDING_CONFLICT,
                    operation,
                    result.reason(),
                    null
            );
            case INVALID -> throw failure(
                    ServerErrorCode.INVALID_WORKER_BINDING_REQUEST,
                    operation,
                    result.reason(),
                    null
            );
            case REJECTED, STALE -> throw failure(
                    ServerErrorCode.WORKER_BINDING_UNAVAILABLE,
                    operation,
                    result.reason(),
                    null
            );
        }
    }

    private static String requireNonBlank(
            String value,
            String field,
            String operation
    ) {
        if (value == null || value.isBlank()) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_BINDING_REQUEST,
                    operation,
                    field + " must be non-blank",
                    null
            );
        }
        return value;
    }

    private static ServerException failure(
            ServerErrorCode code,
            String operation,
            String message,
            Throwable cause
    ) {
        return new ServerException(code, operation, message, cause);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
