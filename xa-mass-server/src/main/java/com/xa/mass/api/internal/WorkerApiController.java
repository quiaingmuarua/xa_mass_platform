package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.sdk.TransportOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/status/api")
public class WorkerApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkerQueryOperations workerQueries;
    private final SdkMetadataCatalog metadataCatalog;
    private final TransportOperations transportOperations;

    public WorkerApiController(WorkerQueryOperations workerQueries) {
        this(workerQueries, (SdkMetadataCatalog) null, (TransportOperations) null);
    }

    public WorkerApiController(WorkerQueryOperations workerQueries,
                               SdkMetadataCatalog metadataCatalog,
                               TransportOperations transportOperations) {
        this.workerQueries = workerQueries;
        this.metadataCatalog = metadataCatalog;
        this.transportOperations = transportOperations;
    }

    @Autowired
    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ObjectProvider<SdkMetadataCatalog> metadataCatalogProvider,
                               ObjectProvider<TransportOperations> transportOperationsProvider) {
        this(
                workerQueries,
                metadataCatalogProvider == null ? null : metadataCatalogProvider.getIfAvailable(),
                transportOperationsProvider == null ? null : transportOperationsProvider.getIfAvailable()
        );
    }

    @GetMapping("/workers")
    public ApiResponse<Map<String, Object>> listWorkers() {
        Map<String, List<Map<String, Object>>> connectionsByWorker =
                WorkerCapabilityViewSupport.groupConnectionsByWorker(transportOperations);
        List<Map<String, Object>> items = workerQueries.getAllWorkers().stream()
                .sorted(Comparator.comparing(Worker::getWorkerId, Comparator.nullsLast(String::compareTo)))
                .map(worker -> toWorkerItem(
                        worker,
                        connectionsByWorker.getOrDefault(worker.getWorkerId(), List.of())
                ))
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @GetMapping("/worker-contexts")
    public ApiResponse<Map<String, Object>> listWorkerContexts() {
        List<Map<String, Object>> items = workerQueries.getAllWorkerContexts().stream()
                .sorted(Comparator.comparing(WorkerContext::getWorkerContextId, Comparator.nullsLast(String::compareTo)))
                .map(this::toWorkerContextItem)
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    private Map<String, Object> toWorkerItem(Worker worker, List<Map<String, Object>> connections) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workerId", worker.getWorkerId());
        item.put("status", worker.getStatus() != null ? worker.getStatus().name() : null);
        item.put("workerGroupId", worker.getWorkerGroupId());
        item.put("agentVersion", worker.getAgentVersion());
        item.put("supportedProjects", worker.getSupportedProjects());
        item.put("supportedEventCodes", worker.getSupportedEventCodes());
        item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(worker, metadataCatalog));
        item.put("adapterId", WorkerCapabilityViewSupport.resolveAdapterId(worker, connections));
        item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker, connections));
        item.put("attributes", worker.getAttributes());
        item.put("lastHeartbeat", formatDateTime(worker.getLastHeartbeat()));
        item.put("locked", workerQueries.isWorkerLocked(worker.getWorkerId()));
        item.put("connections", connections);
        item.put("hasActiveEndpoint", WorkerCapabilityViewSupport.hasActiveConnection(connections));
        item.put("updateTime", formatDateTime(worker.getUpdateTime()));
        return item;
    }

    private Map<String, Object> toWorkerContextItem(WorkerContext workerContext) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workerContextId", workerContext.getWorkerContextId());
        item.put("workerId", workerContext.getWorkerId());
        item.put("project", workerContext.getProject());
        item.put("status", workerContext.getStatus() != null ? workerContext.getStatus().name() : null);
        item.put("routingTags", workerContext.getRoutingTags());
        item.put("attributes", workerContext.getAttributes());
        item.put("lastBindTaskId", workerContext.getLastBindTaskId());
        item.put("lastUsedTime", formatDateTime(workerContext.getLastUsedTime()));
        item.put("updateTime", formatDateTime(workerContext.getUpdateTime()));
        return item;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }
}
