package com.xa.mass.server.api.v1;

import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.workerscheduling.WorkerSchedulingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = ApiTags.WORKER_RESOURCES)
@Validated
@RestController
@RequestMapping("/api/v1/worker-groups")
public class WorkerSchedulingController {

    private final WorkerSchedulingService workerScheduling;

    public WorkerSchedulingController(
            WorkerSchedulingService workerScheduling
    ) {
        this.workerScheduling = workerScheduling;
    }

    @PostMapping(
            "/{workerGroupId}/workers/{workerId}:pause-scheduling"
    )
    public ResponseEntity<CommandResultResponse> pauseScheduling(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId
    ) {
        return response(workerScheduling.pause(
                workerGroupId,
                workerId
        ));
    }

    @PostMapping(
            "/{workerGroupId}/workers/{workerId}:resume-scheduling"
    )
    public ResponseEntity<CommandResultResponse> resumeScheduling(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId
    ) {
        return response(workerScheduling.resume(
                workerGroupId,
                workerId
        ));
    }

    private static ResponseEntity<CommandResultResponse> response(
            WorkerScoreTransitionStatus status
    ) {
        HttpStatus httpStatus = switch (status) {
            case TRANSITIONED, NOOP -> HttpStatus.OK;
            case STALE -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        RuntimeCommandStatus responseStatus = switch (status) {
            case TRANSITIONED -> RuntimeCommandStatus.OK;
            case NOOP -> RuntimeCommandStatus.NOOP;
            case STALE -> RuntimeCommandStatus.STALE;
            case INVALID -> RuntimeCommandStatus.INVALID;
        };
        return ResponseEntity.status(httpStatus).body(
                new CommandResultResponse(responseStatus, null)
        );
    }
}
