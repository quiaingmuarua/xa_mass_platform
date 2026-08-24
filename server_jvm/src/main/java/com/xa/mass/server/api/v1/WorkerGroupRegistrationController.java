package com.xa.mass.server.api.v1;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.WorkerGroupRegisterRequest;
import com.xa.mass.server.api.v1.model.WorkerGroupRegisterResponse;
import com.xa.mass.server.workergroup.WorkerGroupRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_RESOURCES)
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerGroupRegistrationController {

    private final WorkerGroupRegistrationService registrations;

    public WorkerGroupRegistrationController(
            WorkerGroupRegistrationService registrations
    ) {
        this.registrations = registrations;
    }

    @Operation(
            summary = "Register a WorkerGroup and ensure its managed Task"
    )
    @PostMapping("/{workerGroupId}:register")
    public WorkerGroupRegisterResponse registerWorkerGroup(
            @PathVariable String workerGroupId,
            @RequestBody WorkerGroupRegisterRequest request
    ) {
        WorkerGroupRegistrationService.Registration registration =
                registrations.register(
                        workerGroupId,
                        request.attributes(),
                        request.eventCodes()
                );
        return new WorkerGroupRegisterResponse(
                registration.workerGroupId(),
                registration.taskId(),
                registration.status()
        );
    }
}
