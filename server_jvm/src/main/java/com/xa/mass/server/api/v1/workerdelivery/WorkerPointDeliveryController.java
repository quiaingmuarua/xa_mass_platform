package com.xa.mass.server.api.v1.workerdelivery;

import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.AcceptedResponse;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerResultRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandResponse;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<Void> verifyWorkerRoute(
            @PathVariable @NotBlank String endpointManagerId,
            @PathVariable @NotBlank String workerId
    ) {
        workerDelivery.verifyWorkerRoute(endpointManagerId, workerId);
        return ResponseEntity.noContent().build();
    }
}
