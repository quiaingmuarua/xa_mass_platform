package com.xa.mass.server.workeridentity;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
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
            String clientWorkerKey
    ) {
        String operation = "workerIdentity.register";
        requireNonBlank(workerGroupId, "workerGroupId", operation);
        requireNonBlank(clientWorkerKey, "clientWorkerKey", operation);
        try {
            if (workerCatalog.getWorkerGroupDescriptors(
                    List.of(workerGroupId)
            ).get(workerGroupId) == null) {
                throw failure(
                        ServerErrorCode.WORKER_IDENTITY_NOT_FOUND,
                        operation,
                        "WorkerGroup was not found",
                        null
                );
            }
            String workerId = registry.register(
                    workerGroupId,
                    clientWorkerKey
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

    public void requireRegistration(
            String workerGroupId,
            String clientWorkerKey,
            String workerId
    ) {
        String operation = "workerIdentity.requireRegistration";
        requireNonBlank(workerGroupId, "workerGroupId", operation);
        requireNonBlank(clientWorkerKey, "clientWorkerKey", operation);
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
                    clientWorkerKey,
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
