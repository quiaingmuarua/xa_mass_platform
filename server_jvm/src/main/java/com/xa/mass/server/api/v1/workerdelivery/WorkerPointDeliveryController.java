package com.xa.mass.server.api.v1.workerdelivery;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.AcceptedResponse;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerResultRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandResponse;
import com.xa.mass.server.delivery.application.WorkerDeliveryService;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_DELIVERY)
@Validated
@RestController
@RequestMapping(
        "/api/v1/worker-delivery/endpoint-managers/{endpointManagerId}"
)
public class WorkerPointDeliveryController {

    private final WorkerDeliveryService workerDelivery;

    public WorkerPointDeliveryController(
            WorkerDeliveryService workerDelivery
    ) {
        this.workerDelivery = workerDelivery;
    }

    @PostMapping("/workers/{workerId}/commands:poll")
    @Operation(summary = "Poll one Worker command")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "A Worker command is available",
                    content = @Content(schema = @Schema(
                            implementation = WorkerCommandResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "204",
                    description = "No Worker command is available",
                    content = @Content
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
    public ResponseEntity<WorkerCommandResponse> pollWorkerCommand(
            @PathVariable @NotBlank String endpointManagerId,
            @PathVariable @NotBlank String workerId
    ) {
        DeliveryCommand command = workerDelivery.pollWorkerCommand(
                endpointManagerId,
                workerId
        );
        return command == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(WorkerCommandResponse.from(command));
    }

    @PostMapping("/workers/{workerId}/results")
    @Operation(summary = "Append one Worker result")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Worker result was accepted",
                    content = @Content(schema = @Schema(
                            implementation = AcceptedResponse.class
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
    public ResponseEntity<AcceptedResponse> appendWorkerResult(
            @PathVariable @NotBlank String endpointManagerId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerResultRequest request
    ) {
        workerDelivery.appendWorkerResult(
                endpointManagerId,
                workerId,
                request.toDeliveryReport()
        );
        return ResponseEntity.accepted().body(new AcceptedResponse(true));
    }

    @PostMapping("/workers/{workerId}:verify-binding")
    @Operation(summary = "Verify one Worker Endpoint Binding")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "Worker Binding was verified",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker Binding verification was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker Binding Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public ResponseEntity<Void> verifyWorkerRoute(
            @PathVariable @NotBlank String endpointManagerId,
            @PathVariable @NotBlank String workerId
    ) {
        workerDelivery.verifyWorkerRoute(endpointManagerId, workerId);
        return ResponseEntity.noContent().build();
    }
}
