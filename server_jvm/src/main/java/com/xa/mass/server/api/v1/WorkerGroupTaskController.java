package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.api.v1.model.TaskCreateResponse;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.taskdata.TaskCreationService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallService;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.context.request.async.DeferredResult;

@Tag(name = ApiTags.TASKS)
@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerGroupTaskController {

    private final WorkerGroupTaskCallService taskCall;
    private final TaskCreationService taskCreation;

    public WorkerGroupTaskController(
            WorkerGroupTaskCallService taskCall,
            TaskCreationService taskCreation
    ) {
        this.taskCall = taskCall;
        this.taskCreation = taskCreation;
    }

    @PostMapping("/{workerGroupId}/tasks")
    public ResponseEntity<?> createTask(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody TaskCreateRequest request
    ) {
        TaskCreateResponse response = taskCreation.create(
                workerGroupId,
                request
        );
        HttpStatus status = switch (response.status()) {
            case CREATED -> HttpStatus.CREATED;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_CONTENT;
            case RETRYABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> throw new IllegalStateException(
                    "Unexpected Task creation status: " + response.status()
            );
        };
        Object body = response.status() == RuntimeCommandStatus.CREATED
                ? response
                : new CommandResultResponse(
                        response.status(),
                        response.reason()
                );
        return ResponseEntity.status(status).body(body);
    }

    @PostMapping("/{workerGroupId}/items:call")
    public DeferredResult<ResponseEntity<TaskRpcCallResponse>> callTaskItem(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody TaskRpcCallRequest request
    ) {
        return taskCall.call(workerGroupId, request);
    }

    @PostMapping("/{workerGroupId}/item-results:load")
    public ResponseEntity<TaskItemResultsLoadResponse> loadTaskItemResults(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody TaskItemResultsLoadRequest request
    ) {
        return ResponseEntity.ok(taskCall.loadSuccessResults(
                workerGroupId,
                request.messageIds()
        ));
    }
}
