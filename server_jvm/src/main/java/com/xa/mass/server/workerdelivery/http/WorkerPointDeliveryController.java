package com.xa.mass.server.workerdelivery.http;

import com.xa.mass.server.workerdelivery.WorkerDeliveryService;
import com.xa.mass.server.workerdelivery.http.WorkerDeliveryHttpContract.AcceptedResponse;
import com.xa.mass.server.workerdelivery.http.WorkerDeliveryHttpContract.SeedResultRequest;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
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
    public ResponseEntity<WorkerCommandEnvelope> pollWorkerCommand(
            @PathVariable @NotBlank String endpointManagerId,
            @PathVariable @NotBlank String workerId
    ) {
        WorkerCommandEnvelope command = workerDelivery.pollWorkerCommand(
                endpointManagerId,
                workerId
        );
        return command == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(command);
    }

    @PostMapping("/workers/{workerId}/results")
    public ResponseEntity<AcceptedResponse> appendWorkerResult(
            @PathVariable @NotBlank String endpointManagerId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody SeedResultRequest request
    ) {
        workerDelivery.appendWorkerResult(
                endpointManagerId,
                workerId,
                request.toSeedResult()
        );
        return ResponseEntity.accepted().body(new AcceptedResponse(true));
    }
}
