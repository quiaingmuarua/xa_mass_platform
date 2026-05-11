package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
import com.xa.mass.sdk.internal.TransportDebugOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/runtime")
public class WorkerApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkerQueryOperations workerQueries;
    private final SdkMetadataCatalog metadataCatalog;
    private final TransportDebugOperations transportDebugOperations;

    public WorkerApiController(WorkerQueryOperations workerQueries) {
        this(workerQueries, (SdkMetadataCatalog) null, (TransportDebugOperations) null);
    }

    public WorkerApiController(WorkerQueryOperations workerQueries,
                               SdkMetadataCatalog metadataCatalog,
                               TransportDebugOperations transportDebugOperations) {
        this.workerQueries = workerQueries;
        this.metadataCatalog = metadataCatalog;
        this.transportDebugOperations = transportDebugOperations;
    }

    @Autowired
    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ObjectProvider<SdkMetadataCatalog> metadataCatalogProvider,
                               ObjectProvider<TransportDebugOperations> transportDebugOperationsProvider) {
        this(
                workerQueries,
                metadataCatalogProvider == null ? null : metadataCatalogProvider.getIfAvailable(),
                transportDebugOperationsProvider == null ? null : transportDebugOperationsProvider.getIfAvailable()
        );
    }

    @GetMapping("/workers")
    public ApiResponse<Map<String, Object>> listWorkers() {
        Map<String, List<Map<String, Object>>> connectionsByWorker =
                WorkerCapabilityViewSupport.groupConnectionsByWorker(transportDebugOperations);
        List<Map<String, Object>> items = workerQueries.getAllWorkers().stream()
                .sorted(Comparator.comparing(worker -> worker.getWorkerId(), Comparator.nullsLast(String::compareTo)))
                .map(worker -> {
                    List<Map<String, Object>> connections =
                            connectionsByWorker.getOrDefault(worker.getWorkerId(), List.of());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workerId", worker.getWorkerId());
                    item.put("status", worker.getStatus() != null ? worker.getStatus().name() : null);
                    item.put("workerGroupId", worker.getWorkerGroupId());
                    item.put("agentVersion", worker.getAgentVersion());
                    item.put("supportedProjects", worker.getSupportedProjects());
                    item.put("supportedEventCodes", worker.getSupportedEventCodes());
                    item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(
                            worker.getSupportedEventCodes(), metadataCatalog));
                    item.put("adapterId", WorkerCapabilityViewSupport.resolveAdapterId(worker.getAdapterId(), connections));
                    item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker.getOnlineStrategy()));
                    item.put("attributes", worker.getAttributes());
                    item.put("lastHeartbeat", formatDateTime(worker.getLastHeartbeat()));
                    item.put("locked", workerQueries.isWorkerLocked(worker.getWorkerId()));
                    item.put("connections", connections);
                    item.put("hasActiveEndpoint", WorkerCapabilityViewSupport.hasActiveConnection(connections));
                    item.put("updateTime", formatDateTime(worker.getUpdateTime()));
                    return item;
                })
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @GetMapping("/worker-contexts")
    public ApiResponse<Map<String, Object>> listWorkerContexts() {
        List<Map<String, Object>> items = workerQueries.getAllWorkerContexts().stream()
                .sorted(Comparator.comparing(context -> context.getWorkerContextId(), Comparator.nullsLast(String::compareTo)))
                .map(workerContext -> {
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
                })
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }
}
