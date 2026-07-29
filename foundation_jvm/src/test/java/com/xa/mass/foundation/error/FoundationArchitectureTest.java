package com.xa.mass.foundation.error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FoundationArchitectureTest {

    @Test
    void foundationIsAJavaElevenLibraryWithoutRuntimeDependencies()
            throws IOException {
        Path project = Path.of("").toAbsolutePath();
        String build = Files.readString(project.resolve("build.gradle"));
        String source = readTree(project.resolve("src/main/java"));

        assertTrue(build.contains("id 'java-library'"));
        assertTrue(build.contains(
                "archivesName.set('xa-mass-foundation-jvm')"
        ));
        assertTrue(build.contains("options.release = 11"));
        assertFalse(build.contains("implementation "));

        for (String forbidden : new String[]{
                "spring",
                "slf4j",
                "netty",
                "okhttp",
                "redis",
                "lettuce",
                "workerdelivery",
                "HttpStatus",
                "Logger"
        }) {
            assertFalse(
                    source.toLowerCase().contains(forbidden.toLowerCase()),
                    forbidden
            );
        }
    }

    private static String readTree(Path root) throws IOException {
        StringBuilder source = new StringBuilder();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> append(source, path));
        }
        return source.toString();
    }

    private static void append(StringBuilder target, Path path) {
        try {
            target.append(Files.readString(path));
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Unable to read " + path,
                    error
            );
        }
    }
}
