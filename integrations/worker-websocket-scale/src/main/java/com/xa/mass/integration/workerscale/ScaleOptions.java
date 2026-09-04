package com.xa.mass.integration.workerscale;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

record ScaleOptions(
        Stage stage,
        String proofId,
        URI serverBaseUri,
        URI labBaseUri,
        String workerGroupId,
        String endpointManagerId,
        int preparedWorkers,
        int retainedWorkers,
        int minimumInitialConverged,
        int minimumRetainedConverged,
        int workloadItemsPerTask,
        Duration maximumConvergenceWait,
        Duration stableHold,
        Duration scanInterval,
        Duration taskResultWait,
        Duration requestTimeout,
        Path topologyFile,
        Path baselineFile,
        Path gateDirectory,
        Path summaryFile,
        Path timelineFile
) {

    private static final Set<String> FIELDS = Set.of(
            "stage",
            "proof-id",
            "server-base-url",
            "lab-base-url",
            "worker-group-id",
            "endpoint-manager-id",
            "prepared-workers",
            "retained-workers",
            "minimum-initial-converged",
            "minimum-retained-converged",
            "workload-items-per-task",
            "maximum-convergence-wait-millis",
            "stable-hold-millis",
            "scan-interval-millis",
            "task-result-wait-millis",
            "request-timeout-millis",
            "topology-file",
            "baseline-file",
            "gate-directory",
            "summary-file",
            "timeline-file"
    );

    enum Stage {
        INITIAL_CONTRACTION("initial-contraction"),
        GRACEFUL_RESTART("graceful-restart"),
        HARD_RESTART_1("hard-restart-1"),
        HARD_RESTART_2("hard-restart-2");

        private final String wireValue;

        Stage(String wireValue) {
            this.wireValue = wireValue;
        }

        static Stage parse(String value) {
            return switch (requireText(value, "stage")) {
                case "initial-contraction" -> INITIAL_CONTRACTION;
                case "graceful-restart" -> GRACEFUL_RESTART;
                case "hard-restart-1" -> HARD_RESTART_1;
                case "hard-restart-2" -> HARD_RESTART_2;
                default -> throw new IllegalArgumentException(
                        "stage must be initial-contraction, graceful-restart, "
                                + "hard-restart-1 or hard-restart-2"
                );
            };
        }

        String wireValue() {
            return wireValue;
        }

        boolean isInitialContraction() {
            return this == INITIAL_CONTRACTION;
        }

        boolean isHardRestart() {
            return this == HARD_RESTART_1 || this == HARD_RESTART_2;
        }
    }

    ScaleOptions {
        java.util.Objects.requireNonNull(stage, "stage");
        if (preparedWorkers < 1 || preparedWorkers > 15_000) {
            throw new IllegalArgumentException(
                    "preparedWorkers must be in 1..15000"
            );
        }
        if (retainedWorkers < 1 || retainedWorkers >= preparedWorkers) {
            throw new IllegalArgumentException(
                    "retainedWorkers must be in 1..preparedWorkers-1"
            );
        }
        if (minimumInitialConverged < 1
                || minimumInitialConverged > preparedWorkers) {
            throw new IllegalArgumentException(
                    "minimumInitialConverged must be in 1..preparedWorkers"
            );
        }
        if (minimumRetainedConverged < 1
                || minimumRetainedConverged > retainedWorkers) {
            throw new IllegalArgumentException(
                    "minimumRetainedConverged must be in 1..retainedWorkers"
            );
        }
        if (workloadItemsPerTask < 1 || workloadItemsPerTask > 5_000) {
            throw new IllegalArgumentException(
                    "workloadItemsPerTask must be in 1..5000"
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
        topologyFile = absolute(topologyFile, "topologyFile");
        baselineFile = absolute(baselineFile, "baselineFile");
        gateDirectory = absolute(gateDirectory, "gateDirectory");
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
        Stage stage = Stage.parse(required(values, "stage"));
        return new ScaleOptions(
                stage,
                text(values, "proof-id", "worker-websocket-scale"),
                uri(values, "server-base-url", "http://127.0.0.1:18082"),
                uri(values, "lab-base-url", "http://127.0.0.1:18086"),
                text(
                        values,
                        "worker-group-id",
                        "scenario-string-utils-workers"
                ),
                text(values, "endpoint-manager-id", "scenario-websocket"),
                integer(values, "prepared-workers", 15_000),
                integer(values, "retained-workers", 10_000),
                integer(values, "minimum-initial-converged", 14_800),
                integer(values, "minimum-retained-converged", 9_900),
                integer(values, "workload-items-per-task", 5_000),
                millis(values, "maximum-convergence-wait-millis", 900_000),
                millis(
                        values,
                        "stable-hold-millis",
                        stage.isInitialContraction() ? 60_000 : 0
                ),
                millis(values, "scan-interval-millis", 10_000),
                millis(values, "task-result-wait-millis", 900_000),
                millis(values, "request-timeout-millis", 30_000),
                path(values, "topology-file"),
                path(values, "baseline-file"),
                path(values, "gate-directory"),
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
