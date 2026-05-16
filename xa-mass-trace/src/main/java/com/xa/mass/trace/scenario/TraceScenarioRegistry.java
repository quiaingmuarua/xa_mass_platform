package com.xa.mass.trace.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TraceScenarioRegistry {

    private final Map<String, TraceScenarioAnalyzer> analyzers;

    public TraceScenarioRegistry() {
        this(List.of(
                new SingleMessageSuccessScenarioAnalyzer(),
                new DuplicateCallbackReplayScenarioAnalyzer(),
                new AssignmentSuccessBindingScenarioAnalyzer(),
                new AssignmentMinWorkerGateScenarioAnalyzer(),
                new AssignmentRetryRedispatchScenarioAnalyzer(),
                new LoadAwareWorkerSelectionScenarioAnalyzer(),
                new CapacityReservationUnderConcurrencyScenarioAnalyzer(),
                new BackgroundWorkerSharingScenarioAnalyzer(),
                new WorkerAttributeRoutingWithoutContextScenarioAnalyzer(),
                new CrossTaskWorkerFairnessScenarioAnalyzer(),
                new WorkerResourceCleanupWithoutContextScenarioAnalyzer()
        ));
    }

    TraceScenarioRegistry(List<TraceScenarioAnalyzer> analyzers) {
        Map<String, TraceScenarioAnalyzer> byId = new LinkedHashMap<>();
        for (TraceScenarioAnalyzer analyzer : analyzers) {
            byId.put(analyzer.id(), analyzer);
        }
        this.analyzers = Map.copyOf(byId);
    }

    public TraceScenarioAnalyzer require(String id) {
        TraceScenarioAnalyzer analyzer = analyzers.get(id);
        if (analyzer == null) {
            throw new IllegalArgumentException("Unknown scenario: " + id
                    + ". Available: " + String.join(", ", analyzers.keySet()));
        }
        return analyzer;
    }

    public List<String> ids() {
        return analyzers.keySet().stream().toList();
    }
}
