package com.xa.mass.server.api.v1.runtimeview;

import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupBatchGetRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerGroupBatchGetResponse;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerPreviewRequest;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerPreviewResponse;
import com.xa.mass.server.runtimeview.RuntimeViewService;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/runtime-view")
public class RuntimeViewController {

    private final RuntimeViewService runtimeView;

    public RuntimeViewController(RuntimeViewService runtimeView) {
        this.runtimeView = runtimeView;
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

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(
                RequestIdFilter.ATTRIBUTE_NAME
        );
        return value instanceof String requestId ? requestId : null;
    }
}
