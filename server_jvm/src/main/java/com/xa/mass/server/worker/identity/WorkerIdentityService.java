package com.xa.mass.server.worker.identity;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class WorkerIdentityService {

    private final WorkerIdentityRegistry registry;
    WorkerIdentityService(WorkerIdentityRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Prepare has already checked Group and default Endpoint before entering here. */
    public List<String> registerAll(String workerGroupId, List<String> registrationKeys) {
        String operation = "workerIdentity.registerAll";
        requireNonBlank(workerGroupId, "workerGroupId", operation);
        if (registrationKeys == null || registrationKeys.isEmpty() || registrationKeys.size() > 100
                || new java.util.HashSet<>(registrationKeys).size() != registrationKeys.size()) {
            throw failure(ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST, operation,
                    "registrationKeys must contain 1..100 unique keys", null);
        }
        registrationKeys.forEach(key -> requireNonBlank(key, "registrationKey", operation));
        try {
            List<String> workerIds = registry.registerAll(workerGroupId, registrationKeys);
            if (workerIds.size() != registrationKeys.size()
                    || workerIds.stream().anyMatch(id -> !isCanonicalUuid(id))) {
                throw failure(ServerErrorCode.WORKER_IDENTITY_CONFLICT, operation,
                        "Stored Worker identity is invalid", null);
            }
            return List.copyOf(workerIds);
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(ServerErrorCode.WORKER_IDENTITY_UNAVAILABLE, operation, null, error);
        }
    }

    public String registrationKey(
            WorkerRegistrationKind workerKind,
            Map<String, Object> workerProperties
    ) {
        String operation = "workerIdentity.registrationKey";
        Objects.requireNonNull(workerKind, "workerKind");
        if (workerProperties == null) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties must be present",
                    null
            );
        }
        return switch (workerKind) {
            case CLIENT_KEY -> clientKeyRegistrationKey(
                    requireClientWorkerKey(workerProperties, operation)
            );
            case SCENARIO_LAB -> scenarioLabRegistrationKey(
                    workerProperties,
                    operation
            );
        };
    }

    private static String scenarioLabRegistrationKey(
            Map<String, Object> workerProperties,
            String operation
    ) {
        if (workerProperties.containsKey("clientWorkerKey")) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "SCENARIO_LAB workerProperties must not contain clientWorkerKey",
                    null
            );
        }
        Object rawInventoryKey = workerProperties.get("labInventoryKey");
        if (!(rawInventoryKey instanceof String inventoryKey)
                || inventoryKey.isBlank()) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties.labInventoryKey must be a non-blank string",
                    null
            );
        }
        Object rawLine = workerProperties.get("labInventoryLine");
        int line;
        try {
            if (!(rawLine instanceof String value) || !value.matches("[0-9]+")) {
                throw new IllegalArgumentException("Expected decimal string");
            }
            line = Integer.parseInt(value);
        } catch (IllegalArgumentException error) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties.labInventoryLine must be a decimal string",
                    error
            );
        }
        if (line < 1L || line > 100L) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties.labInventoryLine must be between 1 and 100",
                    null
            );
        }
        return "scenario-lab:"
                + inventoryKey.length()
                + ":"
                + inventoryKey
                + ":"
                + line;
    }

    private static String clientKeyRegistrationKey(String clientWorkerKey) {
        return "client-key:"
                + clientWorkerKey.length()
                + ":"
                + clientWorkerKey;
    }

    private static void requireNonBlank(
            String value,
            String name,
            String operation
    ) {
        if (value == null || value.isBlank()) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    name + " must be non-blank",
                    null
            );
        }
    }

    private static String requireClientWorkerKey(
            Map<String, Object> workerProperties,
            String operation
    ) {
        Object value = workerProperties.get("clientWorkerKey");
        if (!(value instanceof String key) || key.isBlank()) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties.clientWorkerKey must be a "
                            + "non-blank string",
                    null
            );
        }
        return key;
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static ServerException failure(
            ServerErrorCode errorCode,
            String operation,
            String message,
            Throwable cause
    ) {
        return new ServerException(errorCode, operation, message, cause);
    }
}
