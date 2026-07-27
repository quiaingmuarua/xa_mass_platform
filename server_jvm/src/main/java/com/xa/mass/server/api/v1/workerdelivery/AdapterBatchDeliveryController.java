package com.xa.mass.server.api.v1.workerdelivery;

import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.AcceptedCountResponse;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.SeedResultBatchRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.SeedResultRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandConsumeRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandConsumeResponse;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryAccessPolicy;
import com.xa.mass.server.workerdelivery.application.WorkerDeliveryService;
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
public class AdapterBatchDeliveryController {

    private final WorkerDeliveryService workerDelivery;
    private final WorkerDeliveryAccessPolicy accessPolicy;

    public AdapterBatchDeliveryController(
            WorkerDeliveryService workerDelivery,
            WorkerDeliveryAccessPolicy accessPolicy
    ) {
        this.workerDelivery = workerDelivery;
        this.accessPolicy = accessPolicy;
    }

    @PostMapping("/commands:consume")
    public WorkerCommandConsumeResponse consumeWorkerCommands(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody WorkerCommandConsumeRequest request
    ) {
        accessPolicy.requireHttpAccess(endpointManagerId);
        var page = workerDelivery.consumeWorkerCommands(
                endpointManagerId,
                request.cursor(),
                request.scanCount()
        );
        return new WorkerCommandConsumeResponse(
                page.workerCommandsByWorkerId(),
                page.nextCursor()
        );
    }

    @PostMapping("/results:append")
    public ResponseEntity<AcceptedCountResponse> appendAdapterResults(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody SeedResultBatchRequest request
    ) {
        accessPolicy.requireHttpAccess(endpointManagerId);
        int acceptedCount = workerDelivery.appendAdapterResults(
                endpointManagerId,
                request.results().stream()
                        .map(SeedResultRequest::toSeedResult)
                        .toList()
        );
        return ResponseEntity.accepted().body(
                new AcceptedCountResponse(acceptedCount)
        );
    }
}
