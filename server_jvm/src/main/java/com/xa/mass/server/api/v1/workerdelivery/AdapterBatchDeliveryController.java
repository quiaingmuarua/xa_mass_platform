package com.xa.mass.server.api.v1.workerdelivery;

import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerResultBatchRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerResultBatchResponse;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandConsumeRequest;
import com.xa.mass.server.api.v1.workerdelivery.WorkerDeliveryHttpContract.WorkerCommandConsumeResponse;
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

    public AdapterBatchDeliveryController(
            WorkerDeliveryService workerDelivery
    ) {
        this.workerDelivery = workerDelivery;
    }

    @PostMapping("/commands:consume")
    public WorkerCommandConsumeResponse consumeWorkerCommands(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody WorkerCommandConsumeRequest request
    ) {
        var commands = workerDelivery.consumeWorkerCommands(
                endpointManagerId,
                request.limit()
        );
        return WorkerCommandConsumeResponse.from(commands);
    }

    @PostMapping("/results:append")
    public ResponseEntity<WorkerResultBatchResponse> appendAdapterResults(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody WorkerResultBatchRequest request
    ) {
        var counts = workerDelivery.appendAdapterResults(
                endpointManagerId,
                request.results()
        );
        return ResponseEntity.accepted().body(
                new WorkerResultBatchResponse(
                        counts.acceptedCount(),
                        counts.rejectedCount()
                )
        );
    }
}
