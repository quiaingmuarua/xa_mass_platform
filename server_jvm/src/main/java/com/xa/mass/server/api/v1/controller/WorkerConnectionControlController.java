package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerConnectionControlRequest;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.worker.connection.WorkerConnectionControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = ApiTags.WORKER_RESOURCES)
@RequestMapping("/api/v1/worker-groups/{workerGroupId}/workers/{workerId}/connection-control")
@ApiResponses({@ApiResponse(responseCode = "200", description = "Current control view", content = @Content(schema = @Schema(implementation = WorkerConnectionControlService.View.class))),
        @ApiResponse(responseCode = "400", description = "Invalid or unsupported Worker control", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Control storage or disconnect is unavailable or unconfirmed", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))})
public final class WorkerConnectionControlController {
    private final WorkerConnectionControlService service;
    public WorkerConnectionControlController(WorkerConnectionControlService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Read Worker connection control (administrator)",
            description = "A time-derived policy view, not proof of physical connection closure or scheduling availability.")
    public WorkerConnectionControlService.View get(@Parameter(hidden = true) TaskCaller caller,
            @PathVariable String workerGroupId, @PathVariable String workerId) {
        return service.get(caller, workerGroupId, workerId);
    }

    @PutMapping
    @ApiResponse(responseCode = "409", description = "Current control changed or request identity conflicts", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @Operation(summary = "Set or release Worker connection cooldown (administrator)",
            description = "Null cooldownUntilMillis releases admission only. Keep the full request on 503; read and explicitly reconcile. "
                    + "An identical current request can repeat route revocation without extending the deadline.")
    public WorkerConnectionControlService.View put(@Parameter(hidden = true) TaskCaller caller,
            @PathVariable String workerGroupId, @PathVariable String workerId, @RequestBody WorkerConnectionControlRequest request) {
        caller.requireAdmin();
        return service.put(caller, workerGroupId, workerId, request.normalized());
    }
}
