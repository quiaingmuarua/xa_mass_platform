package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerDeleteResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupDeleteResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupDirectoryPageResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupRegisterRequest;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupRegisterResponse;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.worker.group.WorkerGroupManagementService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_RESOURCES)
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerGroupRegistrationController {

    private final WorkerGroupRegistrationService registrations;
    private final WorkerGroupManagementService management;

    public WorkerGroupRegistrationController(
            WorkerGroupRegistrationService registrations,
            WorkerGroupManagementService management
    ) {
        this.registrations = registrations;
        this.management = management;
    }

    @Operation(
            summary = "List registered WorkerGroups with startup protection and Kernel worker state counts"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated WorkerGroup directory returned",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupDirectoryPageResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid pagination parameters",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin role required",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "WorkerGroup Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @GetMapping
    public WorkerGroupDirectoryPageResponse listWorkerGroups(
            TaskCaller caller,
            @RequestParam(defaultValue = "0") String cursor,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return management.list(caller, cursor, limit);
    }

    @Operation(
            summary = "Register a WorkerGroup"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "WorkerGroup registration completed",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupRegisterResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "WorkerGroup declaration was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin role required",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "WorkerGroup Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{workerGroupId}:register")
    public WorkerGroupRegisterResponse registerWorkerGroup(
            TaskCaller caller,
            @PathVariable String workerGroupId,
            @RequestBody WorkerGroupRegisterRequest request
    ) {
        caller.requireAdmin();
        WorkerGroupRegistrationService.Registration registration =
                registrations.register(
                        workerGroupId,
                        request.attributes(),
                        request.eventCodes()
                );
        return new WorkerGroupRegisterResponse(
                registration.workerGroupId(),
                registration.status()
        );
    }

    @Operation(
            summary = "Update metadata (attributes and eventCodes) for a registered WorkerGroup"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "WorkerGroup metadata updated or unchanged",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupRegisterResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid WorkerGroup metadata request, missing group, or concurrent operation conflict",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin role required",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "WorkerGroup Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PutMapping("/{workerGroupId:[^:]+}")
    public WorkerGroupRegisterResponse updateWorkerGroup(
            TaskCaller caller,
            @PathVariable String workerGroupId,
            @RequestBody WorkerGroupRegisterRequest request
    ) {
        WorkerGroupRegistrationService.Registration updated = management.update(
                caller,
                workerGroupId,
                request.attributes(),
                request.eventCodes()
        );
        return new WorkerGroupRegisterResponse(updated.workerGroupId(), updated.status());
    }

    @Operation(
            summary = "Idempotently remove an idle WorkerGroup's Runtime state",
            description = "Stop affected Worker controllers and quiesce Task creation, approval and registration before maintenance. "
                    + "Missing state is already removed; concurrent re-registration is not supported."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "WorkerGroup cleanup completed, including already absent state",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupDeleteResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid workerGroupId or blocked by non-terminal Tasks or active Workers",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin role required",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "WorkerGroup Runtime cleanup is unconfirmed; retry DELETE with the same workerGroupId",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @DeleteMapping("/{workerGroupId:[^:]+}")
    public WorkerGroupDeleteResponse deleteWorkerGroup(
            TaskCaller caller,
            @PathVariable String workerGroupId
    ) {
        return management.deleteGroup(caller, workerGroupId);
    }

    @Operation(
            summary = "Idempotently remove a single offline Worker's Runtime state",
            description = "Stop the Worker controller and quiesce preparation/reconnection before removal. "
                    + "Missing state is already removed; a Binding to another Group is rejected."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Offline Worker cleanup completed, including already absent state",
                    content = @Content(schema = @Schema(
                            implementation = WorkerDeleteResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request, Worker is bound to another WorkerGroup, or Worker is still active",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Admin role required",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker Runtime cleanup is unconfirmed; retry DELETE with the same WorkerGroup and Worker IDs",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @DeleteMapping("/{workerGroupId:[^:]+}/workers/{workerId:[^:]+}")
    public WorkerDeleteResponse deleteOfflineWorker(
            TaskCaller caller,
            @PathVariable String workerGroupId,
            @PathVariable String workerId
    ) {
        return management.deleteOfflineWorker(caller, workerGroupId, workerId);
    }
}
