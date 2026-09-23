package com.xa.mass.server.api.v1.contract.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskItemStateResponse(
        @Schema(allowableValues = {"active", "terminal"}, description =
                "Terminal for scheduling, not immutable business state. While the Item is retained, "
                        + "valid monotonic outcome observations may continue after Task completion or closure.")
        String band,
        @Schema(minimum = "1", maximum = "9") int tag,
        @Schema(description = "Band-local score time in milliseconds, not business event time")
        long timeMillis,
        @Nullable String outcomeName
) {
}
