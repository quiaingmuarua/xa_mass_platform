package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_RESOURCES)
@RestController
@RequestMapping("/api/v1/worker-groups")
public class ResourceCommandController {

    private final WorkerResourceCommandService workerResources;

    public ResourceCommandController(
            WorkerResourceCommandService workerResources
    ) {
        this.workerResources = workerResources;
    }

    @PatchMapping(
            "/{workerGroupId}/workers/{workerId}/platform-properties"
    )
    @Operation(summary = "Patch Worker platform properties")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Action outcome: applied or unchanged",
                    content = @Content(schema = @Schema(
                            implementation = ActionOutcome.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker resource request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker Resource Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public ActionOutcome patchPlatformProperties(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            type = "object",
                            additionalProperties = Schema
                                    .AdditionalPropertiesValue.TRUE
                    ))
            )
            @RequestBody @NotNull
            Map<String, @Nullable Object> properties
    ) {
        if (properties.size() == 1
                && properties.get("properties") instanceof Map<?, ?>) {
            throw new ServerException(
                    ServerErrorCode.MALFORMED_REQUEST,
                    "workerResource.patchPlatformProperties",
                    "Legacy properties envelope is not supported",
                    null
            );
        }
        return workerResources.patchPlatformProperties(
                workerGroupId,
                workerId,
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(properties)
                )
        );
    }
}
