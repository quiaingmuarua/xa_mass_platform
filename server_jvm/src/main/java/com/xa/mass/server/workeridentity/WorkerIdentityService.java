package com.xa.mass.server.workeridentity;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerpreparation.WorkerRegistrationKind;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class WorkerIdentityService {

    private final WorkerIdentityRegistry registry;
    private final WorkerResourceCatalog workerCatalog;

    WorkerIdentityService(
            WorkerIdentityRegistry registry,
            WorkerResourceCatalog workerCatalog
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
    }

    public String register(
            String workerGroupId,
            Map<String, Object> workerProperties
    ) {
        return register(
                workerGroupId,
                WorkerRegistrationKind.CLIENT_KEY,
                workerProperties
        );
    }

    public String register(
            String workerGroupId,
            WorkerRegistrationKind workerKind,
            Map<String, Object> workerProperties
    ) {
        String operation = "workerIdentity.register";
        requireNonBlank(workerGroupId, "workerGroupId", operation);
        String registrationKey = registrationKey(
                workerKind,
                workerProperties,
                operation
        );
        return registerResolved(
                workerGroupId,
                registrationKey,
                operation
        );
    }

    public void requireRegistration(
            String workerGroupId,
            Map<String, Object> workerProperties,
            String workerId
    ) {
        requireRegistration(
                workerGroupId,
                WorkerRegistrationKind.CLIENT_KEY,
                workerProperties,
                workerId
        );
    }

    public void requireRegistration(
            String workerGroupId,
            WorkerRegistrationKind workerKind,
            Map<String, Object> workerProperties,
            String workerId
    ) {
        String operation = "workerIdentity.requireRegistration";
        requireNonBlank(workerGroupId, "workerGroupId", operation);
        String registrationKey = registrationKey(
                workerKind,
                workerProperties,
                operation
        );
        requireResolvedRegistration(
                workerGroupId,
                registrationKey,
                workerId,
                operation
        );
    }

    public String registrationKey(
            WorkerRegistrationKind workerKind,
            Map<String, Object> workerProperties
    ) {
        return registrationKey(
                workerKind,
                workerProperties,
                "workerIdentity.registrationKey"
        );
    }

    private String registrationKey(
            WorkerRegistrationKind workerKind,
            Map<String, Object> workerProperties,
            String operation
    ) {
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

    private String registerResolved(
            String workerGroupId,
            String registrationKey,
            String operation
    ) {
        try {
            if (workerCatalog.getWorkerGroupDescriptors(
                    List.of(workerGroupId)
            ).get(workerGroupId) == null) {
                throw failure(
                        ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                        operation,
                        "WorkerGroup was not found",
                        null
                );
            }
            String workerId = registry.register(
                    workerGroupId,
                    registrationKey
            );
            if (!isCanonicalUuid(workerId)) {
                throw failure(
                        ServerErrorCode.WORKER_IDENTITY_CONFLICT,
                        operation,
                        "Stored Worker identity is invalid",
                        null
                );
            }
            return workerId;
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(
                    ServerErrorCode.WORKER_IDENTITY_UNAVAILABLE,
                    operation,
                    null,
                    error
            );
        }
    }

    private void requireResolvedRegistration(
            String workerGroupId,
            String registrationKey,
            String workerId,
            String operation
    ) {
        if (!isCanonicalUuid(workerId)) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerId must be a canonical UUID",
                    null
            );
        }
        try {
            if (!registry.matches(
                    workerGroupId,
                    registrationKey,
                    workerId
            )) {
                throw failure(
                        ServerErrorCode.WORKER_IDENTITY_NOT_FOUND,
                        operation,
                        "Worker identity was not registered",
                        null
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(
                    ServerErrorCode.WORKER_IDENTITY_UNAVAILABLE,
                    operation,
                    null,
                    error
            );
        }
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
        if (!(rawLine instanceof Byte
                || rawLine instanceof Short
                || rawLine instanceof Integer
                || rawLine instanceof Long)) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties.labInventoryLine must be an integer",
                    null
            );
        }
        long line = ((Number) rawLine).longValue();
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
        if (workerProperties == null) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties must be present",
                    null
            );
        }
        Object value = workerProperties.get("clientWorkerKey");
        if (!(value instanceof String)
                || ((String) value).isBlank()) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                    operation,
                    "workerProperties.clientWorkerKey must be a "
                            + "non-blank string",
                    null
            );
        }
        return (String) value;
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
