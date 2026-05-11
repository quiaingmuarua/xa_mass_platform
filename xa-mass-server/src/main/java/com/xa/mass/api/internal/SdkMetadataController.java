package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.catalog.SdkMetadataCatalog;
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
 * Read-only SDK/platform metadata endpoints.
 */
@RestController
@RequestMapping("/api/v1/meta")
public class SdkMetadataController {

    private final SdkMetadataCatalog metadataCatalog;
    private final WorkerQueryOperations workerQueries;
    private final TransportDebugOperations transportDebugOperations;

    public SdkMetadataController(SdkMetadataCatalog metadataCatalog) {
        this(metadataCatalog, (WorkerQueryOperations) null, null);
    }

    @Autowired
    public SdkMetadataController(SdkMetadataCatalog metadataCatalog,
                                 ObjectProvider<WorkerQueryOperations> workerQueriesProvider,
                                 ObjectProvider<TransportDebugOperations> transportDebugOperationsProvider) {
        this(
                metadataCatalog,
                workerQueriesProvider == null ? null : workerQueriesProvider.getIfAvailable(),
                transportDebugOperationsProvider == null ? null : transportDebugOperationsProvider.getIfAvailable()
        );
    }

    public SdkMetadataController(SdkMetadataCatalog metadataCatalog,
                                 WorkerQueryOperations workerQueries) {
        this(metadataCatalog, workerQueries, null);
    }

    public SdkMetadataController(SdkMetadataCatalog metadataCatalog,
                                 WorkerQueryOperations workerQueries,
                                 TransportDebugOperations transportDebugOperations) {
        this.metadataCatalog = metadataCatalog;
        this.workerQueries = workerQueries;
        this.transportDebugOperations = transportDebugOperations;
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<ProjectMetadata>>> listProjects() {
        return ResponseEntity.ok(ApiResponse.success(metadataCatalog.listProjects()));
    }

    @GetMapping("/projects/{projectCode}")
    public ResponseEntity<ApiResponse<ProjectMetadata>> getProject(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectMetadata));
    }

    @GetMapping("/projects/{projectCode}/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> getProjectEvents(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = metadataCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(metadataCatalog.getEventsForProject(projectCode)));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> listEvents() {
        return ResponseEntity.ok(ApiResponse.success(metadataCatalog.listEvents()));
    }

    @GetMapping("/event-capabilities")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listEventCapabilities() {
        List<WorkerSnapshot> workers = workerQueries == null ? List.of() : workerQueries.getAllWorkers();
        List<Map<String, Object>> items = metadataCatalog.listEvents().stream()
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
                            worker.getSupportedEventCodes(), metadataCatalog));
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
        EventDefinition definition = metadataCatalog.getEvent(eventCode);
        if (definition == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Event metadata not found: " + eventCode));
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
