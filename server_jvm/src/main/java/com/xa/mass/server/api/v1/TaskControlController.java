package com.xa.mass.server.api.v1;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseResult;
import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.TASKS)
@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskControlController {

    private final TaskLifecycleCommands taskLifecycle;
    private final TaskResourceCatalog taskCatalog;

    public TaskControlController(
            TaskLifecycleCommands taskLifecycle,
            TaskResourceCatalog taskCatalog
    ) {
        this.taskLifecycle = taskLifecycle;
        this.taskCatalog = taskCatalog;
    }

    @PostMapping("/{taskId}/approve")
    public ResponseEntity<CommandResultResponse> approveTask(
            @PathVariable @NotBlank String taskId
    ) {
        ResponseEntity<CommandResultResponse> rejected =
                rejectNonPublicTask(taskId);
        if (rejected != null) {
            return rejected;
        }
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
        ResponseEntity<CommandResultResponse> rejected =
                rejectNonPublicTask(taskId);
        if (rejected != null) {
            return rejected;
        }
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

    private ResponseEntity<CommandResultResponse> rejectNonPublicTask(
            String taskId
    ) {
        TaskDescriptor descriptor;
        try {
            descriptor = taskCatalog.loadTaskAllocationDescriptors(
                    List.of(taskId)
            ).get(taskId);
        } catch (RuntimeException error) {
            return response(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    RuntimeCommandStatus.RETRYABLE.wireValue(),
                    "Task catalog is unavailable"
            );
        }
        if (descriptor == null
                || descriptor.workerAllocationMechanism()
                != WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                || descriptor.idleDisposition()
                != TaskIdleDisposition.CLOSE_WHEN_IDLE) {
            return response(
                    HttpStatus.NOT_FOUND,
                    RuntimeCommandStatus.NOT_FOUND.wireValue(),
                    null
            );
        }
        return null;
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
