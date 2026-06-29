package com.xa.mass.sdk.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

final class EngineCallerSurfaceGuardSupport {

    private EngineCallerSurfaceGuardSupport() {
    }

    static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path pom = current.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String source = Files.readString(pom, StandardCharsets.UTF_8);
                    if (source.contains("<artifactId>xa_mass_platform</artifactId>")
                            && source.contains("<module>xa-mass-engine</module>")) {
                        return current;
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read " + pom, e);
                }
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root");
    }

    static String read(String relativePath) {
        Path path = repositoryRoot().resolve(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    static List<Path> javaSourceFiles(String relativeRoot) {
        Path root = repositoryRoot().resolve(relativeRoot);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan " + root, e);
        }
    }

    static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
