package com.xa.mass.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkerArchitectureBoundaryTest {

    @Test
    void workerDependsOnlyOnTheSharedProtocolAndToolRuntime()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String source = readTree(project.resolve("src/main/java"));
        String build = Files.readString(project.resolve("build.gradle"));

        assertTrue(build.contains(
                "project(':worker_delivery_contract_jvm')"
        ));
        for (String forbiddenDependency : new String[]{
                "project(':server_jvm')",
                "project(':kernel_jvm')",
                "spring",
                "redis",
                "lettuce"
        }) {
            assertFalse(
                    build.toLowerCase().contains(
                            forbiddenDependency.toLowerCase()
                    ),
                    forbiddenDependency
            );
        }
        for (String forbidden : new String[]{
                "server_jvm",
                "kernel_jvm",
                "kernel_design",
                "springframework",
                "io.lettuce",
                "Redis",
                "TaskType",
                "commands:consume",
                "results:append"
        }) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    private static String readTree(Path root) throws IOException {
        StringBuilder source = new StringBuilder();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                source.append(Files.readString(path));
            }
        }
        return source.toString();
    }
}
