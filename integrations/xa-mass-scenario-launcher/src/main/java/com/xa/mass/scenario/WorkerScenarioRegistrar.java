package com.xa.mass.scenario;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.AdapterNodeSpec;
import com.xa.mass.client.worker.NodeGroupBindingSpec;
import com.xa.mass.client.worker.WorkerCapabilityReport;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.WorkerSpec;
import com.xa.mass.client.worker.WorkerStateReport;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class WorkerScenarioRegistrar {
    private final ScenarioLauncherOptions options;
    private final ScenarioClientFactory clientFactory;
    private final Set<String> declaredWorkerGroups = new LinkedHashSet<>();
    private final Set<String> registeredAdapterNodes = new LinkedHashSet<>();
    private final Set<String> boundAdapterNodeGroups = new LinkedHashSet<>();

    WorkerScenarioRegistrar(ScenarioLauncherOptions options, ScenarioClientFactory clientFactory) {
        this.options = Objects.requireNonNull(options, "options is required");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
    }

    void register(List<WorkerScenarioSpec> workerSpecs) {
        register(workerSpecs, true);
    }

    void register(List<WorkerScenarioSpec> workerSpecs, boolean markApiOnline) {
        if (workerSpecs == null || workerSpecs.isEmpty()) {
            System.out.println("[java-scenario-launcher] no workers configured");
            return;
        }
        for (WorkerScenarioSpec spec : workerSpecs) {
            registerWorker(spec, markApiOnline);
        }
    }

    int declaredWorkerGroupCount() {
        return declaredWorkerGroups.size();
    }

    int registeredAdapterNodeCount() {
        return registeredAdapterNodes.size();
    }

    int boundAdapterNodeGroupCount() {
        return boundAdapterNodeGroups.size();
    }

    private void registerWorker(WorkerScenarioSpec spec, boolean markApiOnline) {
        String workerId = requireNonBlank(spec.workerId(), "workerId");
        String workerGroupId = requireNonBlank(spec.workerGroupId(), "workerGroupId");
        String adapterNodeId = adapterNodeIdFor(spec);
        String adapterType = adapterTypeFor(spec);
        MassPlatform client = clientFactory.forApiKey(workerApiKey(spec));

        if (declaredWorkerGroups.add(workerGroupId)) {
            client.workers().declareGroup(WorkerGroupSpec.builder()
                    .groupId(workerGroupId)
                    .eventBindings(spec.eventBindings())
                    .build());
            System.out.printf("[java-scenario-launcher] declared worker group %s%n", workerGroupId);
        }

        if (registeredAdapterNodes.add(adapterNodeId)) {
            client.workers().registerAdapterNode(AdapterNodeSpec.builder()
                    .adapterNodeId(adapterNodeId)
                    .adapterType(adapterType)
                    .endpointId(adapterNodeId)
                    .attributes(Map.of(
                            "launcher", "integrations/xa-mass-scenario-launcher",
                            "transport", adapterType
                    ))
                    .build());
            System.out.printf("[java-scenario-launcher] registered adapter node %s%n", adapterNodeId);
        }

        String bindingKey = adapterNodeId + "\n" + workerGroupId;
        if (boundAdapterNodeGroups.add(bindingKey)) {
            client.workers().bindNodeGroup(NodeGroupBindingSpec.builder()
                    .adapterNodeId(adapterNodeId)
                    .workerGroupId(workerGroupId)
                    .attributes(Map.of("transport", adapterType))
                    .build());
            System.out.printf("[java-scenario-launcher] bound adapter node %s to group %s%n",
                    adapterNodeId, workerGroupId);
        }

        client.workers().registerWorker(WorkerSpec.builder()
                .workerId(workerId)
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .adapterId(adapterType)
                .transportHint(spec.transportHint() == null || spec.transportHint().isBlank()
                        ? "realtime"
                        : spec.transportHint())
                .attributes(spec.attributes())
                .build());
        System.out.printf("[java-scenario-launcher] registered worker %s%n", workerId);
        if (markApiOnline && "api-online".equals(spec.startMode())) {
            markApiOnline(client, spec, workerId);
        }
    }

    String workerApiKey(WorkerScenarioSpec spec) {
        if (options.workerApiKey() != null && !options.workerApiKey().isBlank()) {
            return options.workerApiKey();
        }
        return requireNonBlank(spec.workerKey(), "workerKey");
    }

    private void markApiOnline(MassPlatform client, WorkerScenarioSpec spec, String workerId) {
        Map<String, String> attributes = spec.attributes() == null ? Map.of() : new LinkedHashMap<>(spec.attributes());
        List<String> eventCodes = spec.eventBindings() == null ? List.of() : spec.eventBindings().stream()
                .map(WorkerEventBindingSpec::eventCode)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        client.workers().online(workerId, UUID.randomUUID().toString(), "java-scenario-launcher-api-online");
        client.workers().reportCapability(workerId, WorkerCapabilityReport.builder()
                .workerId(workerId)
                .availableEventCodes(eventCodes)
                .schedulingAttributes(attributes)
                .agentVersion("java-scenario-launcher-api-online")
                .build());
        client.workers().reportState(workerId, WorkerStateReport.builder()
                .workerId(workerId)
                .available()
                .reason("java-scenario-launcher-api-online")
                .observedAt(Instant.now())
                .attributes(withSource(attributes))
                .build());
    }

    private static Map<String, String> withSource(Map<String, String> attributes) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("source", "java-scenario-launcher");
        if (attributes != null) {
            result.putAll(attributes);
        }
        return result;
    }

    static String adapterNodeIdFor(WorkerScenarioSpec spec) {
        if (spec.adapterNodeId() != null && !spec.adapterNodeId().isBlank()) {
            return spec.adapterNodeId().trim();
        }
        return "sample-" + adapterTypeFor(spec) + "-node";
    }

    static String adapterTypeFor(WorkerScenarioSpec spec) {
        if (spec.adapterId() != null && !spec.adapterId().isBlank()) {
            return spec.adapterId().trim();
        }
        return "websocket";
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
