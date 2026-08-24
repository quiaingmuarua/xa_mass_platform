package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerCapabilityIntegrationArchitectureTest {

    @Test
    void moduleOwnsOnlyExternalFiniteTaskAcceptance() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));

        assertTrue(build.contains(
                "project(':transport:worker-delivery-contract')"
        ));
        for (String forbidden : new String[]{
                "project(':server_jvm')",
                "project(':scenario_rpc_jvm')",
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
    void productionSourcesUseOnlyPublicFiniteTaskApis() throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/integration/workercapability"
        );
        StringBuilder sources = new StringBuilder();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path ->
                    path.toString().endsWith(".java")
            ).toList()) {
                sources.append(Files.readString(file));
            }
        }
        String text = sources.toString();
        assertTrue(text.contains("/api/v1/tasks"));
        for (String forbidden : new String[]{
                "/api/v1/worker-groups/",
                "WorkerResourceCatalog",
                "TaskRuntime",
                "WorkerRuntime",
                "ServerWorkerBundle",
                "ScenarioWorkers",
                "Redis",
                "ServiceLoader",
                "Class.forName",
                "workerId",
                "workerProperties"
        }) {
            assertFalse(text.contains(forbidden), forbidden);
        }
    }
}
