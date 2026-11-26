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
            summary = "Remove a WorkerGroup when no non-terminal Tasks or active Workers use it"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "WorkerGroup and its offline Runtime keys were removed",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupDeleteResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid workerGroupId, missing group, or blocked by non-terminal Tasks or active Workers",
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
                    description = "WorkerGroup Runtime cleanup could not complete; retry DELETE safely",
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
            summary = "Remove a single offline Worker's Runtime state from its WorkerGroup"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Offline Worker Runtime state was removed",
                    content = @Content(schema = @Schema(
                            implementation = WorkerDeleteResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid request, Worker not found in WorkerGroup, or Worker is still active",
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
                    description = "Worker Runtime Owner is unavailable",
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
