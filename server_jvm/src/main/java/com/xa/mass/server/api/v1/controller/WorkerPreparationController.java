package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerBatchPreparationRequest;
import com.xa.mass.server.api.v1.contract.worker.WorkerBatchPreparationResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerPreparationRequest;
import com.xa.mass.server.api.v1.contract.worker.WorkerPreparationResponse;
import com.xa.mass.server.worker.preparation.WorkerPreparationService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return WorkerPreparationResponse.from(
                preparations.prepareAll(
                        workerGroupId,
                        request.workerKind(),
                        request.transportType(),
                        List.of(request.workerProperties())
                ).get(0)
        );
    }

    @PostMapping("/{workerGroupId}/workers:prepare-batch")
    @Operation(summary = "Prepare multiple Worker identities and bindings")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Worker batch preparation completed",
                    content = @Content(schema = @Schema(
                            implementation =
                                    WorkerBatchPreparationResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker batch preparation was rejected",
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
    public WorkerBatchPreparationResponse prepareWorkers(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerBatchPreparationRequest request
    ) {
        var first = request.workers().get(0);
        var workerProperties = new ArrayList<Map<String, Object>>();
        request.workers().forEach(item -> {
            if (item.workerKind() != first.workerKind()
                    || item.transportType() != first.transportType()) {
                throw new ServerException(
                        ServerErrorCode.INVALID_WORKER_IDENTITY_REQUEST,
                        "workerPreparation.prepareBatch",
                        "Batch Workers must use one workerKind and transportType",
                        null
                );
            }
            workerProperties.add(item.workerProperties());
        });
        return WorkerBatchPreparationResponse.from(
                preparations.prepareAll(
                        workerGroupId,
                        first.workerKind(),
                        first.transportType(),
                        workerProperties
                )
        );
    }
}
