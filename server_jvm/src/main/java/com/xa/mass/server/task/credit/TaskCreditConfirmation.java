package com.xa.mass.server.task.credit;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.server.credits.CreditModels;
import tools.jackson.databind.JsonNode;

public record TaskCreditConfirmation(
        @io.swagger.v3.oas.annotations.media.Schema(format = "uuid", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) String requestId,
        @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "9007199254740991", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) long expectedSeedCount,
        @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "9007199254740991", requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) long expectedAmount) {
    public TaskCreditConfirmation {
        requestId = CreditModels.requestId(requestId);
        if (expectedSeedCount < 1 || expectedSeedCount > CreditModels.MAX_POINTS
                || expectedAmount < 1 || expectedAmount > CreditModels.MAX_POINTS)
            throw new IllegalArgumentException("Credit confirmation requires positive safe integers");
    }
    @JsonCreator public static TaskCreditConfirmation fromJson(@JsonProperty("requestId") String requestId,
            @JsonProperty("expectedSeedCount") JsonNode count, @JsonProperty("expectedAmount") JsonNode amount) {
        return new TaskCreditConfirmation(requestId, positive(count), positive(amount));
    }
    private static long positive(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("Credit confirmation requires integer values");
        return value.longValue();
    }
    @JsonAnySetter public void rejectUnknown(String field, Object ignored) {
        throw new IllegalArgumentException("Unsupported credit confirmation field: " + field);
    }
}
