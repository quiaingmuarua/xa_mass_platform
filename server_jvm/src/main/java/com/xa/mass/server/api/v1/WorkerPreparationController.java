package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.api.v1.model.WorkerPreparationRequest;
import com.xa.mass.server.api.v1.model.WorkerPreparationResponse;
import com.xa.mass.server.workerpreparation.WorkerPreparationService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_RESOURCES)
@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerPreparationController {

    private final WorkerPreparationService preparations;

    public WorkerPreparationController(
            WorkerPreparationService preparations
    ) {
        this.preparations = preparations;
    }

    @PostMapping("/{workerGroupId}/workers:prepare")
    @Operation(summary = "Prepare a Worker identity and Endpoint Binding")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Worker preparation completed",
                    content = @Content(schema = @Schema(
                            implementation = WorkerPreparationResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker preparation request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker preparation Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerPreparationResponse prepareWorker(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerPreparationRequest request
    ) {
        return WorkerPreparationResponse.from(preparations.prepare(
                workerGroupId,
                request.transportType(),
                request.workerProperties()
        ));
    }
}
