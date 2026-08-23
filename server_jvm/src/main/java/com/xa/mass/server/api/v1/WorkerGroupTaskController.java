package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerGroupTaskController {

    private final WorkerGroupTaskCallService taskCall;

    public WorkerGroupTaskController(
            WorkerGroupTaskCallService taskCall
    ) {
        this.taskCall = taskCall;
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
