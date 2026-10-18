package com.xa.mass.server.api.v1.contract.credits;

import com.xa.mass.server.credits.CreditModels;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record CreditAdjustmentRequest(
        @NotBlank @Schema(format = "uuid") String requestId,
        @NotNull @Schema(implementation = Long.class, type = "integer", format = "int64", minimum = "-9007199254740991", maximum = "9007199254740991", description = "Nonzero integer change; decimals and numeric strings are rejected") JsonNode delta,
        @NotBlank @Size(min = 1, max = 500) String reason) {
    public CreditModels.Adjustment normalized() {
        if (delta == null || !delta.isIntegralNumber() || !delta.canConvertToLong())
            throw new ServerException(ServerErrorCode.INVALID_CREDIT_REQUEST, "credits.validate", "delta must be an integer", null);
        return new CreditModels.Adjustment(requestId, delta.longValue(), reason);
    }
}
