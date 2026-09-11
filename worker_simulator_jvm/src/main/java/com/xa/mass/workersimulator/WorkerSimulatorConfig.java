package com.xa.mass.workersimulator;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One complete captured configuration after resolving omitted input fields; no runtime reload. */
record WorkerSimulatorConfig(
        URI runtimeApiBaseUrl,
        Path sandboxRoot,
        int controlPort,
        List<WorkerSimulatorGroupConfig> workerGroups,
        WorkerSimulatorStartupPlan startupPlan
) {
    WorkerSimulatorConfig {
        if (runtimeApiBaseUrl == null || !runtimeApiBaseUrl.isAbsolute()
                || runtimeApiBaseUrl.getHost() == null
                || runtimeApiBaseUrl.getQuery() != null || runtimeApiBaseUrl.getFragment() != null
                || !("http".equalsIgnoreCase(runtimeApiBaseUrl.getScheme())
                || "https".equalsIgnoreCase(runtimeApiBaseUrl.getScheme()))) {
            throw new IllegalArgumentException("runtimeApiBaseUrl must be an absolute HTTP(S) URI");
        }
        sandboxRoot = Objects.requireNonNull(sandboxRoot, "sandboxRoot").toAbsolutePath().normalize();
        if (controlPort < 0 || controlPort > 65535) {
            throw new IllegalArgumentException("controlPort must be between 0 and 65535");
        }
        workerGroups = List.copyOf(workerGroups);
        Objects.requireNonNull(startupPlan, "startupPlan");
    }

    static WorkerSimulatorConfig load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return WorkerSimulatorJsonParser.parse(Files.readString(absolute), absolute.getParent());
    }
}
