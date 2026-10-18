package com.xa.mass.server.api.v1.contract.task;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;
import com.xa.mass.server.task.credit.TaskCreditConfirmation;

public record TaskApproveRequest(@Positive long expectedItemCount, @jakarta.validation.Valid @org.jspecify.annotations.Nullable TaskCreditConfirmation credits) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static TaskApproveRequest fromJson(@JsonProperty("expectedItemCount") JsonNode count, @JsonProperty("credits") TaskCreditConfirmation credits) {
        if (count == null || !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() <= 0)
            throw new IllegalArgumentException("expectedItemCount must be a positive integer");
        return new TaskApproveRequest(count.longValue(), credits);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported Task approval field: " + field);
    }
}
