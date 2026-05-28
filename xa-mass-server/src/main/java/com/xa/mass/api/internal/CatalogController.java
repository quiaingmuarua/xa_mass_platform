package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.model.catalog.EventCapabilityView;
import com.xa.mass.sdk.RuntimeDiagnosticsOperations;
import com.xa.mass.sdk.WorkerQueryOperations;
import com.xa.mass.sdk.WorkerTopologyOperations;
import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.AdapterNodeSnapshot;
import com.xa.mass.sdk.model.NodeGroupBindingSnapshot;
import com.xa.mass.sdk.model.WorkerGroupSnapshot;
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
    private final WorkerTopologyOperations workerTopology;
    private final RuntimeDiagnosticsOperations runtimeDiagnostics;

    public CatalogController(ControlPlaneCatalog catalog) {
        this(catalog, (WorkerQueryOperations) null, null, null);
    }

    @Autowired
    public CatalogController(ControlPlaneCatalog catalog,
                             ObjectProvider<WorkerQueryOperations> workerQueriesProvider,
                             ObjectProvider<WorkerTopologyOperations> workerTopologyProvider,
                             ObjectProvider<RuntimeDiagnosticsOperations> runtimeDiagnosticsProvider) {
        this(
                catalog,
                workerQueriesProvider == null ? null : workerQueriesProvider.getIfAvailable(),
                workerTopologyProvider == null ? null : workerTopologyProvider.getIfAvailable(),
                runtimeDiagnosticsProvider == null ? null : runtimeDiagnosticsProvider.getIfAvailable()
        );
    }

    public CatalogController(ControlPlaneCatalog catalog,
                             WorkerQueryOperations workerQueries) {
        this(catalog, workerQueries, null, null);
    }

    public CatalogController(ControlPlaneCatalog catalog,
                             WorkerQueryOperations workerQueries,
                             RuntimeDiagnosticsOperations runtimeDiagnostics) {
        this(catalog, workerQueries, null, runtimeDiagnostics);
    }

    public CatalogController(ControlPlaneCatalog catalog,
                             WorkerQueryOperations workerQueries,
                             WorkerTopologyOperations workerTopology,
                             RuntimeDiagnosticsOperations runtimeDiagnostics) {
        this.catalog = catalog;
        this.workerQueries = workerQueries;
        this.workerTopology = workerTopology;
        this.runtimeDiagnostics = runtimeDiagnostics;
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<EventDefinition>>> listEvents() {
        return ResponseEntity.ok(ApiResponse.success(catalog.listEvents()));
    }

    @GetMapping("/event-capabilities")
    public ResponseEntity<ApiResponse<List<EventCapabilityView>>> listEventCapabilities() {
        List<WorkerSnapshot> workers = workerQueries == null ? List.of() : workerQueries.getAllWorkers();
        List<EventCapabilityView> items = catalog.listEvents().stream()
                .sorted(Comparator.comparing(EventDefinition::getCode, String::compareToIgnoreCase))
                .map(event -> {
                    boolean directRuntime = event.getTaskModes().isEmpty();
                    List<String> onlineWorkerIds = workers.stream()
                            .filter(worker -> isTransportOnline(worker.getWorkerId()))
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

                    return new EventCapabilityView(
                            event.getCode(),
                            event.getName(),
                            event.isEnabled(),
                            event.getPriorityClass().name(),
                            event.getResponseMode().name(),
                            event.getDeliveryAcknowledgementMode().name(),
                            event.getConvergenceMode().name(),
                            event.getTargetScope().name(),
                            directRuntime ? "DIRECT_RUNTIME" : "TASK_BACKED",
                            normalizeProjectCodes(event.getProjectCodes()),
                            workerIds,
                            onlineWorkerIds,
                            directRuntime,
                            !onlineWorkerIds.isEmpty(),
                            directRuntime || !onlineWorkerIds.isEmpty()
                    );
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
                WorkerCapabilityViewSupport.groupConnectionsByWorker(runtimeDiagnostics);
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
                    item.put("maxConcurrentWork", worker.getMaxConcurrentWork());
                    item.put("eventBindings", WorkerCapabilityViewSupport.deriveEventBindings(
                            worker.getEventBindings(), worker.getSupportedEventCodes(), catalog));
                    item.put("adapterId", WorkerCapabilityViewSupport.resolveAdapterId(worker.getAdapterId(), connections));
                    item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker.getOnlineStrategy()));
                    item.put("attributes", worker.getAttributes());
                    item.put("online", isTransportOnline(worker.getWorkerId()));
                    item.put("connections", connections);
                    item.put("hasActiveEndpoint", WorkerCapabilityViewSupport.hasActiveConnection(connections));
                    item.put("locked", runtimeDiagnostics != null && runtimeDiagnostics.isWorkerLocked(worker.getWorkerId()));
                    item.put("fieldSources", WorkerCapabilityViewSupport.workerFieldSources());
                    return item;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    @GetMapping("/worker-group-capabilities")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listWorkerGroupCapabilities() {
        if (workerTopology == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }
        List<WorkerSnapshot> workers = workerQueries == null ? List.of() : workerQueries.getAllWorkers();
        List<AdapterNodeSnapshot> adapterNodes = workerTopology.listAdapterNodes();
        List<NodeGroupBindingSnapshot> nodeGroupBindings = workerTopology.listNodeGroupBindings();
        Map<String, AdapterNodeSnapshot> adapterNodesById = adapterNodes.stream()
                .filter(Objects::nonNull)
                .filter(node -> node.adapterNodeId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        AdapterNodeSnapshot::adapterNodeId,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new));
        List<Map<String, Object>> items = workerTopology.listWorkerGroups().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(WorkerGroupSnapshot::groupId, String::compareToIgnoreCase))
                .map(group -> workerGroupCapability(group, workers, adapterNodesById, nodeGroupBindings))
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

    private boolean isTransportOnline(String workerId) {
        return workerQueries != null
                && workerId != null
                && !workerId.isBlank()
                && workerQueries.isWorkerOnline(workerId);
    }

    private Map<String, Object> workerGroupCapability(WorkerGroupSnapshot group,
                                                      List<WorkerSnapshot> workers,
                                                      Map<String, AdapterNodeSnapshot> adapterNodesById,
                                                      List<NodeGroupBindingSnapshot> nodeGroupBindings) {
        List<WorkerSnapshot> groupWorkers = workers.stream()
                .filter(worker -> group.groupId().equals(worker.getWorkerGroupId()))
                .sorted(Comparator.comparing(WorkerSnapshot::getWorkerId, Comparator.nullsLast(String::compareTo)))
                .toList();
        List<String> workerIds = groupWorkers.stream()
                .map(WorkerSnapshot::getWorkerId)
                .filter(Objects::nonNull)
                .toList();
        List<NodeGroupBindingSnapshot> bindings = nodeGroupBindings.stream()
                .filter(binding -> group.groupId().equals(binding.workerGroupId()))
                .sorted(Comparator.comparing(NodeGroupBindingSnapshot::adapterNodeId, Comparator.nullsLast(String::compareTo)))
                .toList();
        List<AdapterNodeSnapshot> adapterNodes = bindings.stream()
                .map(binding -> adapterNodesById.get(binding.adapterNodeId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<String, Long> transportCounts = groupWorkers.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        worker -> normalizeTransport(worker.getOnlineStrategy()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> transportOnlineCounts = groupWorkers.stream()
                .filter(worker -> isTransportOnline(worker.getWorkerId()))
                .collect(java.util.stream.Collectors.groupingBy(
                        worker -> normalizeTransport(worker.getOnlineStrategy()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> modelStatusCounts = groupWorkers.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        worker -> blankToDefault(worker.getStatus(), "UNKNOWN"),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> fingerprintDistribution = groupWorkers.stream()
                .map(worker -> worker.getAttributes().get("fingerprintProfile"))
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> value,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));

        long lockedCount = groupWorkers.stream().filter(worker -> isWorkerLocked(worker.getWorkerId())).count();
        long dispatchEligibleCount = groupWorkers.stream()
                .filter(worker -> isTransportOnline(worker.getWorkerId()))
                .filter(worker -> !isWorkerLocked(worker.getWorkerId()))
                .filter(worker -> hasAvailableBinding(worker, bindings, adapterNodesById))
                .count();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("groupId", group.groupId());
        item.put("eventBindings", group.eventBindings());
        item.put("projectCodes", group.projectCodes());
        item.put("defaultAttributes", group.defaultAttributes());
        item.put("defaultMaxConcurrentWork", group.defaultMaxConcurrentWork());
        item.put("adapterNodes", adapterNodes);
        item.put("nodeGroupBindings", bindings);
        item.put("workerCount", groupWorkers.size());
        item.put("workerIds", workerIds);
        item.put("transportCounts", transportCounts);
        item.put("transportOnlineCounts", transportOnlineCounts);
        item.put("modelStatusCounts", modelStatusCounts);
        item.put("lockedCount", lockedCount);
        item.put("dispatchEligibleCount", dispatchEligibleCount);
        item.put("fingerprintDistribution", fingerprintDistribution);
        return item;
    }

    private boolean isWorkerLocked(String workerId) {
        return runtimeDiagnostics != null && runtimeDiagnostics.isWorkerLocked(workerId);
    }

    private boolean hasAvailableBinding(WorkerSnapshot worker,
                                        List<NodeGroupBindingSnapshot> bindings,
                                        Map<String, AdapterNodeSnapshot> adapterNodesById) {
        if (bindings.isEmpty()) {
            return true;
        }
        String workerAdapterNodeId = worker.getAdapterNodeId();
        return bindings.stream()
                .filter(binding -> workerAdapterNodeId == null || workerAdapterNodeId.equals(binding.adapterNodeId()))
                .anyMatch(binding -> {
                    AdapterNodeSnapshot node = adapterNodesById.get(binding.adapterNodeId());
                    return binding.enabled()
                            && !binding.draining()
                            && node != null
                            && node.enabled()
                            && node.online();
                });
    }

    private String normalizeTransport(String value) {
        String transport = WorkerCapabilityViewSupport.resolveTransportHint(value);
        return blankToDefault(transport, "unknown");
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

}
