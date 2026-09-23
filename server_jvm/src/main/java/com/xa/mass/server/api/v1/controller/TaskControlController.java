package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskLifecycleService;
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

    public TaskControlController(
            TaskCreationService taskCreation,
            TaskLifecycleService taskLifecycle
    ) {
        this.taskCreation = taskCreation;
        this.taskLifecycle = taskLifecycle;
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
    public TaskCreateResponse createTask(
            @Valid @RequestBody TaskCreateRequest request
    ) {
        return taskCreation.create(request);
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
    public ActionOutcome approveTask(
            @PathVariable @NotBlank String taskId
    ) {
        return taskLifecycle.approve(taskId);
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
    public ActionOutcome closeTask(
            @PathVariable @NotBlank String taskId
    ) {
        return taskLifecycle.close(taskId);
    }

}
