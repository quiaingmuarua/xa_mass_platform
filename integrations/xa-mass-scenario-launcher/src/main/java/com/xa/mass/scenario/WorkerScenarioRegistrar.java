package com.xa.mass.scenario;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.WorkerGroupSpec;
import com.xa.mass.client.worker.WorkerHandlerEvidence;
import com.xa.mass.client.worker.WorkerRuntimeEvidence;
import com.xa.mass.client.worker.WorkerSpec;

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

    private void registerWorker(WorkerScenarioSpec spec, boolean markApiOnline) {
        String workerId = requireNonBlank(spec.workerId(), "workerId");
        String workerGroupId = requireNonBlank(spec.workerGroupId(), "workerGroupId");
        String adapterType = adapterTypeFor(spec);
        MassPlatform client = clientFactory.forApiKey(workerApiKey(spec));

        if (declaredWorkerGroups.add(workerGroupId)) {
            client.workers().declareGroup(WorkerGroupSpec.builder()
                    .groupId(workerGroupId)
                    .eventBindings(spec.eventBindings())
                    .build());
            System.out.printf("[java-scenario-launcher] declared worker group %s%n", workerGroupId);
        }

        client.workers().registerWorker(WorkerSpec.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .transportHint(transportHintFor(spec, adapterType))
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
        client.workers().reportHandlerEvidence(workerId, WorkerHandlerEvidence.builder()
                .workerId(workerId)
                .eventCodes(eventCodes)
                .attributes(attributes)
                .agentVersion("java-scenario-launcher-api-online")
                .build());
        client.workers().reportRuntimeEvidence(workerId, WorkerRuntimeEvidence.builder()
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

    static String adapterTypeFor(WorkerScenarioSpec spec) {
        if (spec.adapterType() != null && !spec.adapterType().isBlank()) {
            return spec.adapterType().trim();
        }
        return "websocket";
    }

    static String transportHintFor(WorkerScenarioSpec spec, String adapterType) {
        if (spec.transportHint() != null && !spec.transportHint().isBlank()) {
            return spec.transportHint().trim();
        }
        if ("polling".equals(adapterType)) {
            return "polling";
        }
        return "realtime";
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }
}
