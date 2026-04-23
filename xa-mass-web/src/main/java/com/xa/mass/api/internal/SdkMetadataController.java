package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.base.model.Worker;
import com.xa.mass.sdk.WorkerOperations;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.event.SdkEventDefinition;
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
@RequestMapping("/sdk/meta")
public class SdkMetadataController {

    private final ProjectEventCatalog projectEventCatalog;
    private final WorkerOperations workerOperations;

    public SdkMetadataController(ProjectEventCatalog projectEventCatalog) {
        this(projectEventCatalog, (WorkerOperations) null);
    }

    @Autowired
    public SdkMetadataController(ProjectEventCatalog projectEventCatalog,
                                 ObjectProvider<WorkerOperations> workerOperationsProvider) {
        this(projectEventCatalog, workerOperationsProvider == null ? null : workerOperationsProvider.getIfAvailable());
    }

    public SdkMetadataController(ProjectEventCatalog projectEventCatalog,
                                 WorkerOperations workerOperations) {
        this.projectEventCatalog = projectEventCatalog;
        this.workerOperations = workerOperations;
    }

    @GetMapping("/projects")
    public ResponseEntity<ApiResponse<List<ProjectMetadata>>> listProjects() {
        return ResponseEntity.ok(ApiResponse.success(projectEventCatalog.listProjects()));
    }

    @GetMapping("/projects/{projectCode}")
    public ResponseEntity<ApiResponse<ProjectMetadata>> getProject(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = projectEventCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectMetadata));
    }

    @GetMapping("/projects/{projectCode}/events")
    public ResponseEntity<ApiResponse<List<SdkEventDefinition>>> getProjectEvents(@PathVariable String projectCode) {
        ProjectMetadata projectMetadata = projectEventCatalog.getProject(projectCode);
        if (projectMetadata == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Project metadata not found: " + projectCode));
        }
        return ResponseEntity.ok(ApiResponse.success(projectEventCatalog.getEventsForProject(projectCode)));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<SdkEventDefinition>>> listEvents() {
        return ResponseEntity.ok(ApiResponse.success(projectEventCatalog.listEvents()));
    }

    @GetMapping("/event-capabilities")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listEventCapabilities() {
        List<Worker> workers = workerOperations == null ? List.of() : workerOperations.getAllWorkers();
        List<Map<String, Object>> items = projectEventCatalog.listEvents().stream()
                .sorted(Comparator.comparing(SdkEventDefinition::getCode, String::compareToIgnoreCase))
                .map(event -> toEventCapabilityItem(event, workers))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/events/{eventCode}")
    public ResponseEntity<ApiResponse<SdkEventDefinition>> getEvent(@PathVariable String eventCode) {
        SdkEventDefinition definition = projectEventCatalog.getEvent(eventCode);
        if (definition == null) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.error(404, "Event metadata not found: " + eventCode));
        }
        return ResponseEntity.ok(ApiResponse.success(definition));
    }

    private Map<String, Object> toEventCapabilityItem(SdkEventDefinition event,
                                                      List<Worker> workers) {
        boolean directRuntime = event.getTaskModes().isEmpty();
        // Event capabilities are keyed by globally unique event codes. Worker
        // support is therefore derived from supportedEventCodes, while
        // projectCodes remains scope metadata from the SDK event definition.
        List<String> onlineWorkerIds = workers.stream()
                .filter(worker -> worker.getStatus() != null && "ONLINE".equals(worker.getStatus().name()))
                .filter(worker -> worker.getSupportedEventCodes() != null
                        && worker.getSupportedEventCodes().contains(event.getCode()))
                .map(Worker::getWorkerId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();
        List<String> workerIds = workers.stream()
                .filter(worker -> worker.getSupportedEventCodes() != null
                        && worker.getSupportedEventCodes().contains(event.getCode()))
                .map(Worker::getWorkerId)
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
