package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.internal.TransportDebugOperations;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only control-plane catalog endpoints.
 */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final ControlPlaneCatalog catalog;
    private final WorkerQueryOperations workerQueries;
    private final TransportDebugOperations transportDebugOperations;

    public CatalogController(ControlPlaneCatalog catalog) {
        this(catalog, (WorkerQueryOperations) null, null);
    }

    @Autowired
    public CatalogController(ControlPlaneCatalog catalog,
                                 ObjectProvider<WorkerQueryOperations> workerQueriesProvider,
                                 ObjectProvider<TransportDebugOperations> transportDebugOperationsProvider) {
        this(
                catalog,
                workerQueriesProvider == null ? null : workerQueriesProvider.getIfAvailable(),
                transportDebugOperationsProvider == null ? null : transportDebugOperationsProvider.getIfAvailable()
        );
    }

    public CatalogController(ControlPlaneCatalog catalog,
                                 WorkerQueryOperations workerQueries) {
        this(catalog, workerQueries, null);
    }

    public CatalogController(ControlPlaneCatalog catalog,
                                 WorkerQueryOperations workerQueries,
                                 TransportDebugOperations transportDebugOperations) {
        this.catalog = catalog;
        this.workerQueries = workerQueries;
        this.transportDebugOperations = transportDebugOperations;
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> listEvents() {
        return ResponseEntity.ok(ApiResponse.success(catalog.listEvents()));
    }

    @GetMapping("/event-capabilities")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listEventCapabilities() {
        List<WorkerSnapshot> workers = workerQueries == null ? List.of() : workerQueries.getAllWorkers();
        List<Map<String, Object>> items = catalog.listEvents().stream()
                .sorted(Comparator.comparing(EventDefinition::getCode, String::compareToIgnoreCase))
                .map(event -> {
                    boolean directRuntime = event.getTaskModes().isEmpty();
                    List<String> onlineWorkerIds = workers.stream()
                            .filter(worker -> "ONLINE".equals(worker.getStatus()))
                            .filter(worker -> worker.getSupportedEventCodes() != null
                                    && worker.getSupportedEventCodes().contains(event.getCode()))
                            .map(worker -> worker.getWorkerId())
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted(String::compareToIgnoreCase)
                            .toList();
                    List<String> workerIds = workers.stream()
                            .filter(worker -> worker.getSupportedEventCodes() != null
                                    && worker.getSupportedEventCodes().contains(event.getCode()))
                            .map(worker -> worker.getWorkerId())
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted(String::compareToIgnoreCase)
                            .toList();

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("eventCode", event.getCode());
                    item.put("eventName", event.getName());
                    item.put("enabled", event.isEnabled());
                    item.put("invocationModel", directRuntime ? "DIRECT_RUNTIME" : "TASK_BACKED");
                    item.put("projectCodes", normalizeProjectCodes(event.getProjectCodes()));
                    item.put("workerIds", workerIds);
                    item.put("onlineWorkerIds", onlineWorkerIds);
                    item.put("hasDirectRuntimeHandler", directRuntime);
                    item.put("hasOnlineWorkerCoverage", !onlineWorkerIds.isEmpty());
                    item.put("ready", directRuntime || !onlineWorkerIds.isEmpty());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/worker-capabilities")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listWorkerCapabilities() {
        if (workerQueries == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        Map<String, List<Map<String, Object>>> connectionsByWorker =
                WorkerCapabilityViewSupport.groupConnectionsByWorker(transportDebugOperations);
        List<Map<String, Object>> items = workerQueries.getAllWorkers().stream()
                .sorted(Comparator.comparing(worker -> worker.getWorkerId(), Comparator.nullsLast(String::compareTo)))
                .map(worker -> {
                    List<Map<String, Object>> connections =
                            connectionsByWorker.getOrDefault(worker.getWorkerId(), List.of());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workerId", worker.getWorkerId());
                    item.put("status", worker.getStatus());
                    item.put("workerGroupId", worker.getWorkerGroupId());
                    item.put("agentVersion", worker.getAgentVersion());
                    item.put("supportedProjects", normalizeProjectCodes(worker.getSupportedProjects()));
                    item.put("supportedEventCodes", normalizeProjectCodes(worker.getSupportedEventCodes()));
                    item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(
                            worker.getSupportedEventCodes(), catalog));
                    item.put("adapterId", WorkerCapabilityViewSupport.resolveAdapterId(worker.getAdapterId(), connections));
                    item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker.getOnlineStrategy()));
                    item.put("attributes", worker.getAttributes());
                    item.put("online", "ONLINE".equals(worker.getStatus()));
                    item.put("connections", connections);
                    item.put("hasActiveEndpoint", WorkerCapabilityViewSupport.hasActiveConnection(connections));
                    item.put("locked", workerQueries.isWorkerLocked(worker.getWorkerId()));
                    return item;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/events/{eventCode}")
    public ResponseEntity<ApiResponse<EventDefinition>> getEvent(@PathVariable String eventCode) {
        EventDefinition definition = catalog.getEvent(eventCode);
        if (definition == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Event not found: " + eventCode));
        }
        return ResponseEntity.ok(ApiResponse.success(definition));
    }

    private List<String> normalizeProjectCodes(List<String> projectCodes) {
        if (projectCodes == null || projectCodes.isEmpty()) {
            return List.of();
        }
        return projectCodes.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();
    }

}
