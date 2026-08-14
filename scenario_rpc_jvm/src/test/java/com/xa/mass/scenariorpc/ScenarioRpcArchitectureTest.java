package com.xa.mass.scenariorpc;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScenarioRpcArchitectureTest {

    @Test
    void moduleRemainsAJavaOnlyInMemoryProcess() throws Exception {
        String build = Files.readString(Path.of("build.gradle"));
        StringBuilder sources = new StringBuilder();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                sources.append(Files.readString(file));
            }
        }
        String all = build + sources;
        for (String forbidden : new String[]{
                "project('",
                "org.springframework",
                "com.xa.mass.server",
                "com.xa.mass.kernel",
                "io.lettuce",
                "java.nio.file",
                "java.net.http",
                "java.net.URI",
                "ServiceLoader",
                "Class.forName"
        }) {
            assertFalse(all.contains(forbidden), forbidden);
        }
    }

    @Test
    void onlyServerConsumesThisModule() throws Exception {
        Path repository = Path.of("..");
        List<String> consumers = new ArrayList<>();
        try (var files = Files.walk(repository, 3)) {
            for (Path file : files
                    .filter(path -> path.getFileName().toString()
                            .equals("build.gradle"))
                    .toList()) {
                if (Files.readString(file).contains(
                        "project(':scenario_rpc_jvm')"
                )) {
                    consumers.add(file.getParent().getFileName().toString());
                }
            }
        }
        assertFalse(consumers.isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("server_jvm"),
                consumers
        );
    }
}
