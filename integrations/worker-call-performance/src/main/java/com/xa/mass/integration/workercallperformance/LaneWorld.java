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

    record Coordinates(List<String> groups, String project, String endpoint, int workersPerGroup,
                       int maximumWorkersPerGroup, int assignmentBatchLimit, DeploymentConfig deployment) {
        Coordinates { groups = List.copyOf(groups); }

        CallApi api(Map<String, String> options) throws Exception {
            return new CallApi(deployment == null ? options.getOrDefault("--runtime-url", "http://127.0.0.1:18082")
                    : deployment.runtimeUrl(), options.getOrDefault("--lab-url", "http://127.0.0.1:18086"),
                    deployment == null ? Map.of("Authorization", "Bearer xak_dev_1000") : deployment.callerHeaders(),
                    endpoint, deployment == null ? 1 : 8);
        }

        String rpcTaskId(String group) { return deployment == null ? "perf-rpc-" + group : deployment.rpcTaskId(group); }
    }

    static Coordinates coordinates(Map<String, String> options) throws Exception {
        var deployment = DeploymentConfig.from(options);
        return deployment == null ? reference(ExperimentConfig.settings(options).workers())
                : new Coordinates(deployment.groups(), deployment.project(), deployment.endpointManager(),
                        deployment.workersPerGroup(), 15_000, deployment.assignmentBatchLimit(), deployment);
    }

    private static Coordinates reference(int workers) {
        return new Coordinates(GROUPS, PROJECT, ENDPOINT, workers, 4_000, LaneCase.ASSIGNMENT_BATCH_LIMIT, null);
    }

    private LaneWorld() {}

    static void run(Map<String, String> options) throws Exception {
        String phase = options.get("--phase");
        var coordinates = coordinates(options);
        Path output = Path.of(require(options, "--output"));
        Files.createDirectories(output);
        var summary = new LinkedHashMap<String, Object>();
        summary.put("phase", phase);
        summary.put("status", "failed");
        Exception failure = null;
        long started = System.nanoTime();
        try (var api = coordinates.api(options)) {
            switch (phase) {
                case "bootstrap" -> summary.putAll(bootstrap(api, coordinates));
                case "quiesce" -> summary.putAll(quiesce(api, readWorld(Path.of(require(options, "--world")), coordinates),
                        30, coordinates));
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

    static Map<String, Object> bootstrap(CallApi api, Coordinates coordinates) throws Exception {
        if (coordinates.deployment() != null) preflight(api, coordinates);
        var prepared = new LinkedHashMap<String, String>();
        for (String group : coordinates.groups()) {
            String id = coordinates.rpcTaskId(group);
            var response = api.post("/api/v1/tasks/" + id + "/rpc:prepare",
                    Map.of("projectId", coordinates.project(), "workerGroupId", group));
            if (!id.equals(CallApi.string(response, "taskId")))
                throw new CallLoad.ProtocolFailure("RPC Task preparation changed identity");
            prepared.put(group, id);
        }
        var ids = readyGroups(api, coordinates);
        var groups = new LinkedHashMap<String, Object>();
        for (String group : coordinates.groups()) {
            if (coordinates.deployment() == null) {
                var registration = api.post("/api/v1/worker-groups/" + group + ":register", Map.of(
                        "attributes", Map.of("capability", "string-utils"),
                        "eventCodes", List.of("extension.worker.string.md5", "extension.worker.lab.delay")));
                if (!group.equals(registration.get("workerGroupId")))
                    throw new CallLoad.ProtocolFailure("Registration Group changed");
            }
            groups.put(group, Map.of("managedTaskId", prepared.get(group), "workerIds", ids.get(group)));
        }
        return Map.of("groups", groups, "workersPerGroup", coordinates.workersPerGroup(), "endpoint", coordinates.endpoint(),
                "propertiesObservation", coordinates.workersPerGroup() <= 1000 ? "complete-known-fixture" : "bounded-sample");
    }

    static void preflight(CallApi api, Coordinates coordinates) throws Exception {
        for (String group : coordinates.groups()) {
            var bindings = api.get("/api/v1/runtime-view/worker-groups/" + group + "/bindings");
            var descriptor = CallApi.object(bindings.get("workerGroup"));
            var matching = CallApi.object(bindings.get("matching"));
            if (!group.equals(bindings.get("workerGroupId")) || !group.equals(descriptor.get("workerGroupId"))
                    || !(descriptor.get("eventCodes") instanceof List<?> events)
                    || !events.contains("extension.worker.string.md5")
                    || !(matching.get("pools") instanceof List<?> pools) || !pools.contains("any")
                    || !(matching.get("functions") instanceof List<?> functions)
                    || !functions.stream().map(CallApi::object).anyMatch(f -> "worker.any".equals(f.get("name"))
                            && "any".equals(f.get("poolName")))
                    || !functions.stream().map(CallApi::object).anyMatch(f -> "workerId".equals(f.get("name")))
                    || !(bindings.get("projects") instanceof List<?> projects)
                    || !projects.stream().map(CallApi::object).anyMatch(p -> coordinates.project().equals(p.get("projectId"))))
                throw new CallLoad.ProtocolFailure("Deployment Group is missing the declared Project, MD5 event or query resources");
        }
    }

    /** Waits until every Worker is RUNNING in the Lab, connected, idle HOT and has published facts. */
    private static Map<String, List<String>> readyGroups(CallApi api, Coordinates coordinates) throws Exception {
        long deadline = System.nanoTime() + BOOTSTRAP_TIMEOUT_NANOS;
        int unavailable = 0;
        do {
            try {
                var ids = labWorkers(api, coordinates);
                if (ids != null && ready(api, ids, coordinates)) return ids;
            } catch (CallApi.Unavailable busy) {
                // Runtime View may be briefly unavailable while 2,000 connections are verified.
                unavailable++;
            }
            Thread.sleep(500);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException(coordinates.groups().size() * coordinates.workersPerGroup()
                + " lane Workers did not become ready; unavailable observations " + unavailable);
    }

    private static boolean ready(CallApi api, Map<String, List<String>> ids, Coordinates coordinates) throws Exception {
        for (String group : coordinates.groups()) {
            var groupIds = ids.get(group);
            if (!allIn(observe(api, networkPath(coordinates.endpoint()), groupIds), Set.of("connected"))
                    || !allIn(observe(api, schedulingPath(group), groupIds), Set.of("held-hot", IDLE))
                    || !facts(api, group, groupIds)) return false;
        }
        return true;
    }

    /** Sorted RUNNING Worker IDs per Group, or null while the Lab has not assigned every identity. */
    private static Map<String, List<String>> labWorkers(CallApi api, Coordinates coordinates) throws Exception {
        int expected = coordinates.workersPerGroup();
        if (!(api.workers().get("workers") instanceof List<?> workers) || workers.size() != coordinates.groups().size() * expected)
            throw new CallLoad.ProtocolFailure("Unexpected Lab Worker count");
        var ids = new LinkedHashMap<String, List<String>>();
        coordinates.groups().forEach(group -> ids.put(group, new ArrayList<>()));
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
    static Map<String, Object> quiesce(CallApi api, Map<String, List<String>> world, int seconds) throws Exception {
        return quiesce(api, world, seconds, reference(WORKERS_PER_GROUP));
    }

    private static Map<String, Object> quiesce(CallApi api, Map<String, List<String>> world, int seconds,
                                             Coordinates coordinates) throws Exception {
        long started = System.nanoTime();
        long deadline = started + seconds * 1_000_000_000L;
        int passes = 0;
        int observations = 0;
        Map<String, Map<String, Long>> lastNotQuiet = Map.of();
        do {
            observations++;
            var notQuiet = new LinkedHashMap<String, Map<String, Long>>();
            try {
                for (String group : coordinates.groups()) {
                    var ids = world.get(group);
                    var states = new LinkedHashMap<String, Long>();
                    count(states, observe(api, networkPath(coordinates.endpoint()), ids), "connected", "network:");
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
        if (api.observationPageConcurrency() == 1) {
            for (int offset = 0; offset < ids.size(); offset += 100)
                result.putAll(observePage(api, path, ids.subList(offset, Math.min(offset + 100, ids.size()))));
            return result;
        }
        var pending = new java.util.ArrayDeque<java.util.concurrent.Future<Map<String, Object>>>();
        try (var pages = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                int offset = 0;
                while (offset < ids.size() || !pending.isEmpty()) {
                    while (offset < ids.size() && pending.size() < api.observationPageConcurrency()) {
                        var page = List.copyOf(ids.subList(offset, Math.min(offset + 100, ids.size())));
                        pending.addLast(pages.submit(() -> observePage(api, path, page)));
                        offset += 100;
                    }
                    try {
                        // Keep the original identity order even when later HTTP pages return first.
                        result.putAll(pending.removeFirst().get());
                    } catch (java.util.concurrent.ExecutionException failure) {
                        if (failure.getCause() instanceof Exception cause) throw cause;
                        if (failure.getCause() instanceof Error cause) throw cause;
                        throw new IllegalStateException("Worker observation failed", failure.getCause());
                    }
                }
            } finally {
                pending.forEach(page -> page.cancel(true));
            }
        }
        return result;
    }

    private static Map<String, Object> observePage(CallApi api, String path, List<String> page) throws Exception {
        var states = CallApi.object(api.post(path, page).get("statesByWorkerId"));
        if (!states.keySet().equals(Set.copyOf(page))) throw new CallLoad.ProtocolFailure("Worker observation identity changed");
        var ordered = new LinkedHashMap<String, Object>();
        page.forEach(id -> ordered.put(id, states.get(id)));
        return ordered;
    }

    private static String networkPath(String endpoint) {
        return "/api/v1/runtime-view/endpoint-managers/" + endpoint + "/workers:network-observe";
    }

    private static String schedulingPath(String group) {
        return "/api/v1/runtime-view/worker-groups/" + group + "/workers:scheduling-observe";
    }

    static Map<String, List<String>> readWorld(Path bootstrap) throws Exception {
        return readWorld(bootstrap, reference(WORKERS_PER_GROUP));
    }

    static Map<String, List<String>> readWorld(Path bootstrap, Coordinates coordinates) throws Exception {
        var root = Jsons.parseObject(Files.readString(bootstrap));
        int workers = root.containsKey("workersPerGroup")
                ? ExperimentConfig.integer(root, "workersPerGroup", 1, coordinates.maximumWorkersPerGroup()) : coordinates.workersPerGroup();
        if (coordinates.deployment() != null && workers != coordinates.workersPerGroup())
            throw new CallLoad.ProtocolFailure("Bootstrap and deployment Worker counts differ");
        var groups = CallApi.object(root.get("groups"));
        if (!groups.keySet().equals(Set.copyOf(coordinates.groups())))
            throw new CallLoad.ProtocolFailure("Bootstrap Group identities differ");
        var world = new LinkedHashMap<String, List<String>>();
        for (String group : coordinates.groups()) {
            if (!(CallApi.object(groups.get(group)).get("workerIds") instanceof Collection<?> ids)
                    || ids.size() != workers || ids.stream().anyMatch(id -> !(id instanceof String value) || value.isBlank())
                    || Set.copyOf(ids).size() != workers)
                throw new CallLoad.ProtocolFailure("Bootstrap world is incomplete");
            world.put(group, ids.stream().map(String.class::cast).toList());
        }
        return world;
    }

    static Map<String, String> readTasks(Path bootstrap) throws Exception {
        return readTasks(bootstrap, reference(WORKERS_PER_GROUP));
    }

    static Map<String, String> readTasks(Path bootstrap, Coordinates coordinates) throws Exception {
        var groups = CallApi.object(Jsons.parseObject(Files.readString(bootstrap)).get("groups"));
        var tasks = new LinkedHashMap<String, String>();
        for (String group : coordinates.groups()) {
            String task = CallApi.string(CallApi.object(groups.get(group)), "managedTaskId");
            if (coordinates.deployment() != null && !coordinates.rpcTaskId(group).equals(task))
                throw new CallLoad.ProtocolFailure("Bootstrap RPC Task identity differs");
            tasks.put(group, task);
        }
        return tasks;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required");
        return value;
    }
}
