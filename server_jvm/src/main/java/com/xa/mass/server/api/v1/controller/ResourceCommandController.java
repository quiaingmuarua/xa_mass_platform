package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerPlatformPropertiesPatchResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerPropertiesPatchRequest;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_RESOURCES)
@Validated
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
                    description = "Worker platform properties patch completed",
                    content = @Content(schema = @Schema(
                            implementation = WorkerPlatformPropertiesPatchResponse.class
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
    public WorkerPlatformPropertiesPatchResponse patchPlatformProperties(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerPropertiesPatchRequest request
    ) {
        return new WorkerPlatformPropertiesPatchResponse(
                workerResources.patchPlatformProperties(
                        workerGroupId,
                        workerId,
                        request.properties()
                ).wireValue()
        );
    }
}
