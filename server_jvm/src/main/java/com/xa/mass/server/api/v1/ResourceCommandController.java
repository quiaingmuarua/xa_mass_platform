package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;
import com.xa.mass.server.kernelclient.KernelCommandClient;
import com.xa.mass.server.kernelclient.KernelResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class ResourceCommandController {

    private final KernelCommandClient kernelClient;

    public ResourceCommandController(KernelCommandClient kernelClient) {
        this.kernelClient = kernelClient;
    }

    @PutMapping("/{workerGroupId}")
    public ResponseEntity<CommandResultResponse> upsertWorkerGroup(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerGroupUpsertRequest request
    ) {
        return response(kernelClient.upsertWorkerGroup(workerGroupId, request));
    }

    @PutMapping("/{workerGroupId}/workers/{workerId}")
    public ResponseEntity<CommandResultResponse> upsertWorker(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerUpsertRequest request
    ) {
        return response(
                kernelClient.upsertWorker(workerGroupId, workerId, request)
        );
    }

    private static ResponseEntity<CommandResultResponse> response(
            KernelResponse<CommandResultResponse> response
    ) {
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }
}
