package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.v1.model.WorkerIdentityRegisterRequest;
import com.xa.mass.server.api.v1.model.WorkerIdentityRegisterResponse;
import com.xa.mass.server.workeridentity.WorkerIdentityService;
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
public class WorkerIdentityController {

    private final WorkerIdentityService identities;

    public WorkerIdentityController(WorkerIdentityService identities) {
        this.identities = identities;
    }

    @PostMapping("/{workerGroupId}/workers:register")
    public WorkerIdentityRegisterResponse registerWorkerIdentity(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerIdentityRegisterRequest request
    ) {
        return new WorkerIdentityRegisterResponse(
                identities.register(
                        workerGroupId,
                        request.clientWorkerKey()
                )
        );
    }
}
