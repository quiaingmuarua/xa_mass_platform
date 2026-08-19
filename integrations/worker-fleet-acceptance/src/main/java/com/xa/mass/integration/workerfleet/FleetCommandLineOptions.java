package com.xa.mass.integration.workerfleet;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class FleetCommandLineOptions {

    private static final Set<String> ALLOWED = Set.of(
            "phase",
            "proof-id",
            "server-base-url",
            "fleet-spec",
            "scenario-worker-lab-root",
            "evidence-file",
            "baseline-file",
            "maximum-wait-millis",
            "request-timeout-millis"
    );

    private final Map<String, String> values;

    private FleetCommandLineOptions(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    static FleetCommandLineOptions parse(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : arguments) {
            if (argument == null || !argument.startsWith("--")) {
                throw new IllegalArgumentException(
                        "Arguments must use --name=value"
                );
            }
            int separator = argument.indexOf('=');
            if (separator <= 2 || separator == argument.length() - 1) {
                throw new IllegalArgumentException(
                        "Arguments must use --name=value"
                );
            }
            String name = argument.substring(2, separator);
            if (!ALLOWED.contains(name)) {
                throw new IllegalArgumentException(
                        "Unknown argument: --" + name
                );
            }
            String value = argument.substring(separator + 1);
            if (values.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException(
                        "Duplicate argument: --" + name
                );
            }
        }
        return new FleetCommandLineOptions(values);
    }

    Phase requiredPhase() {
        String value = required("phase");
        return switch (value) {
            case "initial" -> Phase.INITIAL;
            case "restart" -> Phase.RESTART;
            default -> throw new IllegalArgumentException(
                    "--phase must be initial or restart"
            );
        };
    }

    String proofId() {
        return Identifiers.require(required("proof-id"), "proofId");
    }

    URI serverBaseUrl() {
        URI value = URI.create(values.getOrDefault(
                "server-base-url",
                "http://127.0.0.1:18082"
        ));
        if (value.getHost() == null
                || (!"http".equalsIgnoreCase(value.getScheme())
                && !"https".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException(
                    "--server-base-url must be an absolute HTTP(S) URI"
            );
        }
        return value;
    }

    Path fleetSpec() {
        return absolutePath(values.getOrDefault(
                "fleet-spec",
                "fleet-spec.json"
        ));
    }

    Path scenarioWorkerLabRoot() {
        return absolutePath(values.getOrDefault(
                "scenario-worker-lab-root",
                "../../data/scenario-workers"
        ));
    }

    Path evidenceFile() {
        return absolutePath(required("evidence-file"));
    }

    Path baselineFile(Phase phase) {
        String value = values.get("baseline-file");
        if (phase == Phase.RESTART && value == null) {
            throw new IllegalArgumentException(
                    "--baseline-file is required for restart"
            );
        }
        if (phase == Phase.INITIAL && value != null) {
            throw new IllegalArgumentException(
                    "--baseline-file is accepted only for restart"
            );
        }
        return value == null ? null : absolutePath(value);
    }

    long maximumWaitMillis() {
        return boundedPositiveLong(
                "maximum-wait-millis",
                30_000L,
                300_000L
        );
    }

    long requestTimeoutMillis() {
        return boundedPositiveLong(
                "request-timeout-millis",
                120_000L,
                300_000L
        );
    }

    private String required(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "--" + name + " is required"
            );
        }
        return value;
    }

    private long boundedPositiveLong(
            String name,
            long defaultValue,
            long maximum
    ) {
        String value = values.get(name);
        long parsed = value == null
                ? defaultValue
                : Long.parseLong(value);
        if (parsed <= 0 || parsed > maximum) {
            throw new IllegalArgumentException(
                    "--" + name + " must be in 1.." + maximum
            );
        }
        return parsed;
    }

    private static Path absolutePath(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }

    enum Phase {
        INITIAL("initial"),
        RESTART("restart");

        private final String wireValue;

        Phase(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }
}
