package com.xa.mass.server.api.v1;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.WorkerPropertiesPatchRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class ResourceCommandController {

    private final WorkerResourceCatalog workerCatalog;

    public ResourceCommandController(
            WorkerResourceCatalog workerCatalog
    ) {
        this.workerCatalog = workerCatalog;
    }

    @PatchMapping(
            "/{workerGroupId}/workers/{workerId}/platform-properties"
    )
    public ResponseEntity<CommandResultResponse> patchPlatformProperties(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerPropertiesPatchRequest request
    ) {
        return response(workerCatalog.patchWorkerPlatformProperties(
                workerGroupId,
                workerId,
                request.properties()
        ));
    }

    private static ResponseEntity<CommandResultResponse> response(
            WorkerRuntimeResult result
    ) {
        HttpStatus httpStatus = switch (result.status()) {
            case OK, NOOP -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REJECTED, STALE, CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(httpStatus).body(
                new CommandResultResponse(
                        RuntimeCommandStatus.fromWireValue(
                                result.status().wireValue()
                        ),
                        result.reason()
                )
        );
    }
}
