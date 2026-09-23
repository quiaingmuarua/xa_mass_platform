package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class AndroidWorkerProofArchitectureTest {

    @Test
    void dependsOnlyOnPublicHttpAndDeliveryJson() throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = Files.readString(project.resolve("build.gradle"));
        String source;
        try (var files = Files.walk(project.resolve("src/main/java"))) {
            source = files.filter(Files::isRegularFile)
                    .map(AndroidWorkerProofArchitectureTest::read)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        assertTrue(build.contains(
                "project(':transport:worker-delivery-contract')"
        ));
        for (String forbidden : new String[]{
                "project(':transport:android-worker')",
                "project(':server_jvm')",
                "project(':kernel_jvm')",
                "project(':transport:netty-adapter')",
                "com.xa.mass.worker.android",
                "com.xa.mass.server",
                "com.xa.mass.kernel",
                "com.xa.mass.workerdelivery.adapter"
        }) {
            assertFalse(build.contains(forbidden), forbidden);
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read " + path, error);
        }
    }
}
