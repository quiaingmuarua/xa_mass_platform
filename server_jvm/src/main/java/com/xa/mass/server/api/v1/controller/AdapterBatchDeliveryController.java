package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.delivery.WorkerDeliveryHttpContract.WorkerResultBatchResponse;
import com.xa.mass.server.api.v1.contract.delivery.WorkerDeliveryHttpContract.WorkerCommandResponse;
import com.xa.mass.server.delivery.application.WorkerDeliveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_DELIVERY)
@RestController
@RequestMapping(
        "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}"
)
public class AdapterBatchDeliveryController {

    private final WorkerDeliveryService workerDelivery;

    public AdapterBatchDeliveryController(
            WorkerDeliveryService workerDelivery
    ) {
        this.workerDelivery = workerDelivery;
    }

    @PostMapping("/commands:consume")
    @Operation(summary = "Consume an Adapter command batch")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Adapter command batch",
                    content = @Content(schema = @Schema(
                            type = "object",
                            additionalPropertiesSchema =
                                    WorkerCommandResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker Delivery request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker Delivery Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public Map<String, WorkerCommandResponse> consumeWorkerCommands(
            @PathVariable @NotBlank String endpointManagerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            type = "integer",
                            format = "int32",
                            minimum = "1"
                    ))
            )
            @RequestBody @NotNull @Positive Integer limit
    ) {
        var commands = workerDelivery.consumeWorkerCommands(
                endpointManagerId,
                limit
        );
        return WorkerCommandResponse.fromCommands(commands);
    }

    @PostMapping("/results:append")
    @Operation(summary = "Append an Adapter result batch")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Adapter result batch was accepted",
                    content = @Content(schema = @Schema(
                            implementation = WorkerResultBatchResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker Delivery request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker Delivery Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public ResponseEntity<WorkerResultBatchResponse> appendAdapterResults(
            @PathVariable @NotBlank String endpointManagerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(array = @ArraySchema(
                            minItems = 1,
                            maxItems = WorkerDeliveryService
                                    .MAX_ADAPTER_RESULT_BATCH_SIZE,
                            schema = @Schema(type = "string", minLength = 1)
                    ))
            )
            @RequestBody
            @NotNull @Size(
                    min = 1,
                    max = WorkerDeliveryService.MAX_ADAPTER_RESULT_BATCH_SIZE
            )
            List<@NotBlank String> results
    ) {
        var counts = workerDelivery.appendAdapterResults(
                endpointManagerId,
                List.copyOf(results)
        );
        return ResponseEntity.accepted().body(
                new WorkerResultBatchResponse(
                        counts.acceptedCount(),
                        counts.rejectedCount()
                )
        );
    }
}
