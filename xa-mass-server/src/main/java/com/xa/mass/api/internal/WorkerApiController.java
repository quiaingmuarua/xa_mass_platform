package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.model.WorkerContextSnapshot;
import com.xa.mass.sdk.model.WorkerSnapshot;
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
    private final ControlPlaneCatalog catalog;
    private final RuntimeDiagnosticsOperations runtimeDiagnostics;

    public WorkerApiController(WorkerQueryOperations workerQueries) {
        this(workerQueries, (ControlPlaneCatalog) null, (RuntimeDiagnosticsOperations) null);
    }

    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ControlPlaneCatalog catalog,
                               RuntimeDiagnosticsOperations runtimeDiagnostics) {
        this.workerQueries = workerQueries;
        this.catalog = catalog;
        this.runtimeDiagnostics = runtimeDiagnostics;
    }

    @Autowired
    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ObjectProvider<ControlPlaneCatalog> metadataCatalogProvider,
                               ObjectProvider<RuntimeDiagnosticsOperations> runtimeDiagnosticsProvider) {
        this(
                workerQueries,
                metadataCatalogProvider == null ? null : metadataCatalogProvider.getIfAvailable(),
                runtimeDiagnosticsProvider == null ? null : runtimeDiagnosticsProvider.getIfAvailable()
        );
    }

    @GetMapping("/workers")
    public ApiResponse<Map<String, Object>> listWorkers() {
        Map<String, List<Map<String, Object>>> connectionsByWorker =
                WorkerCapabilityViewSupport.groupConnectionsByWorker(runtimeDiagnostics);
        List<Map<String, Object>> items = workerQueries.getAllWorkers().stream()
                .sorted(Comparator.comparing(WorkerSnapshot::getWorkerId, Comparator.nullsLast(String::compareTo)))
                .map(worker -> {
                    List<Map<String, Object>> connections =
                            connectionsByWorker.getOrDefault(worker.getWorkerId(), List.of());
                    boolean transportOnline = workerQueries.isWorkerOnline(worker.getWorkerId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workerId", worker.getWorkerId());
                    item.put("status", worker.getStatus());
                    item.put("transportReachability", transportOnline ? "ONLINE" : "OFFLINE");
                    item.put("transportOnline", transportOnline);
                    item.put("workerGroupId", worker.getWorkerGroupId());
                    item.put("agentVersion", worker.getAgentVersion());
                    item.put("supportedProjects", worker.getSupportedProjects());
                    item.put("supportedEventCodes", worker.getSupportedEventCodes());
                    item.put("maxConcurrentWork", worker.getMaxConcurrentWork());
                    item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(
                            worker.getSupportedEventCodes(), catalog));
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
                .sorted(Comparator.comparing(WorkerContextSnapshot::getWorkerContextId, Comparator.nullsLast(String::compareTo)))
                .map(workerContext -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workerContextId", workerContext.getWorkerContextId());
                    item.put("workerId", workerContext.getWorkerId());
                    item.put("project", workerContext.getProject());
                    item.put("status", workerContext.getStatus());
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
