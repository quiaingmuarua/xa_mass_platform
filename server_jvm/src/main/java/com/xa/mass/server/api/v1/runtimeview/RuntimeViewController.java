package com.xa.mass.server.api.v1.runtimeview;

import com.xa.mass.server.api.ApiTags;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.runtimeview.model.ConfiguredRuntimeResourcesResponse;
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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/configured-resources")
    public ConfiguredRuntimeResourcesResponse configuredResources(
            HttpServletRequest httpRequest
    ) {
        return runtimeView.configuredResources(requestId(httpRequest));
    }

    @PostMapping("/worker-groups:batch-get")
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
