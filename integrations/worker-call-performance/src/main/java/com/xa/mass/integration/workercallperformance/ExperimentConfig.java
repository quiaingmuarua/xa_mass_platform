package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Harness owns the finite experiment configuration; the runner consumes its resolved JSON. */
final class ExperimentConfig {
    record Settings(int workers, int warmupSeconds, int seconds, int items, long ttlMillis, int settleSeconds, boolean referenceLane) {}
    private final Map<String, Object> values;

    ExperimentConfig(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
        if (integer(values, "schemaVersion", 2, 2) != 2) throw new IllegalArgumentException("Experiment version");
        if (!Set.of("capacity", "smoke", "lane").contains(CallApi.string(values, "purpose")))
            throw new IllegalArgumentException("Unknown experiment purpose");
        boolean lane = "lane".equals(values.get("purpose"));
        CallApi.string(values, "name");
        if (!lane) integer(values, "targetQps", 1, 100_000);
        integer(values, "ttlMillis", 60_000, 1_800_000);
        integer(values, "settleSeconds", 1, 60);
        integer(values, "caseTimeoutSeconds", 60, 1200);
        for (String stage : lane ? List.of("screening") : List.of("screening", "confirmation")) {
            var window = object(stage);
            integer(window, "warmupSeconds", 0, 60);
            integer(window, "measurementSeconds", 1, 120);
            int items = integer(window, "itemsPerGroup", 100, 2_000_000);
            if (items % 100 != 0) throw new IllegalArgumentException("Items must be a multiple of 100");
        }
        if (!lane) {
            integer(object("confirmation"), "anyRepetitions", 1, 5);
            integer(object("confirmation"), "targetedRepetitions", 0, 3);
        }
        if (lane && profiles().size() != 1)
            throw new IllegalArgumentException("Reference lane requires exactly one fixed profile");
        var names = new java.util.HashSet<String>();
        for (var profile : profiles()) {
            if (!names.add(CallApi.string(profile, "name"))) throw new IllegalArgumentException("Duplicate profile");
            integer(profile, "cpuCount", 1, 16);
            integer(profile, "workersPerGroup", 1, 4000);
        }
        var resources = object("resources");
        for (String role : List.of("server", "host", "harness"))
            integer(resources, role + "HeapMiB", 256, 4096);
        integer(resources, "redisMemoryMiB", 256, 8192);
        integer(resources, "redisMaxmemoryMiB", 128, integer(resources, "redisMemoryMiB", 256, 8192));
        integer(resources, "fileDescriptors", 1024, 32768);
        integer(resources, "nativeThreads", 64, 512);
        if ("capacity".equals(values.get("purpose"))
                && (integer(values, "targetQps", 1, 100_000) != 10_000
                || integer(object("confirmation"), "measurementSeconds", 1, 120) != 120
                || integer(object("confirmation"), "anyRepetitions", 1, 5) != 5))
            throw new IllegalArgumentException("Capacity acceptance requires 10000/s, 120 seconds and five Any repetitions");
    }

    static ExperimentConfig read(String path) throws Exception {
        return new ExperimentConfig(Jsons.parseObject(Files.readString(Path.of(path))));
    }

    Map<String, Object> values() { return java.util.Collections.unmodifiableMap(values); }
    Map<String, Object> object(String key) { return CallApi.object(values.get(key)); }
    List<Map<String, Object>> profiles() {
        if (!(values.get("profiles") instanceof List<?> list) || list.isEmpty() || list.size() > 5)
            throw new IllegalArgumentException("Expected 1..5 profiles");
        return list.stream().map(CallApi::object).toList();
    }
    Map<String, Object> profile(String name) {
        return profiles().stream().filter(p -> name.equals(p.get("name"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown experiment profile"));
    }
    Settings settings(String profile, String stage) {
        if (!Set.of("screening", "confirmation").contains(stage)) throw new IllegalArgumentException("Unknown stage");
        var window = object(stage);
        return new Settings(integer(profile(profile), "workersPerGroup", 1, 4000),
                integer(window, "warmupSeconds", 0, 60), integer(window, "measurementSeconds", 1, 120),
                integer(window, "itemsPerGroup", 100, 2_000_000), integer(values, "ttlMillis", 60_000, 1_800_000),
                integer(values, "settleSeconds", 1, 60), "lane".equals(values.get("purpose")));
    }
    static Settings settings(Map<String, String> options) throws Exception {
        if (!options.containsKey("--experiment-config")) return new Settings(1000, 0, 30, 150_000, 900_000, 30, false);
        return read(options.get("--experiment-config")).settings(options.get("--experiment-profile"),
                options.getOrDefault("--experiment-stage", "screening"));
    }
    static int integer(Map<String, Object> source, String key, int minimum, int maximum) {
        Object raw = source.get(key);
        if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < minimum || n.intValue() > maximum)
            throw new IllegalArgumentException("Invalid experiment " + key);
        return n.intValue();
    }
    static void resolve(Map<String, String> options) throws Exception {
        Path output = Path.of(options.get("--output"));
        Files.createDirectories(output);
        Files.writeString(output.resolve("experiment.json"), Jsons.toJson(read(options.get("--experiment-config")).values()));
    }
}
