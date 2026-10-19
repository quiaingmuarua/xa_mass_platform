package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest;
import com.xa.mass.server.task.call.TaskRpcPreparationService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.task.directory.TaskDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.TASKS)
@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskControlController {

    private final TaskCreationService taskCreation;
    private final TaskLifecycleService taskLifecycle;
    private final TaskRpcPreparationService rpcPreparation;
    private final TaskDirectory directory;

    public TaskControlController(
            TaskCreationService taskCreation,
            TaskLifecycleService taskLifecycle,
            TaskRpcPreparationService rpcPreparation,
            TaskDirectory directory
    ) {
        this.taskCreation = taskCreation;
        this.taskLifecycle = taskLifecycle;
        this.rpcPreparation = rpcPreparation;
        this.directory = directory;
    }

    @Operation(summary = "Confirm a known Task's directory entry from its immutable Server metadata",
            description = "Does not create, approve, close or append work. No caller-supplied attribution is accepted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Directory entry applied or unchanged",
                    content = @Content(schema = @Schema(implementation = ActionOutcome.class))),
            @ApiResponse(responseCode = "400", description = "Task is missing or has no valid Server attribution",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Task read or directory write not confirmed",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{taskId}/directory:ensure")
    public ActionOutcome ensureDirectory(@io.swagger.v3.oas.annotations.Parameter(hidden = true) TaskCaller caller, @PathVariable @NotBlank String taskId) {
        return directory.ensure(caller, taskId);
    }

    @Operation(summary = "Create or reuse and enable an RPC Task",
            description = "Caller supplies a stable Task ID. Exact existing configuration is reused without resetting work. "
                    + "Creation and approval are separate commits; on 503 retain this identity and explicitly reconcile.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "RPC Task approval confirmed",
                    content = @Content(schema = @Schema(implementation = TaskCreateResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request or conflicting Task",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Preparation or approval not confirmed",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/{taskId}/rpc:prepare")
    public TaskCreateResponse prepareRpcTask(@io.swagger.v3.oas.annotations.Parameter(hidden = true) TaskCaller caller, @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskRpcPrepareRequest request) {
        return rpcPreparation.prepare(caller, TaskCaller.RAW_INPUT, taskId, request);
    }

    @Operation(summary = "Create a finite Task")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Task was created",
                    content = @Content(schema = @Schema(
                            implementation = TaskCreateResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping
    public TaskCreateResponse createTask(@io.swagger.v3.oas.annotations.Parameter(hidden = true) TaskCaller caller,
            @Valid @RequestBody TaskCreateRequest request
    ) {
        return taskCreation.create(caller, request);
    }

    @Operation(summary = "Approve a finite Task")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Action outcome: applied or unchanged",
                    content = @Content(schema = @Schema(
                            implementation = ActionOutcome.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{taskId}/approve")
    public ActionOutcome approveTask(@io.swagger.v3.oas.annotations.Parameter(hidden = true) TaskCaller caller,
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody com.xa.mass.server.api.v1.contract.task.TaskApproveRequest request
    ) {
        return taskLifecycle.approve(caller, taskId, request.expectedItemCount(), request.credits());
    }

    @Operation(summary = "Close a finite Task")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Action outcome: applied or unchanged",
                    content = @Content(schema = @Schema(
                            implementation = ActionOutcome.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Task business request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Task Owner is temporarily unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    @PostMapping("/{taskId}/close")
    public ActionOutcome closeTask(@io.swagger.v3.oas.annotations.Parameter(hidden = true) TaskCaller caller,
            @PathVariable @NotBlank String taskId
    ) {
        return taskLifecycle.close(caller, taskId);
    }

}
