package com.xa.mass.server.worker.resource;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class WorkerResourceCommandService {

    private static final String PATCH_OPERATION =
            "workerResource.patchPlatformProperties";

    private final WorkerResourceCatalog workerCatalog;

    public WorkerResourceCommandService(
            WorkerResourceCatalog workerCatalog
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
    }

    public PatchStatus patchPlatformProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> properties
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        if (properties == null) {
            throw failure(ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST);
        }
        WorkerRuntimeResult result;
        try {
            result = workerCatalog.patchWorkerPlatformProperties(
                    workerGroupId,
                    workerId,
                    properties
            );
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                    PATCH_OPERATION,
                    null,
                    error
            );
        }
        if (result == null) {
            throw failure(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE);
        }
        return switch (result.status()) {
            case OK -> PatchStatus.UPDATED;
            case NOOP -> PatchStatus.UNCHANGED;
            case NOT_FOUND -> throw failure(
                    ServerErrorCode.WORKER_RESOURCE_NOT_FOUND
            );
            case INVALID -> throw failure(
                    ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST
            );
            case REJECTED, STALE, CONFLICT -> throw failure(
                    ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT
            );
        };
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw failure(ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST);
        }
    }

    private static ServerException failure(ServerErrorCode code) {
        return new ServerException(code, PATCH_OPERATION, null, null);
    }

    public enum PatchStatus {
        UPDATED("updated"),
        UNCHANGED("unchanged");

        private final String wireValue;

        PatchStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }
}
