package com.xa.mass.integration.workerfleet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerFleetAcceptanceArchitectureTest {

    @Test
    void moduleIsOnlyAnExternalRuntimeApiClient() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(build.contains(
                "project(':transport:worker-delivery-contract')"
        ));
        for (String forbidden : new String[]{
                "project(':server_jvm')",
                "project(':scenario_workers_jvm')",
                "project(':kernel_jvm')",
                "project(':transport:netty-adapter')",
                "project(':transport:java-worker')",
                "springframework",
                "io.lettuce"
        }) {
            assertFalse(build.contains(forbidden), forbidden);
        }
    }

    @Test
    void sourcesDoNotOwnPlatformMechanismsOrLogOpaqueValues()
            throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/integration/workerfleet"
        );
        StringBuilder sources = new StringBuilder();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path ->
                    path.toString().endsWith(".java")
            ).toList()) {
                sources.append(Files.readString(file));
            }
        }
        String production = sources.toString();
        for (String forbidden : new String[]{
                "JavaWorkerManager",
                "ScenarioWorkers",
                "WorkerRunController",
                "NettyWorkerDeliveryAdapter",
                "RedisClient",
                "import com.xa.mass.server.",
                "import com.xa.mass.kernel.",
                "import com.xa.mass.scenarioworkers.",
                "properties.toString()"
        }) {
            assertFalse(production.contains(forbidden), forbidden);
        }
        assertTrue(production.contains(
                "/api/v1/runtime-view/endpoint-managers/"
        ));
        assertTrue(production.contains(
                "/api/v1/worker-delivery/endpoint-managers/"
        ));

        String evidence = Files.readString(root.resolve(
                "FleetEvidence.java"
        ));
        for (String forbidden : new String[]{
                "opaqueResultPayload",
                "updatedAtMillis",
                "propertiesByWorkerId"
        }) {
            assertFalse(evidence.contains(forbidden), forbidden);
        }
    }
}
