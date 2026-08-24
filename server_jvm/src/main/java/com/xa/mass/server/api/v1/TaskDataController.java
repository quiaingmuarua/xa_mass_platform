package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.taskdata.TaskDataService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
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
public class TaskDataController {

    private final TaskDataService taskData;

    public TaskDataController(TaskDataService taskData) {
        this.taskData = taskData;
    }

    @PostMapping("/{taskId}/items")
    public ResponseEntity<TaskItemsAppendResponse> appendTaskItems(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskItemsAppendRequest request
    ) {
        return ResponseEntity.ok(taskData.appendFiniteTaskItems(
                taskId,
                request
        ));
    }

    @PostMapping("/{taskId}/results:load")
    public ResponseEntity<TaskItemResultsLoadResponse> loadTaskItemResults(
            @PathVariable @NotBlank String taskId,
            @Valid @RequestBody TaskItemResultsLoadRequest request
    ) {
        return ResponseEntity.ok(taskData.loadFiniteTaskItemSuccessResults(
                taskId,
                request.messageIds()
        ));
    }
}
