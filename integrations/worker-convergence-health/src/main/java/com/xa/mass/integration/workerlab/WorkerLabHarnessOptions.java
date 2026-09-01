package com.xa.mass.integration.workerlab;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

record WorkerLabHarnessOptions(
        URI runtimeApiBaseUrl,
        URI labControlBaseUrl,
        String endpointManagerId,
        String proofId,
        Path evidenceDirectory,
        long maximumWaitMillis,
        long requestTimeoutMillis
) {

    static final Set<String> ARGUMENT_NAMES = Set.of(
            "runtime-api-base-url",
            "lab-control-base-url",
            "endpoint-manager-id",
            "proof-id",
            "evidence-dir",
            "maximum-wait-millis",
            "request-timeout-millis"
    );

    private static final URI DEFAULT_RUNTIME_API =
            URI.create("http://127.0.0.1:18082");
    private static final URI DEFAULT_LAB_CONTROL =
            URI.create("http://127.0.0.1:18086");

    WorkerLabHarnessOptions {
        requireHttp(runtimeApiBaseUrl, "runtime-api-base-url");
        requireHttp(labControlBaseUrl, "lab-control-base-url");
        requireNonBlank(endpointManagerId, "endpoint-manager-id");
        requireNonBlank(proofId, "proof-id");
        java.util.Objects.requireNonNull(
                evidenceDirectory,
                "evidenceDirectory"
        );
        requireRange(
                maximumWaitMillis,
                1_000,
                300_000,
                "maximum-wait-millis"
        );
        requireRange(
                requestTimeoutMillis,
                100,
                60_000,
                "request-timeout-millis"
        );
    }

    static WorkerLabHarnessOptions from(
            WorkerLabArguments arguments,
            String defaultProofId
    ) {
        String proofId = arguments.value("proof-id", defaultProofId);
        return new WorkerLabHarnessOptions(
                URI.create(arguments.value(
                        "runtime-api-base-url",
                        DEFAULT_RUNTIME_API.toString()
                )),
                URI.create(arguments.value(
                        "lab-control-base-url",
                        DEFAULT_LAB_CONTROL.toString()
                )),
                arguments.value(
                        "endpoint-manager-id",
                        "scenario-websocket"
                ),
                proofId,
                Path.of(arguments.value(
                        "evidence-dir",
                        "build/worker-convergence-health/" + proofId
                )),
                arguments.number("maximum-wait-millis", 120_000),
                arguments.number("request-timeout-millis", 10_000)
        );
    }

    Duration requestTimeout() {
        return Duration.ofMillis(requestTimeoutMillis);
    }

    Duration maximumWait() {
        return Duration.ofMillis(maximumWaitMillis);
    }

    WorkerLabControlClient labClient() {
        return new WorkerLabControlClient(new JsonHttpClient(
                labControlBaseUrl,
                requestTimeout()
        ));
    }

    RuntimeApiClient runtimeClient() {
        return new RuntimeApiClient(new JsonHttpClient(
                runtimeApiBaseUrl,
                requestTimeout()
        ));
    }

    private static void requireHttp(URI value, String name) {
        if (value == null
                || !value.isAbsolute()
                || value.getHost() == null
                || value.getQuery() != null
                || value.getFragment() != null
                || (!("http".equalsIgnoreCase(value.getScheme()))
                && !("https".equalsIgnoreCase(value.getScheme())))) {
            throw new IllegalArgumentException(
                    name + " must be an absolute HTTP(S) URI"
            );
        }
    }

    private static void requireRange(
            long value,
            long minimum,
            long maximum,
            String name
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
