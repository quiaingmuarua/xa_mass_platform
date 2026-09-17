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
        Path rules=SOURCE.resolve("com/xa/mass/workermatching/rules");
        for(String file:List.of("CandidatePool.java","MatchingStorage.java")) {
            String source=Files.readString(rules.resolve(file));
            for(String forbidden:List.of("executorName","QueryFunctions","PoolRefillPolicy"))
                assertFalse(source.contains(forbidden),file+" must not depend on "+forbidden);
        }
        String consumers=Files.readString(rules.resolve("PoolQueryFunctions.java"));
        for(String forbidden:List.of("PoolRefillPolicy","PoolMaintenance","PartitionedPoolPolicy","new CandidatePool","new Thread"))
            assertFalse(consumers.contains(forbidden),"Consumer functions only receive existing resources: "+forbidden);
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
