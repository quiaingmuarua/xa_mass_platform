package com.xa.mass.server.api.v1.contract.worker;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.connection.WorkerConnectionControlStore;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

public record WorkerConnectionControlRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128) String requestId,
        @Schema(nullable = true, maxLength = 128) String expectedRevision,
        @Schema(implementation = Long.class, type = "integer", format = "int64", minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED,
                maximum = "9007199254740991", nullable = true, description = "Absolute deadline; null releases admission")
        JsonNode cooldownUntilMillis,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 256) String reason) {
    public WorkerConnectionControlStore.Change normalized() {
        try {
            if (cooldownUntilMillis == null) throw new IllegalArgumentException("Explicit deadline or null is required");
            if (cooldownUntilMillis != null && !cooldownUntilMillis.isNull()
                    && (!cooldownUntilMillis.isIntegralNumber() || !cooldownUntilMillis.canConvertToLong()))
                throw new IllegalArgumentException("Deadline must be an integer or null");
            return new WorkerConnectionControlStore.Change(requestId, expectedRevision,
                    cooldownUntilMillis == null || cooldownUntilMillis.isNull() ? null : cooldownUntilMillis.longValue(), reason);
        } catch (IllegalArgumentException error) {
            throw new ServerException(ServerErrorCode.INVALID_WORKER_CONNECTION_CONTROL, "workerConnectionControl.request", null, error);
        }
    }
}
