package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentConfigTest {
    private static final String KEY = "xak_private_perf_operator";

    @Test
    void resolverProducesOnlyCoordinatesAndStableTasksWithNoCredentialContents(@TempDir Path folder) throws Exception {
        Path source = configuration(folder, Map.of());
        var config = DeploymentConfig.read(source.toString());
        var output = folder.resolve("resolved");
        WorkerCallPerformanceMain.main(new String[]{"--phase=deployment-config", "--deployment-config=" + source,
                "--output=" + output});
        String json = Files.readString(output.resolve("deployment.json"));
        var resolved = Jsons.parseObject(json);
        assertThat(resolved).containsEntry("runtimeUrl", "https://admin.example")
                .containsEntry("workerRuntimeUrl", "https://workers.example")
                .containsEntry("hostMaxHeap", "3g").containsEntry("hostFdLimit", 32_768L)
                .containsEntry("accessKeyFile", folder.resolve("operator.key").toString());
        assertThat(json).doesNotContain(KEY, "private-gateway-value");
        assertThat(config.callerHeaders()).containsEntry("Authorization", "Bearer " + KEY);
        try (var api = LaneWorld.coordinates(Map.of("--deployment-config", source.toString())).api(Map.of())) {
            assertThat(api.observationPageConcurrency()).isEqualTo(8);
        }
        try (var api = LaneWorld.coordinates(Map.of()).api(Map.of())) {
            assertThat(api.observationPageConcurrency()).isEqualTo(1);
        }
        assertThat(config.rpcTaskId("wan-a")).isEqualTo("rpc-0000000000000000000000000000000a")
                .isEqualTo(DeploymentConfig.read(source.toString()).rpcTaskId("wan-a"))
                .isNotEqualTo(config.rpcTaskId("wan-b"));
    }

    @Test
    void rejectsAmbiguousTopologyUnsafeAuthoritiesAndUnboundedInputs(@TempDir Path folder) throws Exception {
        var invalid = List.<Map<String, Object>>of(Map.of("groups", List.of("one")), Map.of("groups", List.of("a", "a")),
                Map.of("groups", List.of("a", "b", "c")), Map.of("groups", List.of("a/b", "c")),
                Map.of("runtimeUrl", "http://admin.example"), Map.of("runtimeUrl", "https://user:secret@admin.example"),
                Map.of("runtimeUrl", "https://admin.example/api"), Map.of("runtimeUrl", "https://admin.example?key=secret"),
                Map.of("workersPerGroup", 15_001), Map.of("workersPerGroup", 0), Map.of("assignmentBatchLimit", 0),
                Map.of("assignmentBatchLimit", 1_001), Map.of("hostMaxHeap", "3g -Dsecret=value"), Map.of("unknown", "value"));
        for (var override : invalid) {
            Path source = configuration(folder, override);
            assertThatThrownBy(() -> DeploymentConfig.read(source.toString())).as("%s", override.keySet())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
        Path source = configuration(folder, Map.of());
        assertThatThrownBy(() -> DeploymentConfig.from(Map.of("--deployment-config", source.toString(),
                "--runtime-url", "http://other"))).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(folder.resolve("operator.key"), "private-key-that-is-not-valid");
        assertThatThrownBy(() -> DeploymentConfig.read(source.toString()).callerHeaders())
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-key-that-is-not-valid");
    }

    @Test
    void deploymentWorldUsesConfiguredGroupsAndAllowsFifteenThousandWithoutChangingLocalBound(@TempDir Path folder) throws Exception {
        Path source = configuration(folder, Map.of("workersPerGroup", 15_000));
        var coordinates = LaneWorld.coordinates(Map.of("--deployment-config", source.toString()));
        var groups = new LinkedHashMap<String, Object>();
        for (String group : coordinates.groups()) groups.put(group, Map.of("managedTaskId", coordinates.rpcTaskId(group),
                "workerIds", IntStream.range(0, 15_000).mapToObj(i -> group + "-" + i).toList()));
        Path world = folder.resolve("bootstrap.json");
        Files.writeString(world, Jsons.toJson(Map.of("groups", groups, "workersPerGroup", 15_000)));
        var identities = LaneWorld.readWorld(world, coordinates);
        assertThat(identities.keySet()).containsExactly("wan-a", "wan-b");
        assertThat(identities.get("wan-a")).hasSize(15_000);
        assertThat(LaneWorld.readTasks(world, coordinates)).containsEntry("wan-a", coordinates.rpcTaskId("wan-a"));
        assertThat(LaneCase.target(3, identities, coordinates.groups())).isEqualTo(new LaneCase.Target("wan-b", "wan-b-1"));
        assertThat(LaneCase.groupOf(new CallLoad.Sample("measurement-3", 0), coordinates.groups())).isEqualTo("wan-b");
        assertThatThrownBy(() -> LaneWorld.readWorld(world)).isInstanceOf(IllegalArgumentException.class);
        groups.put("wan-b", Map.of("managedTaskId", coordinates.rpcTaskId("wan-b"), "workerIds", List.of("only-one")));
        Files.writeString(world, Jsons.toJson(Map.of("groups", groups, "workersPerGroup", 15_000)));
        assertThatThrownBy(() -> LaneWorld.readWorld(world, coordinates)).isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test
    void deploymentBootstrapChecksBothBindingsAndPreProvisionedRpcTasksWithoutMutatingServer(@TempDir Path folder) throws Exception {
        var coordinates = LaneWorld.coordinates(Map.of("--deployment-config", configuration(folder, Map.of()).toString()));
        var taskReads = new ArrayList<String>();
        var bindingsRead = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            Object response;
            if (path.endsWith("/bindings")) {
                bindingsRead.incrementAndGet();
                String group = path.contains("wan-a") ? "wan-a" : "wan-b";
                response = bindings(group, "project-wan");
            } else if (method.equals("GET") && path.startsWith("/api/v1/projects/project-wan/tasks/")) {
                taskReads.add(path);
                if (bindingsRead.get() != 2) throw new AssertionError("Task read preceded binding checks");
                String id = path.substring(path.lastIndexOf('/') + 1);
                String group = id.equals(coordinates.rpcTaskId("wan-a")) ? "wan-a" : "wan-b";
                response = Map.of("taskId", id, "scoreBand", "running_visible",
                        "task", Map.of("taskId", id, "workerGroupId", group, "idleDisposition", "PARK_WHEN_IDLE"));
            } else if (path.equals("/lab/v1/workers")) {
                var workers = new ArrayList<Map<String, Object>>();
                coordinates.groups().forEach(group -> IntStream.range(0, 2).forEach(i -> workers.add(Map.of(
                        "workerGroupId", group, "workerId", group + "-" + i, "runtimeState", "RUNNING"))));
                response = Map.of("workers", workers);
            } else if (path.endsWith("/workers:preview")) {
                String group = path.contains("wan-a") ? "wan-a" : "wan-b";
                response = Map.of("workers", IntStream.range(0, 2).mapToObj(i -> Map.of("workerId", group + "-" + i,
                        "workerProperties", Map.of("capability", "string-utils"))).toList());
            } else {
                if (!method.equals("POST") || !(path.endsWith("/workers:network-observe")
                        || path.endsWith("/workers:scheduling-observe"))) throw new AssertionError("Unexpected mutation " + path);
                if (path.contains("endpoint-managers") && !path.contains("/wan-endpoint/")) throw new AssertionError("Wrong Endpoint");
                var states = new LinkedHashMap<String, Object>();
                Jsons.parseArray(request).forEach(id -> states.put((String) id,
                        path.endsWith("/workers:network-observe") ? "connected" : "hot-score-overdue"));
                response = Map.of("statesByWorkerId", states);
            }
            byte[] bytes = Jsons.toJson(response).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var api = new CallApi(base, base, coordinates.deployment().callerHeaders(), coordinates.endpoint())) {
            var bootstrap = LaneWorld.bootstrap(api, coordinates);
            assertThat(bootstrap).containsEntry("workersPerGroup", 2).containsEntry("endpoint", "wan-endpoint");
            assertThat(taskReads).containsExactly(
                    "/api/v1/projects/project-wan/tasks/" + coordinates.rpcTaskId("wan-a"),
                    "/api/v1/projects/project-wan/tasks/" + coordinates.rpcTaskId("wan-b")
            );
        } finally { server.stop(0); }
    }

    @Test
    void missingQueryOrProjectPreventsAnyBootstrapWrites(@TempDir Path folder) throws Exception {
        var coordinates = LaneWorld.coordinates(Map.of("--deployment-config", configuration(folder, Map.of()).toString()));
        var writes = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) writes.incrementAndGet();
            byte[] bytes = Jsons.toJson(bindings("wan-a", "other-project")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused", Map.of())) {
            assertThatThrownBy(() -> LaneWorld.bootstrap(api, coordinates)).isInstanceOf(CallLoad.ProtocolFailure.class);
            assertThat(writes).hasValue(0);
        } finally { server.stop(0); }
    }

    @Test
    void deploymentCannotRunSaturationOrReplaceItsResourceConfiguration(@TempDir Path folder) throws Exception {
        String config = configuration(folder, Map.of()).toString();
        assertThatThrownBy(() -> WorkerCallPerformanceMain.main(new String[]{"--phase=case", "--case=sat-task-any",
                "--deployment-config=" + config, "--output=" + folder.resolve("case")}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("open-loop");
        assertThatThrownBy(() -> DeploymentConfig.from(Map.of("--deployment-config", config, "--experiment-config", "experiment.json")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkerCallPerformanceMain.main(new String[]{"--phase=calibrate",
                "--deployment-config=" + config, "--output=" + folder.resolve("calibration")}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("open-loop");
    }

    private static Map<String, Object> bindings(String group, String project) {
        return Map.of("workerGroupId", group,
                "workerGroup", Map.of("workerGroupId", group, "eventCodes", List.of("extension.worker.string.md5")),
                "matching", Map.of("pools", List.of("any"), "functions", List.of(
                        Map.of("name", "worker.any", "poolName", "any"), Map.of("name", "workerId"))),
                "projects", List.of(Map.of("projectId", project)));
    }

    private static Path configuration(Path folder, Map<String, Object> overrides) throws Exception {
        Files.writeString(folder.resolve("operator.key"), KEY);
        Files.writeString(folder.resolve("gateway.json"), "private-gateway-value");
        var input = new LinkedHashMap<String, Object>();
        input.put("runtimeUrl", "https://admin.example/");
        input.put("workerRuntimeUrl", "https://workers.example/");
        input.put("project", "project-wan"); input.put("endpointManager", "wan-endpoint");
        input.put("groups", List.of("wan-a", "wan-b"));
        input.put("rpcTaskIds", Map.of("wan-a", "rpc-0000000000000000000000000000000a", "wan-b", "rpc-0000000000000000000000000000000b"));
        input.put("workersPerGroup", 2);
        input.put("consumerId", "consumer-one"); input.put("accessKeyFile", "operator.key");
        input.put("gatewayCredentialFile", "gateway.json"); input.put("assignmentBatchLimit", 100);
        input.put("serverDeployment", Map.of("version", "4.3.2", "gitCommit", "a".repeat(40), "configurationId", "deployment-one"));
        input.putAll(overrides);
        Path file = folder.resolve("deployment-source.json"); Files.writeString(file, Jsons.toJson(input)); return file;
    }
}
