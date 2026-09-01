package com.xa.mass.server.api.v1.controller;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.contract.ApiErrorResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.TaskPreviewResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBatchGetResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupPreviewResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerNetworkObserveResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerPreviewResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerSchedulingObserveResponse;
import com.xa.mass.server.runtimeview.RuntimeViewService;
import com.xa.mass.server.runtimeview.WorkerNetworkObservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@Tag(name = ApiTags.RUNTIME_VIEW)
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
            @RequestBody
            @NotNull @Min(1) @Max(100) Integer sampleLimit,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.previewTasks(
                sampleLimit,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(array = @ArraySchema(
                            minItems = 1,
                            maxItems = 20,
                            schema = @Schema(type = "string", minLength = 1)
                    ))
            )
            @RequestBody
            @NotNull @Size(min = 1, max = 20)
            List<@NotBlank String> workerGroupIds,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.batchGetWorkerGroups(
                List.copyOf(workerGroupIds),
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
            @RequestBody
            @NotNull @Min(1) @Max(100) Integer sampleLimit,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.previewWorkerGroups(
                sampleLimit,
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
            @RequestBody
            @NotNull @Min(1) @Max(100) Integer sampleLimit,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.previewWorkers(
                workerGroupId,
                sampleLimit,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(array = @ArraySchema(
                            minItems = 1,
                            maxItems = 100,
                            schema = @Schema(type = "string", minLength = 1)
                    ))
            )
            @RequestBody
            @NotNull @Size(min = 1, max = 100)
            List<@NotBlank String> workerIds,
            HttpServletRequest httpRequest
    ) {
        return runtimeView.observeWorkerScheduling(
                workerGroupId,
                List.copyOf(workerIds),
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(array = @ArraySchema(
                            minItems = 1,
                            maxItems = 100,
                            schema = @Schema(type = "string", minLength = 1)
                    ))
            )
            @RequestBody
            @NotNull @Size(min = 1, max = 100)
            List<@NotBlank String> workerIds,
            HttpServletRequest httpRequest
    ) {
        return workerNetwork.observe(
                endpointManagerId,
                List.copyOf(workerIds),
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
