package com.xa.mass.server.api.v1;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.server.kernelbinding.TaskLifecycleCommands.TaskCloseResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskControlController {

    private final TaskRuntime taskRuntime;
    private final TaskLifecycleCommands taskLifecycle;

    public TaskControlController(
            TaskRuntime taskRuntime,
            TaskLifecycleCommands taskLifecycle
    ) {
        this.taskRuntime = taskRuntime;
        this.taskLifecycle = taskLifecycle;
    }

    @PostMapping
    public ResponseEntity<CommandResultResponse> createTask(
            @Valid @RequestBody TaskCreateRequest request
    ) {
        TaskCreationResult result = taskRuntime.createTask(
                new TaskDescriptor(
                        request.taskId(),
                        request.workerGroupId(),
                        TaskRuntime.TaskType.valueOf(
                                request.taskType().name()
                        ),
                        request.allocationRule(),
                        request.config(),
                        request.emptyCloseAtMillis()
                ),
                0
        );
        HttpStatus status = switch (result.status()) {
            case CREATED -> HttpStatus.CREATED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RETRYABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(
                status,
                result.status().wireValue(),
                result.reason()
        );
    }

    @PostMapping("/{taskId}/approve")
    public ResponseEntity<CommandResultResponse> approveTask(
            @PathVariable @NotBlank String taskId
    ) {
        TaskApprovalResult result = taskLifecycle.approveTask(taskId);
        HttpStatus status = switch (result.status()) {
            case APPROVED, ALREADY_APPROVED -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RETRYABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(
                status,
                result.status().wireValue(),
                result.reason()
        );
    }

    @PostMapping("/{taskId}/close")
    public ResponseEntity<CommandResultResponse> closeTask(
            @PathVariable @NotBlank String taskId
    ) {
        TaskCloseResult result = taskLifecycle.closeTask(taskId);
        HttpStatus status = switch (result.status()) {
            case CLOSED, ALREADY_CLOSED -> HttpStatus.OK;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case RETRYABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return response(
                status,
                result.status().wireValue(),
                result.reason()
        );
    }

    private static ResponseEntity<CommandResultResponse> response(
            HttpStatus httpStatus,
            String status,
            String reason
    ) {
        return ResponseEntity.status(httpStatus).body(
                new CommandResultResponse(
                        RuntimeCommandStatus.fromWireValue(status),
                        reason
                )
        );
    }
}
