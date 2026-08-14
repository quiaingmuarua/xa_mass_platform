package com.xa.mass.workerdelivery.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class WorkerDeliveryContractBoundaryTest {

    @Test
    void contractHasNoServerOrRuntimeDependencies() throws IOException {
        Path sourceRoot = Path.of("src/main/java");
        StringBuilder source = new StringBuilder();
        String build = Files.readString(Path.of("build.gradle"));
        Set<String> sourceFiles;
        try (var paths = Files.walk(sourceRoot)) {
            sourceFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
        assertEquals(
                Set.of(
                        "Jsons.java",
                        "WorkerDeliveryCodec.java",
                        "WorkerDeliveryProtocol.java"
                ),
                sourceFiles
        );
        try (var paths = Files.walk(sourceRoot)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> append(source, path));
        }

        for (String forbidden : new String[]{
                "org.springframework",
                "io.lettuce",
                "redis",
                "jackson",
                "com.xa.mass.server",
                "kernel_design",
                "redisKeySuffix",
                " record ",
                " sealed "
        }) {
            assertFalse(
                    source.toString().toLowerCase().contains(
                            forbidden.toLowerCase()
                    ),
                    forbidden
            );
            assertFalse(
                    build.toLowerCase().contains(forbidden.toLowerCase()),
                    forbidden
            );
        }
        assertTrue(build.contains("gson:2.14.0"));
        assertTrue(build.contains("options.release = 11"));

        long gsonImports;
        try (var paths = Files.walk(sourceRoot)) {
            gsonImports = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(
                                    "import com.google.gson"
                            );
                        } catch (IOException error) {
                            throw new IllegalStateException(error);
                        }
                    })
                    .count();
        }
        assertEquals(1, gsonImports);
    }

    private static void append(StringBuilder target, Path path) {
        try {
            target.append(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException("Could not read " + path, error);
        }
    }
}
