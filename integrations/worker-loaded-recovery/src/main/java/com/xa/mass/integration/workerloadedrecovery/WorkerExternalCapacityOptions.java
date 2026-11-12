package com.xa.mass.integration.workerloadedrecovery;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

record WorkerExternalCapacityOptions(
        URI serverBaseUri, URI labBaseUri, String projectId, String workerGroupId,
        String endpointManagerId, Path accessKeyFile, Path outputDirectory,
        int preparedWorkers, int retainedWorkers, int minimumInitialConnected,
        int minimumRetainedConnected, int workloadItemsPerTask, Duration stableHold,
        Duration maximumConvergenceWait, Duration taskResultWait, Duration requestTimeout,
        Duration scanInterval
) {
    private static final Set<String> NAMES = Set.of(
            "server-base-url", "lab-base-url", "project-id", "worker-group-id",
            "endpoint-manager-id", "access-key-file", "output-dir", "prepared-workers",
            "retained-workers", "minimum-initial-connected", "minimum-retained-connected",
            "workload-items-per-task", "stable-hold-millis", "maximum-convergence-wait-millis",
            "task-result-wait-millis", "request-timeout-millis", "scan-interval-millis");

    WorkerExternalCapacityOptions {
        requireOrigin(serverBaseUri, false);
        requireOrigin(labBaseUri, true);
        for (String id : new String[]{projectId, workerGroupId, endpointManagerId}) {
            if (id == null || id.isBlank() || id.length() > 200 || id.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("an explicit non-blank identity is required");
            }
        }
        if (accessKeyFile == null || outputDirectory == null) {
            throw new IllegalArgumentException("access-key-file and output-dir are required");
        }
        if (preparedWorkers < 2 || preparedWorkers > 15_000 || retainedWorkers < 1
                || retainedWorkers >= preparedWorkers || minimumInitialConnected < 1
                || minimumInitialConnected > preparedWorkers || minimumRetainedConnected < 1
                || minimumRetainedConnected > retainedWorkers || workloadItemsPerTask < 1
                || workloadItemsPerTask > 5_000) {
            throw new IllegalArgumentException("external capacity counts are out of bounds");
        }
        for (Duration duration : new Duration[]{stableHold, maximumConvergenceWait,
                taskResultWait, requestTimeout, scanInterval}) {
            if (duration == null || duration.isNegative() || duration.isZero()
                    || duration.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("durations must be positive and at most one hour");
            }
        }
        if (stableHold.compareTo(maximumConvergenceWait) >= 0) {
            throw new IllegalArgumentException("stable hold must be shorter than convergence deadline");
        }
    }

    static WorkerExternalCapacityOptions parse(String[] arguments) {
        Map<String, String> values = new HashMap<>();
        for (String argument : arguments) {
            int separator = argument.indexOf('=');
            if (!argument.startsWith("--") || separator < 3) {
                throw new IllegalArgumentException("arguments must use --name=value");
            }
            String name = argument.substring(2, separator);
            if (!NAMES.contains(name) || values.putIfAbsent(name, argument.substring(separator + 1)) != null) {
                throw new IllegalArgumentException("unknown or repeated option");
            }
        }
        try {
            return new WorkerExternalCapacityOptions(
                    URI.create(required(values, "server-base-url")), URI.create(required(values, "lab-base-url")),
                    required(values, "project-id"), required(values, "worker-group-id"),
                    required(values, "endpoint-manager-id"), Path.of(required(values, "access-key-file")),
                    Path.of(required(values, "output-dir")), number(values, "prepared-workers", 15_000),
                    number(values, "retained-workers", 10_000), number(values, "minimum-initial-connected", 14_800),
                    number(values, "minimum-retained-connected", 9_900), number(values, "workload-items-per-task", 5_000),
                    millis(values, "stable-hold-millis", 60_000), millis(values, "maximum-convergence-wait-millis", 900_000),
                    millis(values, "task-result-wait-millis", 900_000), millis(values, "request-timeout-millis", 30_000),
                    millis(values, "scan-interval-millis", 10_000));
        } catch (IllegalArgumentException error) {
            // URI and number parser exceptions can contain supplied values.
            throw new IllegalArgumentException("external capacity configuration is invalid");
        }
    }

    String readAccessKey() throws IOException {
        if (!Files.isRegularFile(accessKeyFile) || Files.size(accessKeyFile) > 512) {
            throw new IllegalArgumentException("access-key-file must be a small regular file");
        }
        String key = Files.readString(accessKeyFile, StandardCharsets.UTF_8).strip();
        if (!key.matches("xak_[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalArgumentException("access-key-file contains an invalid access key");
        }
        return key;
    }

    static void requireOrigin(URI uri, boolean lab) {
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || uri.getPort() == 0 || uri.getPort() > 65535
                || !(lab ? "http".equalsIgnoreCase(uri.getScheme())
                        && Set.of("127.0.0.1", "localhost", "[::1]", "::1").contains(uri.getHost())
                        : "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(lab ? "Lab requires a loopback HTTP origin"
                    : "Runtime requires an explicit HTTPS origin");
        }
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required option");
        return value;
    }

    private static int number(Map<String, String> values, String name, int fallback) {
        return values.containsKey(name) ? Integer.parseInt(values.get(name)) : fallback;
    }

    private static Duration millis(Map<String, String> values, String name, int fallback) {
        return Duration.ofMillis(number(values, name, fallback));
    }
}
