package com.xa.mass.server.api.v1.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record TaskResultsExportRequest(
        @Schema(
                description = "Maximum terminal wait in milliseconds",
                defaultValue = "30000",
                minimum = "1",
                maximum = "300000"
        )
        @Nullable
        Long waitTimeoutMillis
) {
}
