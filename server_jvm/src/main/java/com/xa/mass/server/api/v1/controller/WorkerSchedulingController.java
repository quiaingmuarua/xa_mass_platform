package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
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
    @Operation(summary = "Pause Worker scheduling")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Worker scheduling pause completed",
                    content = @Content(schema = @Schema(
                            implementation =
                                    WorkerSchedulingService.PauseStatus.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker resource request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker scheduling Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerSchedulingService.PauseStatus pauseScheduling(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId
    ) {
        return workerScheduling.pause(workerGroupId, workerId);
    }

    @PostMapping(
            "/{workerGroupId}/workers/{workerId}:resume-scheduling"
    )
    @Operation(summary = "Resume Worker scheduling")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Worker scheduling resume completed",
                    content = @Content(schema = @Schema(
                            implementation =
                                    WorkerSchedulingService.ResumeStatus.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Worker resource request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Worker scheduling Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerSchedulingService.ResumeStatus resumeScheduling(
            @PathVariable @NotBlank String workerGroupId,
            @PathVariable @NotBlank String workerId
    ) {
        return workerScheduling.resume(workerGroupId, workerId);
    }
}
