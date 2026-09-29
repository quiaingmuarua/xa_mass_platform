package com.xa.mass.integration.workercallperformance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared performance-lane world: two Groups of 1,000 Workers behind one Adapter, prepared
 * once per job. Bootstrap establishes readiness; the quiesce gate separates later cases.
 */
final class LaneWorld {
    static final List<String> GROUPS = List.of("perf-a", "perf-b");
    static final String PROJECT = "perf-lane";
    static final String ENDPOINT = "scenario-websocket";
    static final int WORKERS_PER_GROUP = 1_000;
    static final long BOOTSTRAP_TIMEOUT_NANOS = 240_000_000_000L;
    static final long QUIESCE_TIMEOUT_NANOS = 30_000_000_000L;
    /** Consecutive fully quiet observations required, one second apart. */
    static final int QUIET_PASSES = 2;
    private static final String IDLE = "hot-score-overdue";

    private LaneWorld() {}

    static void run(Map<String, String> options) throws Exception {
        String phase = options.get("--phase");
        Path output = Path.of(require(options, "--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("phase", phase);
        summary.put("status", "failed");
        Exception failure = null;
        long started = System.nanoTime();
        try (var api = new CallApi(options.getOrDefault("--runtime-url", "http://127.0.0.1:18082"),
                options.getOrDefault("--lab-url", "http://127.0.0.1:18086"))) {
            switch (phase) {
                case "bootstrap" -> summary.putAll(bootstrap(api, ExperimentConfig.settings(options).workers()));
                case "quiesce" -> summary.putAll(quiesce(api, readWorld(Path.of(require(options, "--world")))));
                default -> throw new IllegalArgumentException("Unknown lane phase");
            }
            summary.put("status", "passed");
        } catch (Exception error) {
            failure = error;
            summary.put("failureType", error.getClass().getSimpleName());
            if (error instanceof IllegalStateException) summary.put("failure", error.getMessage());
        } finally {
            summary.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
            Files.writeString(output.resolve(phase + ".json"), Jsons.toJson(summary), StandardOpenOption.CREATE_NEW);
        }
        if (failure != null) throw new IllegalStateException("Lane world " + phase + " failed; inspect safe summary", failure);
    }

    private static Map<String, Object> bootstrap(CallApi api, int workers) throws Exception {
        var ids = readyGroups(api, workers);
        var groups = new LinkedHashMap<String, Object>();
        var project = CallApi.object(api.get("/api/v1/projects/" + PROJECT).get("managedTaskIds"));
        for (String group : GROUPS) {
            var registration = api.post("/api/v1/worker-groups/" + group + ":register", Map.of(
                    "attributes", Map.of("capability", "string-utils"),
                    "eventCodes", List.of("extension.worker.string.md5", "extension.worker.lab.delay")));
            if (!group.equals(registration.get("workerGroupId")))
                throw new CallLoad.ProtocolFailure("Registration Group changed");
            groups.put(group, Map.of("managedTaskId", CallApi.string(project, group), "workerIds", ids.get(group)));
        }
        return Map.of("groups", groups, "workersPerGroup", workers, "endpoint", ENDPOINT,
                "propertiesObservation", workers <= 1000 ? "complete-known-fixture" : "bounded-sample");
    }

    /** Waits until every Worker is RUNNING in the Lab, connected, idle HOT and has published facts. */
    private static Map<String, List<String>> readyGroups(CallApi api, int workers) throws Exception {
        long deadline = System.nanoTime() + BOOTSTRAP_TIMEOUT_NANOS;
        int unavailable = 0;
        do {
            try {
                var ids = labWorkers(api, workers);
                if (ids != null && ready(api, ids)) return ids;
            } catch (CallApi.Unavailable busy) {
                // Runtime View may be briefly unavailable while 2,000 connections are verified.
                unavailable++;
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(GROUPS.size() * workers
                + " lane Workers did not become ready; unavailable observations " + unavailable);
    }

    private static boolean ready(CallApi api, Map<String, List<String>> ids) throws Exception {
        for (String group : GROUPS) {
            var groupIds = ids.get(group);
            if (!allIn(observe(api, networkPath(), groupIds), Set.of("connected"))
                    || !allIn(observe(api, schedulingPath(group), groupIds), Set.of("held-hot", IDLE))
                    || !facts(api, group, groupIds)) return false;
        }
        return true;
    }

    /** Sorted RUNNING Worker IDs per Group, or null while the Lab has not assigned every identity. */
    private static Map<String, List<String>> labWorkers(CallApi api, int expected) throws Exception {
        if (!(api.workers().get("workers") instanceof List<?> workers) || workers.size() != GROUPS.size() * expected)
            throw new CallLoad.ProtocolFailure("Unexpected Lab Worker count");
        var ids = new LinkedHashMap<String, List<String>>();
        GROUPS.forEach(group -> ids.put(group, new ArrayList<>()));
        for (var entry : workers) {
            var worker = CallApi.object(entry);
            var group = ids.get(worker.get("workerGroupId"));
            if (group == null) throw new CallLoad.ProtocolFailure("Unexpected Lab Group");
            if ("RUNNING".equals(worker.get("runtimeState")) && worker.get("workerId") instanceof String id) group.add(id);
        }
        for (var group : ids.values()) {
            if (group.size() != expected || Set.copyOf(group).size() != expected) return null;
            group.sort(null);
        }
        return ids;
    }

    private static boolean facts(CallApi api, String group, List<String> ids) throws Exception {
        int limit = Math.min(1000, ids.size());
        var preview = api.post("/api/v1/runtime-view/worker-groups/" + group + "/workers:preview", limit);
        if (!(preview.get("workers") instanceof List<?> entries) || entries.size() != limit) return false;
        var seen = new java.util.HashSet<Object>();
        for (var entry : entries) {
            var worker = CallApi.object(entry);
            if (!(worker.get("workerProperties") instanceof Map<?, ?> properties) || properties.isEmpty()) return false;
            seen.add(worker.get("workerId"));
        }
        return seen.size() == limit && Set.copyOf(ids).containsAll(seen);
    }

    /**
     * Quiet means every Worker of both Groups is connected and idle due HOT: no execution hold,
     * RECOVERY or PAUSE. The state must hold for consecutive observations one second apart.
     */
    private static Map<String, Object> quiesce(CallApi api, Map<String, List<String>> world) throws Exception {
        return quiesce(api, world, 30);
    }

    static Map<String, Object> quiesce(CallApi api, Map<String, List<String>> world, int seconds) throws Exception {
        long started = System.nanoTime();
        long deadline = started + seconds * 1_000_000_000L;
        int passes = 0;
        int observations = 0;
        Map<String, Map<String, Long>> lastNotQuiet = Map.of();
        do {
            observations++;
            var notQuiet = new LinkedHashMap<String, Map<String, Long>>();
            try {
                for (String group : GROUPS) {
                    var ids = world.get(group);
                    var states = new LinkedHashMap<String, Long>();
                    count(states, observe(api, networkPath(), ids), "connected", "network:");
                    count(states, observe(api, schedulingPath(group), ids), IDLE, "scheduling:");
                    if (!states.isEmpty()) notQuiet.put(group, states);
                }
            } catch (CallApi.Unavailable busy) {
                notQuiet.put("runtime-view", Map.of("unavailable", 1L));
            }
            passes = notQuiet.isEmpty() ? passes + 1 : 0;
            lastNotQuiet = notQuiet;
            if (passes >= QUIET_PASSES)
                return Map.of("quietAfterMillis", (System.nanoTime() - started) / 1_000_000, "observations", observations);
            Thread.sleep(1_000);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Lane world did not quiesce; non-quiet states " + Jsons.toJson(lastNotQuiet));
    }

    /** Adds a count per state for every Worker whose state differs from the quiet one. */
    static void count(Map<String, Long> counts, Map<String, Object> states, String quiet, String prefix) {
        states.values().forEach(state -> {
            if (!quiet.equals(state)) counts.merge(prefix + state, 1L, Long::sum);
        });
    }

    static boolean allIn(Map<String, Object> states, Set<String> accepted) {
        return states.values().stream().allMatch(accepted::contains);
    }

    static Map<String, Object> observeScheduling(CallApi api, String group, List<String> ids) throws Exception {
        return observe(api, schedulingPath(group), ids);
    }

    /** Bounded public observation in pages of 100; the identity set must be returned exactly. */
    private static Map<String, Object> observe(CallApi api, String path, List<String> ids) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        for (int offset = 0; offset < ids.size(); offset += 100) {
            var page = ids.subList(offset, Math.min(offset + 100, ids.size()));
            var states = CallApi.object(api.post(path, page).get("statesByWorkerId"));
            if (!states.keySet().equals(Set.copyOf(page))) throw new CallLoad.ProtocolFailure("Worker observation identity changed");
            result.putAll(states);
        }
        return result;
    }

    private static String networkPath() {
        return "/api/v1/runtime-view/endpoint-managers/" + ENDPOINT + "/workers:network-observe";
    }

    private static String schedulingPath(String group) {
        return "/api/v1/runtime-view/worker-groups/" + group + "/workers:scheduling-observe";
    }

    static Map<String, List<String>> readWorld(Path bootstrap) throws Exception {
        var root = Jsons.parseObject(Files.readString(bootstrap));
        int workers = root.containsKey("workersPerGroup")
                ? ExperimentConfig.integer(root, "workersPerGroup", 1, 4000) : WORKERS_PER_GROUP;
        var groups = CallApi.object(root.get("groups"));
        var world = new LinkedHashMap<String, List<String>>();
        for (String group : GROUPS) {
            if (!(CallApi.object(groups.get(group)).get("workerIds") instanceof Collection<?> ids)
                    || ids.size() != workers || Set.copyOf(ids).size() != workers)
                throw new CallLoad.ProtocolFailure("Bootstrap world is incomplete");
            world.put(group, ids.stream().map(String.class::cast).toList());
        }
        return world;
    }

    static Map<String, String> readTasks(Path bootstrap) throws Exception {
        var groups = CallApi.object(Jsons.parseObject(Files.readString(bootstrap)).get("groups"));
        var tasks = new LinkedHashMap<String, String>();
        for (String group : GROUPS) tasks.put(group, CallApi.string(CallApi.object(groups.get(group)), "managedTaskId"));
        return tasks;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
}
