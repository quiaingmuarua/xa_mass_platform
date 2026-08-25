package com.xa.mass.server.api.v1.runtimeview;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.model.ApiErrorResponse;
import com.xa.mass.server.api.v1.runtimeview.model.TaskPreviewRequest;
import com.xa.mass.server.api.v1.runtimeview.model.TaskPreviewResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupBatchGetRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupBatchGetResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupPreviewRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupPreviewResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerNetworkObserveRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerNetworkObserveResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerPreviewRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerPreviewResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerSchedulingObserveRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerSchedulingObserveResponse;
import com.xa.mass.server.runtimeview.RuntimeViewService;
import com.xa.mass.server.runtimeview.WorkerNetworkObservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@Tag(name = ApiTags.RUNTIME_VIEW)
@Validated
@RestController
@RequestMapping("/api/v1/runtime-view")
public class RuntimeViewController {

    private final RuntimeViewService runtimeView;
    private final WorkerNetworkObservationService workerNetwork;

    public RuntimeViewController(
            RuntimeViewService runtimeView,
            WorkerNetworkObservationService workerNetwork
    ) {
        this.runtimeView = runtimeView;
        this.workerNetwork = workerNetwork;
    }

    @PostMapping("/tasks:preview")
    @Operation(summary = "Preview a bounded Task Score window")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Bounded Task runtime projections",
                    content = @Content(schema = @Schema(
                            implementation = TaskPreviewResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Runtime View request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Runtime View Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public TaskPreviewResponse previewTasks(
            @Valid @RequestBody TaskPreviewRequest request,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.previewTasks(
                request.sampleLimit(),
                requestId(httpRequest)
        );
    }

    @PostMapping("/worker-groups:batch-get")
    @Operation(summary = "Load known WorkerGroups by ID")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "WorkerGroup projections and missing IDs",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupBatchGetResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Runtime View request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Runtime View Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerGroupBatchGetResponse batchGetWorkerGroups(
            @Valid @RequestBody WorkerGroupBatchGetRequest request,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.batchGetWorkerGroups(
                request.workerGroupIds(),
                requestId(httpRequest)
        );
    }

    @PostMapping("/worker-groups:preview")
    @Operation(summary = "Preview a bounded WorkerGroup sample")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Bounded WorkerGroup sample projection",
                    content = @Content(schema = @Schema(
                            implementation = WorkerGroupPreviewResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Runtime View request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Runtime View Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerGroupPreviewResponse previewWorkerGroups(
            @Valid @RequestBody WorkerGroupPreviewRequest request,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.previewWorkerGroups(
                request.sampleLimit(),
                requestId(httpRequest)
        );
    }

    @PostMapping(
            "/worker-groups/{workerGroupId}/workers:preview"
    )
    @Operation(summary = "Preview a bounded Worker sample")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Bounded Worker sample projection",
                    content = @Content(schema = @Schema(
                            implementation = WorkerPreviewResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Runtime View request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Runtime View Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerPreviewResponse previewWorkers(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerPreviewRequest request,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.previewWorkers(
                workerGroupId,
                request.sampleLimit(),
                request.filter(),
                requestId(httpRequest)
        );
    }

    @PostMapping(
            "/worker-groups/{workerGroupId}/workers:scheduling-observe"
    )
    @Operation(summary = "Observe bounded Worker scheduling projections")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Worker scheduling projections",
                    content = @Content(schema = @Schema(
                            implementation = WorkerSchedulingObserveResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Runtime View request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Runtime View Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public WorkerSchedulingObserveResponse observeWorkerScheduling(
            @PathVariable @NotBlank String workerGroupId,
            @Valid @RequestBody WorkerSchedulingObserveRequest request,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.observeWorkerScheduling(
                workerGroupId,
                request.workerIds(),
                requestId(httpRequest)
        );
    }

    @PostMapping(
            "/endpoint-managers/{endpointManagerId}/workers:network-observe"
    )
    @Operation(summary = "Observe bounded Worker network projections")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Worker network projections",
                    content = @Content(schema = @Schema(
                            implementation = WorkerNetworkObserveResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Runtime View request was rejected",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Runtime View Owner is unavailable",
                    content = @Content(schema = @Schema(
                            implementation = ApiErrorResponse.class
                    ))
            )
    })
    public DeferredResult<WorkerNetworkObserveResponse> observeWorkerNetwork(
            @PathVariable @NotBlank String endpointManagerId,
            @Valid @RequestBody WorkerNetworkObserveRequest request,
            HttpServletRequest httpRequest
    ) {
        return workerNetwork.observe(
                endpointManagerId,
                request.workerIds(),
                requestId(httpRequest)
        );
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(
                RequestIdFilter.ATTRIBUTE_NAME
        );
        return value instanceof String requestId ? requestId : null;
    }
}
