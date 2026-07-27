package com.xa.mass.server.api.v1;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashSet;
import org.springframework.http.HttpStatus;
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

    private final WorkerRuntime workerRuntime;
    private final WorkerResourceCatalog workerCatalog;

    public ResourceCommandController(
            WorkerRuntime workerRuntime,
            WorkerResourceCatalog workerCatalog
    ) {
        this.workerRuntime = workerRuntime;
        this.workerCatalog = workerCatalog;
    }

    @PutMapping("/{workerGroupId}")
    public ResponseEntity<CommandResultResponse> upsertWorkerGroup(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerGroupUpsertRequest request
    ) {
        return response(workerCatalog.upsertWorkerGroup(
                new WorkerGroupDescriptor(
                        workerGroupId,
                        request.attributes(),
                        new LinkedHashSet<>(request.eventCodes()),
                        new LinkedHashSet<>(
                                request.itemAllocationFields()
                        )
                )
        ));
    }

    @PutMapping("/{workerGroupId}/workers/{workerId}")
    public ResponseEntity<CommandResultResponse> upsertWorker(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerUpsertRequest request
    ) {
        return response(workerRuntime.upsertWorker(
                new WorkerDeclaration(
                        workerId,
                        workerGroupId,
                        request.endpointManagerId(),
                        request.attributes(),
                        new LinkedHashSet<>(
                                request.dynamicAttributeNames()
                        )
                )
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
