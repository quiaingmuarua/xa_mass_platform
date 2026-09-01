package com.xa.mass.integration.androidworkerproof;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class AndroidWorkerProofOptions {

    private static final Set<String> ALLOWED = Set.of(
            "phase",
            "proof-id",
            "server-base-url",
            "device-base-url",
            "endpoint-manager-id",
            "evidence-file",
            "baseline-file",
            "maximum-wait-millis",
            "request-timeout-millis",
            "android-api-level"
    );

    private final Map<String, String> values;
    private final long phaseDeadlineNanos;

    private AndroidWorkerProofOptions(Map<String, String> values) {
        this.values = Map.copyOf(values);
        phaseDeadlineNanos = System.nanoTime()
                + Duration.ofMillis(boundedLong(
                        "maximum-wait-millis",
                        60_000L,
                        300_000L
                )).toNanos();
    }

    static AndroidWorkerProofOptions parse(String[] arguments) {
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
            if (values.putIfAbsent(
                    name,
                    argument.substring(separator + 1)
            ) != null) {
                throw new IllegalArgumentException(
                        "Duplicate argument: --" + name
                );
            }
        }
        AndroidWorkerProofOptions options = new AndroidWorkerProofOptions(values);
        if (options.requestTimeout().compareTo(options.maximumWait()) > 0) {
            throw new IllegalArgumentException(
                    "--request-timeout-millis must not exceed "
                            + "--maximum-wait-millis"
            );
        }
        return options;
    }

    String phase() {
        return required("phase");
    }

    String proofId() {
        return required("proof-id");
    }

    URI serverBaseUrl() {
        return httpUri(values.getOrDefault(
                "server-base-url",
                "http://127.0.0.1:18082"
        ), "server-base-url");
    }

    URI deviceBaseUrl() {
        return httpUri(values.getOrDefault(
                "device-base-url",
                "http://127.0.0.1:18084"
        ), "device-base-url");
    }

    String endpointManagerId() {
        return values.getOrDefault(
                "endpoint-manager-id",
                AndroidWorkerProofConstants.DEFAULT_ENDPOINT_MANAGER_ID
        );
    }

    Path evidenceFile() {
        return absolutePath(required("evidence-file"));
    }

    Path baselineFile(boolean required) {
        String value = values.get("baseline-file");
        if (required && value == null) {
            throw new IllegalArgumentException(
                    "--baseline-file is required for this phase"
            );
        }
        if (!required && value != null) {
            throw new IllegalArgumentException(
                    "--baseline-file is not accepted for this phase"
            );
        }
        return value == null ? null : absolutePath(value);
    }

    Duration maximumWait() {
        return Duration.ofMillis(boundedLong(
                "maximum-wait-millis",
                60_000L,
                300_000L
        ));
    }

    Duration requestTimeout() {
        return Duration.ofMillis(boundedLong(
                "request-timeout-millis",
                5_000L,
                300_000L
        ));
    }

    int androidApiLevel() {
        String value = values.getOrDefault("android-api-level", "33");
        int parsed = Integer.parseInt(value);
        if (parsed < 24 || parsed > 100) {
            throw new IllegalArgumentException(
                    "--android-api-level must be in 24..100"
            );
        }
        return parsed;
    }

    long phaseDeadlineNanos() {
        return phaseDeadlineNanos;
    }

    private long boundedLong(String name, long defaultValue, long maximum) {
        long parsed = Long.parseLong(values.getOrDefault(
                name,
                Long.toString(defaultValue)
        ));
        if (parsed <= 0L || parsed > maximum) {
            throw new IllegalArgumentException(
                    "--" + name + " must be in 1.." + maximum
            );
        }
        return parsed;
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

    private static URI httpUri(String value, String name) {
        URI parsed = URI.create(value);
        if (parsed.getHost() == null
                || (!"http".equalsIgnoreCase(parsed.getScheme())
                && !"https".equalsIgnoreCase(parsed.getScheme()))) {
            throw new IllegalArgumentException(
                    "--" + name + " must be an absolute HTTP(S) URI"
            );
        }
        return parsed;
    }

    private static Path absolutePath(String value) {
        return Path.of(value).toAbsolutePath().normalize();
    }
}
