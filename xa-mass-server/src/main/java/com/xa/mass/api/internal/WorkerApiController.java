package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.AbstractUnknownFieldRequest;
import com.xa.mass.api.model.worker.WorkerCapabilityReportApiRequest;
import com.xa.mass.api.model.worker.WorkerCommandAcknowledgementApiRequest;
import com.xa.mass.api.model.worker.WorkerCommandSubmitApiRequest;
import com.xa.mass.api.model.worker.WorkerStateReportApiRequest;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerControlOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.model.WorkerCapabilityReportRequest;
import com.xa.mass.sdk.model.WorkerCommandAcknowledgementRequest;
import com.xa.mass.sdk.model.WorkerCommandSubmitRequest;
import com.xa.mass.sdk.model.WorkerStateReportRequest;
import com.xa.mass.sdk.model.WorkerSnapshot;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
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
                    item.put("adapterNodeId", worker.getAdapterNodeId());
                    item.put("agentVersion", worker.getAgentVersion());
                    item.put("supportedProjects", worker.getSupportedProjects());
                    item.put("supportedEventCodes", worker.getSupportedEventCodes());
                    item.put("maxConcurrentWork", worker.getMaxConcurrentWork());
                    item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(
                            worker.getEventBindings(), worker.getSupportedEventCodes(), catalog));
                    item.put("adapterId", WorkerCapabilityViewSupport.resolveAdapterId(worker.getAdapterId(), connections));
                    item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker.getOnlineStrategy()));
                    item.put("attributes", worker.getAttributes());
                    item.put("lastHeartbeat", formatDateTime(worker.getLastHeartbeat()));
                    item.put("locked", runtimeDiagnostics != null && runtimeDiagnostics.isWorkerLocked(worker.getWorkerId()));
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

    @PostMapping("/workers/{workerId}/capability-reports")
    @Operation(
            summary = "Report worker capability",
            description = "Owner-backed runtime ingress for worker capability self-report. This is not worker CRUD."
    )
    public ResponseEntity<ApiResponse<?>> reportWorkerCapability(@PathVariable String workerId,
                                                                 @RequestBody WorkerCapabilityReportApiRequest requestBody) {
        validateKnownFields(requestBody, "worker capability report");
        String resolvedWorkerId = resolveWorkerId(workerId, requestBody.getWorkerId());
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().reportWorkerCapability(
                new WorkerCapabilityReportRequest(
                        resolvedWorkerId,
                        requestBody.getCapabilityVersion(),
                        requestBody.getAvailableEventCodes(),
                        requestBody.getSchedulingAttributes(),
                        requestBody.getAgentVersion()
                )
        )));
    }

    @PostMapping("/workers/{workerId}/state-reports")
    @Operation(
            summary = "Report worker state",
            description = "Owner-backed runtime ingress for bounded worker state projection."
    )
    public ResponseEntity<ApiResponse<?>> reportWorkerState(@PathVariable String workerId,
                                                            @RequestBody WorkerStateReportApiRequest requestBody) {
        validateKnownFields(requestBody, "worker state report");
        String resolvedWorkerId = resolveWorkerId(workerId, requestBody.getWorkerId());
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().reportWorkerState(
                new WorkerStateReportRequest(
                        resolvedWorkerId,
                        requestBody.getStateVersion(),
                        requestBody.getState(),
                        requestBody.getReason(),
                        requestBody.getObservedAt(),
                        requestBody.getAttributes()
                )
        )));
    }

    @GetMapping("/workers/{workerId}/state")
    @Operation(summary = "Get worker state projection")
    public ResponseEntity<ApiResponse<?>> getWorkerStateProjection(@PathVariable String workerId) {
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().getWorkerStateProjection(workerId)));
    }

    @GetMapping("/workers/states")
    @Operation(summary = "List worker state projections")
    public ResponseEntity<ApiResponse<?>> listWorkerStateProjections() {
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", requireWorkerControl().listWorkerStateProjections()
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

    @PostMapping("/workers/{workerId}/commands/{commandId}/ack")
    @Operation(summary = "Acknowledge worker command")
    public ResponseEntity<ApiResponse<?>> acknowledgeWorkerCommand(@PathVariable String workerId,
                                                                   @PathVariable String commandId,
                                                                   @RequestBody WorkerCommandAcknowledgementApiRequest requestBody) {
        validateKnownFields(requestBody, "worker command acknowledgement");
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().acknowledgeWorkerCommand(
                new WorkerCommandAcknowledgementRequest(commandId, requestBody.getStatus(), requestBody.getReason())
        )));
    }

    @GetMapping("/workers/{workerId}/commands")
    @Operation(summary = "List worker commands")
    public ResponseEntity<ApiResponse<?>> listWorkerCommands(@PathVariable String workerId) {
        List<?> items = requireWorkerControl().listWorkerCommandsForWorker(workerId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "items", items,
                "total", items.size()
        )));
    }

    @GetMapping("/workers/commands/{commandId}")
    @Operation(summary = "Get worker command")
    public ResponseEntity<ApiResponse<?>> getWorkerCommand(@PathVariable String commandId) {
        return ResponseEntity.ok(ApiResponse.success(requireWorkerControl().getWorkerCommand(commandId)));
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME_FORMATTER);
    }

    private WorkerControlOperations requireWorkerControl() {
        if (workerControl == null) {
            throw new IllegalStateException("Worker control operations are not available");
        }
        return workerControl;
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
