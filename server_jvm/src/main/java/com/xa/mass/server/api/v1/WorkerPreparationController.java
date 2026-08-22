package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.model.WorkerPreparationRequest;
import com.xa.mass.server.api.v1.model.WorkerPreparationResponse;
import com.xa.mass.server.workerpreparation.WorkerPreparationService;
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
public class WorkerPreparationController {

    private final WorkerPreparationService preparations;

    public WorkerPreparationController(
            WorkerPreparationService preparations
    ) {
        this.preparations = preparations;
    }

    @PostMapping("/{workerGroupId}/workers:prepare")
    public WorkerPreparationResponse prepareWorker(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerPreparationRequest request
    ) {
        return WorkerPreparationResponse.from(preparations.prepare(
                workerGroupId,
                request.transportType(),
                request.workerProperties()
        ));
    }
}
