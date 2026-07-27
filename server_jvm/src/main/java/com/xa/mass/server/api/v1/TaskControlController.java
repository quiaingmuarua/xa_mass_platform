package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.kernelclient.KernelCommandClient;
import com.xa.mass.server.kernelclient.KernelResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    private final KernelCommandClient kernelClient;

    public TaskControlController(KernelCommandClient kernelClient) {
        this.kernelClient = kernelClient;
    }

    @PostMapping
    public ResponseEntity<CommandResultResponse> createTask(
            @Valid @RequestBody TaskCreateRequest request
    ) {
        return commandResponse(kernelClient.createTask(request));
    }

    @PostMapping("/{taskId}/approve")
    public ResponseEntity<CommandResultResponse> approveTask(
            @PathVariable @NotBlank String taskId
    ) {
        return commandResponse(kernelClient.approveTask(taskId));
    }

    @PostMapping("/{taskId}/close")
    public ResponseEntity<CommandResultResponse> closeTask(
            @PathVariable @NotBlank String taskId
    ) {
        return commandResponse(kernelClient.closeTask(taskId));
    }

    private static ResponseEntity<CommandResultResponse> commandResponse(
            KernelResponse<CommandResultResponse> response
    ) {
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }
}
