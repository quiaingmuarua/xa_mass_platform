package com.xa.mass.server.api.v1;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerIndexedPropertiesPatchRequest;
import com.xa.mass.server.api.v1.model.WorkerIndexedPropertiesPatchResponse;
import com.xa.mass.server.api.v1.model.WorkerPropertiesPatchRequest;
import com.xa.mass.server.api.v1.model.WorkerRegisterRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final WorkerPropertyIndexRuntime propertyIndex;

    public ResourceCommandController(
            WorkerRuntime workerRuntime,
            WorkerResourceCatalog workerCatalog,
            WorkerPropertyIndexRuntime propertyIndex
    ) {
        this.workerRuntime = workerRuntime;
        this.workerCatalog = workerCatalog;
        this.propertyIndex = propertyIndex;
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
                        new LinkedHashSet<>(request.eventCodes())
                )
        ));
    }

    @PutMapping("/{workerGroupId}/workers/{workerId}")
    public ResponseEntity<CommandResultResponse> registerWorker(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerRegisterRequest request
    ) {
        return response(workerRuntime.registerWorker(
                new WorkerDeclaration(
                        workerId,
                        workerGroupId,
                        request.endpointManagerId(),
                        request.workerProperties()
                )
        ));
    }

    @PutMapping(
            "/{workerGroupId}/workers/{workerId}/worker-properties"
    )
    public ResponseEntity<CommandResultResponse> updateWorkerProperties(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerPropertiesPatchRequest request
    ) {
        return response(workerRuntime.updateWorkerProperties(
                workerGroupId,
                workerId,
                request.properties()
        ));
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

    @PatchMapping(
            "/{workerGroupId}/workers/{workerId}/indexed-properties"
    )
    public WorkerIndexedPropertiesPatchResponse
    patchIndexedProperties(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerIndexedPropertiesPatchRequest request
    ) {
        return indexedResponse(propertyIndex.updateIndexedProperties(
                workerGroupId,
                workerId,
                request.updates()
        ));
    }

    private static WorkerIndexedPropertiesPatchResponse indexedResponse(
            java.util.Map<String, WorkerRuntimeResult> results
    ) {
        var converted = new LinkedHashMap<
                String,
                CommandResultResponse
                >();
        results.forEach((propertyName, result) -> converted.put(
                propertyName,
                new CommandResultResponse(
                        RuntimeCommandStatus.fromWireValue(
                                result.status().wireValue()
                        ),
                        result.reason()
                )
        ));
        return new WorkerIndexedPropertiesPatchResponse(converted);
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
