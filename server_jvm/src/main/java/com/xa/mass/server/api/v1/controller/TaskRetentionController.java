package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.retention.TaskRetentionService;
import com.xa.mass.server.api.ApiTags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@Tag(name = ApiTags.TASKS)
@RequestMapping("/api/v1/tasks")
public class TaskRetentionController {
    private final TaskRetentionService retention;
    public TaskRetentionController(TaskRetentionService retention) { this.retention = retention; }
    @PostMapping("/{taskId}/retention:run")
    @Operation(summary = "Advance eligible finite Task retention", description = "Administrator maintenance permission required. Uses the configured grace and retention periods; cannot force early deletion.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Task retention advanced or unchanged"),
            @ApiResponse(responseCode = "400", description = "Task is missing, in conflict, or does not support retention", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Retention outcome is unconfirmed; retry uses saved progress", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public TaskRetentionService.Result run(@Parameter(hidden = true) TaskCaller caller, @PathVariable @NotBlank String taskId) {
        return retention.run(caller, taskId);
    }
}
