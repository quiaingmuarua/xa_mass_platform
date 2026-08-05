package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.model.WorkerBindingRequest;
import com.xa.mass.server.api.v1.model.WorkerBindingResponse;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerBindingController {

    private final WorkerBindingService bindings;

    public WorkerBindingController(WorkerBindingService bindings) {
        this.bindings = bindings;
    }

    @PostMapping("/{workerGroupId}/workers/{workerId}:bind")
    public WorkerBindingResponse bindWorker(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId,
            @Valid @RequestBody WorkerBindingRequest request
    ) {
        return WorkerBindingResponse.from(bindings.bind(
                workerGroupId,
                request.clientWorkerKey(),
                workerId,
                request.transportType(),
                request.workerProperties()
        ));
    }
}
