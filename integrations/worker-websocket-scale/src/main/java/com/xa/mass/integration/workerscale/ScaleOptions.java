package com.xa.mass.integration.workerscale;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record ScaleOptions(
        Phase phase,
        String proofId,
        URI serverBaseUri,
        URI labBaseUri,
        String workerGroupId,
        String endpointManagerId,
        int offeredWorkers,
        int minimumConverged,
        int workloadItemsPerTask,
        Duration maximumConvergenceWait,
        Duration stableHold,
        Duration scanInterval,
        Duration taskResultWait,
        Duration requestTimeout,
        Path baselineFile,
        Path summaryFile,
        Path timelineFile
) {

    private static final Set<String> FIELDS = Set.of(
            "phase",
            "proof-id",
            "server-base-url",
            "lab-base-url",
            "worker-group-id",
            "endpoint-manager-id",
            "offered-workers",
            "minimum-converged",
            "workload-items-per-task",
            "maximum-convergence-wait-millis",
            "stable-hold-millis",
            "scan-interval-millis",
            "task-result-wait-millis",
            "request-timeout-millis",
            "baseline-file",
            "summary-file",
            "timeline-file"
    );

    enum Phase {
        INITIAL,
        RECONNECTED;

        static Phase parse(String value) {
            return switch (requireText(value, "phase")) {
                case "initial" -> INITIAL;
                case "reconnected" -> RECONNECTED;
                default -> throw new IllegalArgumentException(
                        "phase must be initial or reconnected"
                );
            };
        }

        String wireValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    ScaleOptions {
        if (offeredWorkers < 1 || offeredWorkers > 10_000) {
            throw new IllegalArgumentException(
                    "offeredWorkers must be in 1..10000"
            );
        }
        if (minimumConverged < 1 || minimumConverged > offeredWorkers) {
            throw new IllegalArgumentException(
                    "minimumConverged must be in 1..offeredWorkers"
            );
        }
        if (workloadItemsPerTask < 1 || workloadItemsPerTask > 500) {
            throw new IllegalArgumentException(
                    "workloadItemsPerTask must be in 1..500"
            );
        }
        requirePositive(maximumConvergenceWait, "maximumConvergenceWait");
        if (stableHold == null || stableHold.isNegative()) {
            throw new IllegalArgumentException(
                    "stableHold must not be negative"
            );
        }
        requirePositive(scanInterval, "scanInterval");
        requirePositive(taskResultWait, "taskResultWait");
        requirePositive(requestTimeout, "requestTimeout");
        baselineFile = absolute(baselineFile, "baselineFile");
        summaryFile = absolute(summaryFile, "summaryFile");
        timelineFile = absolute(timelineFile, "timelineFile");
    }

    static ScaleOptions parse(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            if (argument == null || !argument.startsWith("--")) {
                throw new IllegalArgumentException(
                        "Arguments must use --name=value"
                );
            }
            int separator = argument.indexOf('=');
            if (separator < 3 || separator == argument.length() - 1) {
                throw new IllegalArgumentException(
                        "Arguments must use --name=value"
                );
            }
            String name = argument.substring(2, separator);
            if (!FIELDS.contains(name)) {
                throw new IllegalArgumentException(
                        "Unknown scale option: " + name
                );
            }
            if (values.putIfAbsent(
                    name,
                    argument.substring(separator + 1)
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate scale option: " + name
                );
            }
        }
        Phase phase = Phase.parse(required(values, "phase"));
        return new ScaleOptions(
                phase,
                text(values, "proof-id", "worker-websocket-scale"),
                uri(values, "server-base-url", "http://127.0.0.1:18082"),
                uri(values, "lab-base-url", "http://127.0.0.1:18086"),
                text(
                        values,
                        "worker-group-id",
                        "scenario-string-utils-workers"
                ),
                text(values, "endpoint-manager-id", "scenario-websocket"),
                integer(values, "offered-workers", 10_000),
                integer(values, "minimum-converged", 9_900),
                integer(values, "workload-items-per-task", 500),
                millis(values, "maximum-convergence-wait-millis", 900_000),
                millis(
                        values,
                        "stable-hold-millis",
                        phase == Phase.INITIAL ? 60_000 : 0
                ),
                millis(values, "scan-interval-millis", 10_000),
                millis(values, "task-result-wait-millis", 300_000),
                millis(values, "request-timeout-millis", 30_000),
                path(values, "baseline-file"),
                path(values, "summary-file"),
                path(values, "timeline-file")
        );
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing scale option: " + name);
        }
        return requireText(value, name);
    }

    private static String text(
            Map<String, String> values,
            String name,
            String fallback
    ) {
        return requireText(values.getOrDefault(name, fallback), name);
    }

    private static int integer(
            Map<String, String> values,
            String name,
            int fallback
    ) {
        String raw = values.get(name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    private static Duration millis(
            Map<String, String> values,
            String name,
            long fallback
    ) {
        String raw = values.get(name);
        long parsed = fallback;
        if (raw != null) {
            try {
                parsed = Long.parseLong(raw);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        name + " must be an integer",
                        error
                );
            }
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return Duration.ofMillis(parsed);
    }

    private static URI uri(
            Map<String, String> values,
            String name,
            String fallback
    ) {
        try {
            return URI.create(text(values, name, fallback));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(name + " must be a URI", error);
        }
    }

    private static Path path(Map<String, String> values, String name) {
        try {
            return Path.of(required(values, name));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " must be a path", error);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Path absolute(Path value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        return value.toAbsolutePath().normalize();
    }
}
