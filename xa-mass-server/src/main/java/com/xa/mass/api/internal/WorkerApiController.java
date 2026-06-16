package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.api.model.worker.WorkerCommandSubmitApiRequest;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.model.WorkerCommandSubmitRequest;
import com.xa.mass.sdk.model.WorkerSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/runtime")
public class WorkerApiController {

    private static final int DEFAULT_DIAGNOSTIC_LIMIT = 200;
    private static final int MAX_DIAGNOSTIC_LIMIT = 500;
    private final WorkerQueryOperations workerQueries;
    private final ControlPlaneCatalog catalog;
    private final RuntimeDiagnosticsOperations runtimeDiagnostics;
    private final WorkerControlOperations workerControl;

    public WorkerApiController(WorkerQueryOperations workerQueries) {
        this(
                workerQueries,
                (ControlPlaneCatalog) null,
                (RuntimeDiagnosticsOperations) null,
                (WorkerControlOperations) null
        );
    }

    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ControlPlaneCatalog catalog,
                               RuntimeDiagnosticsOperations runtimeDiagnostics) {
        this(workerQueries, catalog, runtimeDiagnostics, null);
    }

    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ControlPlaneCatalog catalog,
                               RuntimeDiagnosticsOperations runtimeDiagnostics,
                               WorkerControlOperations workerControl) {
        this.workerQueries = workerQueries;
        this.catalog = catalog;
        this.runtimeDiagnostics = runtimeDiagnostics;
        this.workerControl = workerControl;
    }

    @Autowired
    public WorkerApiController(WorkerQueryOperations workerQueries,
                               ObjectProvider<ControlPlaneCatalog> metadataCatalogProvider,
                               ObjectProvider<RuntimeDiagnosticsOperations> runtimeDiagnosticsProvider,
                               ObjectProvider<WorkerControlOperations> workerControlProvider) {
        this(
                workerQueries,
                metadataCatalogProvider == null ? null : metadataCatalogProvider.getIfAvailable(),
                runtimeDiagnosticsProvider == null ? null : runtimeDiagnosticsProvider.getIfAvailable(),
                workerControlProvider == null ? null : workerControlProvider.getIfAvailable()
        );
    }

    @GetMapping("/workers")
    @Operation(
            summary = "List runtime worker read models",
            description = "Returns composite current-state worker rows. The fieldSources map labels declaration, runtime, transport, and compatibility projection fields."
    )
    public ApiResponse<Map<String, Object>> listWorkers(
            @RequestParam(required = false) Integer limit) {
        int resolvedLimit = resolveDiagnosticLimit(limit);
        List<WorkerSnapshot> allWorkers = workerQueries.getAllWorkers();
        List<WorkerSnapshot> visibleWorkers = allWorkers.stream()
                .sorted(Comparator.comparing(WorkerSnapshot::getWorkerId, Comparator.nullsLast(String::compareTo)))
                .limit(resolvedLimit)
                .toList();
        Set<String> reachableWorkerIds = resolveReachableWorkerIds(visibleWorkers);
        Set<String> lockedWorkerIds = resolveLockedWorkerIds();
        List<Map<String, Object>> items = visibleWorkers.stream()
                .map(worker -> {
                    boolean reachable = reachableWorkerIds.contains(worker.getWorkerId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workerId", worker.getWorkerId());
                    item.put("runtimeStatus", worker.getStatus());
                    item.put("reachability", reachable ? "ONLINE" : "OFFLINE");
                    item.put("reachable", reachable);
                    item.put("workerGroupId", worker.getWorkerGroupId());
                    item.put("agentVersion", worker.getAgentVersion());
                    item.put("supportedProjects", worker.getSupportedProjects());
                    item.put("supportedEventCodes", worker.getSupportedEventCodes());
                    item.put("maxConcurrentWork", worker.getMaxConcurrentWork());
                    item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(
                            worker.getEventBindings(), worker.getSupportedEventCodes(), catalog));
                    item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker.getTransportHint()));
                    item.put("attributes", worker.getAttributes());
                    item.put("locked", lockedWorkerIds.contains(worker.getWorkerId()));
                    item.put("fieldSources", WorkerCapabilityViewSupport.workerFieldSources());
                    return item;
                })
                .toList();
        return ApiResponse.success(Map.of(
                "items", items,
                "total", allWorkers.size(),
                "limit", resolvedLimit
        ));
    }

    @GetMapping("/workers/{workerId}/state")
    @Operation(summary = "Get worker state projection")
    public ResponseEntity<ApiResponse<?>> getWorkerStateProjection(@PathVariable String workerId) {
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().getWorkerStateProjection(workerId)));
    }

    @GetMapping("/workers/states")
    @Operation(summary = "List worker state projections")
    public ResponseEntity<ApiResponse<?>> listWorkerStateProjections(
            @RequestParam(required = false) Integer limit) {
        int resolvedLimit = resolveDiagnosticLimit(limit);
        List<?> allItems = requireWorkerControl().listWorkerStateProjections();
        List<?> items = allItems.stream()
                .limit(resolvedLimit)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", items,
                "total", allItems.size(),
                "limit", resolvedLimit
        )));
    }

    @PostMapping("/workers/{workerId}/commands")
    @Operation(
            summary = "Request worker command",
            description = "Submits an owner-backed worker command request through the SDK control surface."
    )
    public ResponseEntity<ApiResponse<?>> requestWorkerCommand(@PathVariable String workerId,
                                                               @RequestBody WorkerCommandSubmitApiRequest requestBody) {
        validateKnownFields(requestBody, "worker command request");
        String resolvedWorkerId = resolveWorkerId(workerId, requestBody.getWorkerId());
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().requestWorkerCommand(
                new WorkerCommandSubmitRequest(
                        requestBody.getCommandId(),
                        resolvedWorkerId,
                        requestBody.getCommandType(),
                        requestBody.getRequester(),
                        requestBody.getReason(),
                        requestBody.getIdempotencyKey(),
                        requestBody.getDeadlineEpochMillis(),
                        requestBody.getPayload()
                )
        )));
    }

    @GetMapping("/workers/{workerId}/commands")
    @Operation(summary = "List worker commands")
    public ResponseEntity<ApiResponse<?>> listWorkerCommands(@PathVariable String workerId,
                                                             @RequestParam(required = false) Integer limit) {
        int resolvedLimit = resolveDiagnosticLimit(limit);
        List<?> allItems = requireWorkerControl().listWorkerCommandsForWorker(workerId);
        List<?> items = allItems.stream()
                .limit(resolvedLimit)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", items,
                "total", allItems.size(),
                "limit", resolvedLimit
        )));
    }

    @GetMapping("/workers/commands/{commandId}")
    @Operation(summary = "Get worker command")
    public ResponseEntity<ApiResponse<?>> getWorkerCommand(@PathVariable String commandId) {
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().getWorkerCommand(commandId)));
    }

    private WorkerControlOperations requireWorkerControl() {
        if (workerControl == null) {
            throw new IllegalStateException("Worker control operations are not available");
        }
        return workerControl;
    }

    private Set<String> resolveReachableWorkerIds(List<WorkerSnapshot> workers) {
        if (workers == null || workers.isEmpty()) {
            return Set.of();
        }
        Set<String> visibleWorkerIds = workers.stream()
                .filter(Objects::nonNull)
                .map(WorkerSnapshot::getWorkerId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(workerId -> !workerId.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (visibleWorkerIds.isEmpty()) {
            return Set.of();
        }
        List<String> reachableWorkerIds = workerQueries.listReachableWorkerIds();
        if (reachableWorkerIds == null || reachableWorkerIds.isEmpty()) {
            return Set.of();
        }
        return reachableWorkerIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(visibleWorkerIds::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<String> resolveLockedWorkerIds() {
        if (runtimeDiagnostics == null) {
            return Set.of();
        }
        List<String> lockedWorkerIds = runtimeDiagnostics.listLockedWorkerIds();
        if (lockedWorkerIds == null || lockedWorkerIds.isEmpty()) {
            return Set.of();
        }
        return lockedWorkerIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(workerId -> !workerId.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String resolveWorkerId(String pathWorkerId, String requestWorkerId) {
        String bodyWorkerId = trimToNull(requestWorkerId);
        if (bodyWorkerId != null && !bodyWorkerId.equals(pathWorkerId)) {
            throw new IllegalArgumentException("workerId in request body must match path workerId");
        }
        return pathWorkerId;
    }

    private void validateKnownFields(AbstractUnknownFieldRequest requestBody, String operationName) {
        if (requestBody == null) {
            throw new IllegalArgumentException(operationName + " request body is required");
        }
        if (requestBody.hasUnknownFields()) {
            throw new IllegalArgumentException(operationName + " contains unsupported fields: "
                    + String.join(", ", requestBody.getUnknownFieldNames()));
        }
    }

    private int resolveDiagnosticLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_DIAGNOSTIC_LIMIT;
        }
        return Math.min(limit, MAX_DIAGNOSTIC_LIMIT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
