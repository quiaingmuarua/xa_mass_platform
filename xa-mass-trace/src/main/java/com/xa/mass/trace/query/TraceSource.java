package com.xa.mass.trace.query;

import java.nio.file.Path;
import java.util.List;

public record TraceSource(
        Path inputPath,
        String duckDbPattern,
        List<Path> files
) {
}
