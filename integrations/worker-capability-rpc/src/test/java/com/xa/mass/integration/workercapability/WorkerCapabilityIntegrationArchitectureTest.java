package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerCapabilityIntegrationArchitectureTest {

    @Test
    void moduleOwnsOnlyTheExternalTaskBatchAcceptance()
            throws Exception {
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
                "implementation 'com.googlecode.libphonenumber",
                "springframework",
                "io.lettuce"
        }) {
            assertFalse(build.contains(forbidden), forbidden);
        }
    }

    @Test
    void productionSourcesDoNotHostWorkerAssembly()
            throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/integration"
                        + "/workercapability"
        );
        StringBuilder productionSources = new StringBuilder();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path ->
                    path.toString().endsWith(".java")
            ).toList()) {
                productionSources.append(Files.readString(file));
            }
        }
        for (String forbidden : new String[]{
                "WorkerResourceCatalog",
                "WorkerRuntime",
                "ServerWorkerBundle",
                "ScenarioWorkers",
                "Redis",
                "RpcProcess",
                "WorkerGroupRpcClient",
                "/api/v1/worker-groups/"
        }) {
            assertFalse(
                    productionSources.toString().contains(forbidden),
                    forbidden
            );
        }
        assertFalse(Files.exists(
                root.resolve("PhoneNumberWorkerMain.java")
        ));
    }

    @Test
    void integrationOnlyUsesTheServerTaskBatchApi()
            throws Exception {
        Path root = Path.of(
                "src/main/java/com/xa/mass/integration"
                        + "/workercapability"
        );
        StringBuilder sources = new StringBuilder();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path ->
                    path.toString().endsWith(".java")
            ).toList()) {
                sources.append(Files.readString(file));
            }
        }
        assertTrue(sources.toString().contains(
                "/api/v1/task-batches/"
        ));
        for (String forbidden : new String[]{
                "/api/v1/scenario-rpc/",
                "ServiceLoader",
                "Class.forName",
                "startRequests",
                "followRequest",
                "Checkpoint",
                "newFixedThreadPool",
                "newVirtualThreadPerTaskExecutor"
        }) {
            assertFalse(
                    sources.toString().contains(forbidden),
                    forbidden
            );
        }
        if (Files.exists(root.resolve("process"))) {
            try (var files = Files.walk(root.resolve("process"))) {
                assertFalse(files.anyMatch(path ->
                        path.toString().endsWith(".java")
                ));
            }
        }
    }
}
