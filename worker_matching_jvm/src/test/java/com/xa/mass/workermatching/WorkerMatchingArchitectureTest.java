package com.xa.mass.workermatching;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerMatchingArchitectureTest {

    private static final Path SOURCE = Path.of("src/main/java");

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
                "taskId",
                ":matching:task:",
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
