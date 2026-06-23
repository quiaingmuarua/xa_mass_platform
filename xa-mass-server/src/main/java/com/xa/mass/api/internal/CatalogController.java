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
import com.xa.mass.sdk.model.WorkerEventBinding;
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
import java.util.Set;

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
        Set<String> onlineWorkerIdSet = resolveReachableWorkerIds(workers);
        Map<String, WorkerGroupSnapshot> groupsById = workerGroupsById();
        List<EventCapabilityView> items = catalog.listEvents().stream()
                .sorted(Comparator.comparing(EventDefinition::getCode, String::compareToIgnoreCase))
                .map(event -> {
                    boolean directRuntime = event.getTaskModes().isEmpty();
                    List<String> groupIds = groupsForEvent(groupsById, event.getCode()).stream()
                            .map(WorkerGroupSnapshot::groupId)
                            .toList();
                    List<String> reachableWorkerIds = workers.stream()
                            .filter(worker -> groupIds.contains(worker.getWorkerGroupId()))
                            .filter(worker -> onlineWorkerIdSet.contains(worker.getWorkerId()))
                            .map(worker -> worker.getWorkerId())
                            .filter(Objects::nonNull)
                            .distinct()
                            .sorted(String::compareToIgnoreCase)
                            .toList();
                    List<String> workerIds = workers.stream()
                            .filter(worker -> groupIds.contains(worker.getWorkerGroupId()))
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
                            reachableWorkerIds,
                            directRuntime,
                            !reachableWorkerIds.isEmpty(),
                            directRuntime || !reachableWorkerIds.isEmpty()
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
        Map<String, WorkerGroupSnapshot> groupsById = workerGroupsById();
        List<WorkerSnapshot> workers = workerQueries.getAllWorkers();
        Set<String> reachableWorkerIds = resolveReachableWorkerIds(workers);
        Set<String> lockedWorkerIds = resolveLockedWorkerIds(workers);
        List<Map<String, Object>> items = workers.stream()
                .sorted(Comparator.comparing(worker -> worker.getWorkerId(), Comparator.nullsLast(String::compareTo)))
                .map(worker -> {
                    WorkerGroupSnapshot group = groupsById.get(worker.getWorkerGroupId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("workerId", worker.getWorkerId());
                    item.put("runtimeStatus", worker.getStatus());
                    item.put("workerGroupId", worker.getWorkerGroupId());
                    item.put("agentVersion", worker.getAgentVersion());
                    item.put("supportedProjects", groupProjectCodes(group));
                    item.put("supportedEventCodes", groupEventCodes(group));
                    item.put("maxConcurrentWork", worker.getMaxConcurrentWork());
                    item.put("eventBindings", groupEventBindings(group));
                    item.put("transportHint", WorkerCapabilityViewSupport.resolveTransportHint(worker.getTransportHint()));
                    item.put("attributes", worker.getAttributes());
                    boolean reachable = reachableWorkerIds.contains(worker.getWorkerId());
                    item.put("reachability", reachable ? "ONLINE" : "OFFLINE");
                    item.put("reachable", reachable);
                    item.put("locked", lockedWorkerIds.contains(worker.getWorkerId()));
                    item.put("fieldSources", WorkerCapabilityViewSupport.catalogWorkerFieldSources());
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
        Set<String> reachableWorkerIds = resolveReachableWorkerIds(workers);
        Set<String> lockedWorkerIds = resolveLockedWorkerIds(workers);
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
                .<Map<String, Object>>map(group -> workerGroupCapability(
                        group,
                        workers,
                        adapterNodesById,
                        nodeGroupBindings,
                        reachableWorkerIds,
                        lockedWorkerIds))
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

    private Set<String> resolveReachableWorkerIds(List<WorkerSnapshot> workers) {
        if (workerQueries == null || workers == null || workers.isEmpty()) {
            return Set.of();
        }
        return workers.stream()
                .filter(Objects::nonNull)
                .map(WorkerSnapshot::getWorkerId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(workerId -> !workerId.isEmpty())
                .filter(workerQueries::isWorkerReachable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Map<String, WorkerGroupSnapshot> workerGroupsById() {
        if (workerTopology == null) {
            return Map.of();
        }
        return workerTopology.listWorkerGroups().stream()
                .filter(Objects::nonNull)
                .filter(group -> group.groupId() != null && !group.groupId().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        WorkerGroupSnapshot::groupId,
                        group -> group,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private List<WorkerGroupSnapshot> groupsForEvent(Map<String, WorkerGroupSnapshot> groupsById,
                                                     String eventCode) {
        if (eventCode == null || eventCode.isBlank() || groupsById.isEmpty()) {
            return List.of();
        }
        return groupsById.values().stream()
                .filter(group -> groupEventCodes(group).contains(eventCode))
                .toList();
    }

    private List<String> groupProjectCodes(WorkerGroupSnapshot group) {
        return group == null ? List.of() : normalizeProjectCodes(group.projectCodes());
    }

    private List<String> groupEventCodes(WorkerGroupSnapshot group) {
        if (group == null || group.eventBindings() == null || group.eventBindings().isEmpty()) {
            return List.of();
        }
        return group.eventBindings().stream()
                .filter(Objects::nonNull)
                .map(WorkerEventBinding::getEventCode)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .sorted(String::compareToIgnoreCase)
                .toList();
    }

    private List<WorkerEventBinding> groupEventBindings(WorkerGroupSnapshot group) {
        if (group == null || group.eventBindings() == null || group.eventBindings().isEmpty()) {
            return List.of();
        }
        return group.eventBindings().stream()
                .filter(Objects::nonNull)
                .filter(binding -> binding.getEventCode() != null && !binding.getEventCode().isBlank())
                .sorted(Comparator.comparing(
                        binding -> binding.getEventCode().trim(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Map<String, Object> workerGroupCapability(WorkerGroupSnapshot group,
                                                      List<WorkerSnapshot> workers,
                                                      Map<String, AdapterNodeSnapshot> adapterNodesById,
                                                      List<NodeGroupBindingSnapshot> nodeGroupBindings,
                                                      Set<String> reachableWorkerIds,
                                                      Set<String> lockedWorkerIds) {
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
                        worker -> normalizeTransport(worker.getTransportHint()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> reachableWorkerCountsByTransport = groupWorkers.stream()
                .filter(worker -> reachableWorkerIds.contains(worker.getWorkerId()))
                .collect(java.util.stream.Collectors.groupingBy(
                        worker -> normalizeTransport(worker.getTransportHint()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        Map<String, Long> runtimeStatusCounts = groupWorkers.stream()
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

        long lockedCount = groupWorkers.stream().filter(worker -> lockedWorkerIds.contains(worker.getWorkerId())).count();
        long reachableUnlockedWorkerCount = groupWorkers.stream()
                .filter(worker -> reachableWorkerIds.contains(worker.getWorkerId()))
                .filter(worker -> !lockedWorkerIds.contains(worker.getWorkerId()))
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
        item.put("declaredWorkerIds", workerIds);
        item.put("transportCounts", transportCounts);
        item.put("reachableWorkerCountsByTransport", reachableWorkerCountsByTransport);
        item.put("runtimeStatusCounts", runtimeStatusCounts);
        item.put("lockedCount", lockedCount);
        item.put("reachableUnlockedWorkerCount", reachableUnlockedWorkerCount);
        item.put("fingerprintDistribution", fingerprintDistribution);
        return item;
    }

    private Set<String> resolveLockedWorkerIds(List<WorkerSnapshot> workers) {
        if (runtimeDiagnostics == null || workers == null || workers.isEmpty()) {
            return Set.of();
        }
        return workers.stream()
                .filter(Objects::nonNull)
                .map(WorkerSnapshot::getWorkerId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(workerId -> !workerId.isEmpty())
                .filter(runtimeDiagnostics::isWorkerLocked)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String normalizeTransport(String value) {
        String transport = WorkerCapabilityViewSupport.resolveTransportHint(value);
        return blankToDefault(transport, "unknown");
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

}
