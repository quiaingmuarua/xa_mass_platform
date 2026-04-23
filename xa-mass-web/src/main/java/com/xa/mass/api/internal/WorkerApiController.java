package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.worker.WorkerSupportedProjectsApiRequest;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.project.ProjectRegistry;
import com.xa.mass.sdk.WorkerOperations;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/status/api")
public class WorkerApiController {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkerOperations workerOperations;

    public WorkerApiController(WorkerOperations workerOperations) {
        this.workerOperations = workerOperations;
    }

    @GetMapping("/workers")
    public ApiResponse<Map<String, Object>> listWorkers() {
        List<Map<String, Object>> items = workerOperations.getAllWorkers().stream()
                .sorted(Comparator.comparing(Worker::getWorkerId, Comparator.nullsLast(String::compareTo)))
                .map(this::toWorkerItem)
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @GetMapping("/worker-contexts")
    public ApiResponse<Map<String, Object>> listWorkerContexts() {
        List<Map<String, Object>> items = workerOperations.getAllWorkerContexts().stream()
                .sorted(Comparator.comparing(WorkerContext::getWorkerContextId, Comparator.nullsLast(String::compareTo)))
                .map(this::toWorkerContextItem)
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        ));
    }

    @PutMapping("/workers/{workerId}/supported-projects")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateSupportedProjects(@PathVariable String workerId,
                                                                                    @RequestBody WorkerSupportedProjectsApiRequest requestBody) {
        validateKnownFields(requestBody);
        Worker worker = workerOperations.getWorker(workerId);
        if (worker == null) {
            return ResponseEntity.status(404).body(ApiResponse.error(404, "Worker not found: " + workerId));
        }

        List<String> supportedProjects = normalizeSupportedProjects(requestBody.getSupportedProjects());
        worker.setSupportedProjects(supportedProjects);
        workerOperations.updateWorker(worker);

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "workerId", workerId,
                "supportedProjects", worker.getSupportedProjects()
        )));
    }

    private void validateKnownFields(WorkerSupportedProjectsApiRequest requestBody) {
        if (requestBody == null) {
            throw new IllegalArgumentException("worker request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException("Unsupported worker update fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
        if (requestBody.getSupportedProjects() == null) {
            throw new IllegalArgumentException("supportedProjects is required");
        }
    }

    private List<String> normalizeSupportedProjects(List<String> supportedProjects) {
        return supportedProjects.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> ProjectRegistry.require(value).getCode())
                .distinct()
                .toList();
    }

    private Map<String, Object> toWorkerItem(Worker worker) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("workerId", worker.getWorkerId());
        item.put("status", worker.getStatus() != null ? worker.getStatus().name() : null);
        item.put("workerGroupId", worker.getWorkerGroupId());
        item.put("agentVersion", worker.getAgentVersion());
        item.put("supportedProjects", worker.getSupportedProjects());
        item.put("attributes", worker.getAttributes());
        item.put("lastHeartbeat", formatDateTime(worker.getLastHeartbeat()));
        item.put("locked", workerOperations.isWorkerLocked(worker.getWorkerId()));
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
