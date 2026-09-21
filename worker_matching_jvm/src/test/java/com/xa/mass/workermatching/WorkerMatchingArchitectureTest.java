package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerMatchingArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");

    @Test void resourceAndFunctionDependenciesStaySeparate() throws IOException {
        assertPackageDependencies("pool", List.of("io.lettuce", ".index.", ".storage.", "com.xa.mass.workermatching.buckets.", "WorkerFacts",
                ".functions.", ".refill.", "executorName", "QueryFunction", "PoolRefillPolicy"));
        assertPackageDependencies("index", List.of(".pool.", ".refill.", ".functions.",
                "executorName", "QueryFunction", "PoolRefillPolicy", "CandidateBudget"));
        assertPackageDependencies("buckets", List.of("io.lettuce", ".index.", ".storage.", ".functions.", ".refill.", "redis.call"));
        assertPackageDependencies("storage", List.of(".pool.", ".refill.", ".functions.",
                "executorName", "CandidateBudget", "WorkerCandidatePool"));
        assertPackageDependencies("functions", List.of(".refill.", ".storage.", "io.lettuce",
                "PoolRefillPolicy", "PoolMaintenance", "new WorkerCandidatePool", "new PhoneIndex", "new Thread", ".offerBatch(", ".discardExpired("));
        assertPackageDependencies("refill", List.of("io.lettuce", "IndexMutation", "prepareLua",
                ".index.", ".storage.", "redis.call", ":matching:"));
    }

    @Test void catalogCoordinatesCapabilitiesWithoutBusinessNamesOrRedisCommands() throws IOException {
        String source = Files.readString(SOURCE.resolve("com/xa/mass/workermatching/RedisWorkerMatchingCatalog.java"));
        for (String forbidden : List.of("\"country\"", "\"messaging\"", "\"proof-facts\"", "\"workerId\".equals",
                "io.lettuce", ".commands()", "redis.call")) assertFalse(source.contains(forbidden), forbidden);
    }

    private void assertPackageDependencies(String name, List<String> forbidden) throws IOException {
        try (var files=Files.walk(SOURCE.resolve("com/xa/mass/workermatching").resolve(name))) {
            for (Path file:files.filter(path->path.toString().endsWith(".java")).toList()) {
                String source=Files.readString(file);
                for(String dependency:forbidden)
                    assertFalse(source.contains(dependency),file+" must not depend on "+dependency);
            }
        }
    }

    @Test
    void matchingDoesNotOwnSchedulingOrPlatformMechanisms()
            throws IOException {
        StringBuilder source = new StringBuilder();
        try (var files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString()
                    .endsWith(".java")).toList()) {
                source.append(Files.readString(file));
            }
        }

        for (String forbidden : List.of(
                "com.xa.mass.kernel.score",
                "TaskRuntime",
                "TaskResourceCatalog",
                "taskId",
                ":matching:task:",
                "WorkerResourceCatalog",
                "com.xa.mass.kernel.pacer",
                "com.xa.mass.server",
                "com.xa.mass.workerdelivery",
                "io.netty",
                "org.springframework",
                "DeliveryCommand",
                "DeliveryReport",
                "scanWorkerFacts",
                "WorkerFactsPage",
                "itemCursors",
                "ItemRule",
                "ArrayBlockingQueue"
        )) {
            assertFalse(
                    source.toString().contains(forbidden),
                    () -> "Worker Matching must not contain " + forbidden
            );
        }
        assertFalse(
                source.toString().contains("WorkerScoreCore"),
                "Worker Matching may carry opaque held scores but must not "
                        + "interpret them"
        );
    }
}
