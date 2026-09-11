package com.xa.mass.workersimulator;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete, deterministic Group fixtures for lifecycle tests, not a production configuration fallback. */
final class SimulatorTestConfig {
    static List<WorkerSimulatorGroupConfig> groups(String encoded) {
        Map<String, Object> groups = Jsons.parseObject(encoded);
        groups.replaceAll((id, raw) -> {
            @SuppressWarnings("unchecked") var value = new LinkedHashMap<>((Map<String, Object>) raw);
            value.putIfAbsent("count", 50);
            value.putIfAbsent("propertiesTemplate", Map.of("runtime", "java", "region", "local",
                    "capability", id.contains("phone") ? "libphonenumber" : "string-utils",
                    "labSlot", Map.of("$index", List.of(1, 1)), "convergenceSlot", "A"));
            return value;
        });
        return WorkerSimulatorJsonParser.parseGroups(groups);
    }

    static WorkerSimulatorConfig config(String groups, Path root) {
        return new WorkerSimulatorConfig(java.net.URI.create("http://127.0.0.1:18082"),
                root, 0, groups(groups), WorkerSimulatorStartupPlan.defaults());
    }

    static List<WorkerSimulatorGroupConfig> products(int count) {
        return WorkerSimulatorJsonParser.parseGroups(Map.of("demo-sim", Map.of(
                "events", List.of("extension.worker.sms.listen.start", "extension.worker.sms.listen.cancel",
                        "extension.worker.message.send"),
                "count", count, "propertiesTemplate", Map.of(
                        "phone", Map.of("$index", List.of(861700000001L, 1)),
                        "country", Map.of("$choice", List.of("CN", "US", "GB")),
                        "messaging.enabled", "true"))));
    }
}
