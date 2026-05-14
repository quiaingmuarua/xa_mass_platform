package com.xa.mass.trace.query;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class TraceSourceResolver {

    private TraceSourceResolver() {
    }

    public static TraceSource resolve(String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("--path is required");
        }
        Path input = Path.of(rawPath).toAbsolutePath().normalize();
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Trace path does not exist: " + input);
        }
        if (Files.isDirectory(input)) {
            List<Path> files;
            try (var paths = Files.list(input)) {
                files = paths
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl"))
                        .sorted()
                        .toList();
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException("No .jsonl trace files found under: " + input);
            }
            String glob = normalizeForDuckDb(input) + File.separatorChar + "*.jsonl";
            return new TraceSource(input, glob.replace('\\', '/'), files);
        }
        if (!input.getFileName().toString().endsWith(".jsonl")) {
            throw new IllegalArgumentException("Trace file must end with .jsonl: " + input);
        }
        return new TraceSource(input, normalizeForDuckDb(input), List.of(input));
    }

    private static String normalizeForDuckDb(Path path) {
        return path.toString().replace('\\', '/');
    }
}
