package com.xa.mass.integration.workerlab;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class WorkerLabReliabilityMain {

    private static final System.Logger LOG = System.getLogger(
            WorkerLabReliabilityMain.class.getName()
    );

    private WorkerLabReliabilityMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        WorkerLabReliability.execute(options);
        LOG.log(
                System.Logger.Level.INFO,
                "Worker Lab reliability proof completed; summary={0}",
                options.evidenceDirectory().toAbsolutePath().normalize()
                        .resolve(ReliabilityEvidence.SUMMARY_FILE)
        );
    }

    record Options(
            URI runtimeApiBaseUrl,
            URI labControlBaseUrl,
            String endpointManagerId,
            String proofId,
            Path evidenceDirectory,
            long maximumWaitMillis,
            long requestTimeoutMillis,
            long scheduledStopDelayMillis
    ) {

        private static final URI DEFAULT_RUNTIME_API =
                URI.create("http://127.0.0.1:18082");
        private static final URI DEFAULT_LAB_CONTROL =
                URI.create("http://127.0.0.1:18086");

        Options {
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
            requireRange(
                    scheduledStopDelayMillis,
                    1,
                    86_400_000,
                    "scheduled-stop-delay-millis"
            );
        }

        static Options parse(String[] arguments) {
            if (arguments == null) {
                throw new IllegalArgumentException(
                        "arguments must be present"
                );
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String argument : arguments) {
                if (argument == null) {
                    throw new IllegalArgumentException(
                            "arguments must not contain null"
                    );
                }
                int separator = argument.indexOf('=');
                if (!argument.startsWith("--")
                        || separator <= 2
                        || separator == argument.length() - 1) {
                    throw new IllegalArgumentException(
                            "arguments must use --name=value"
                    );
                }
                String name = argument.substring(2, separator);
                String value = argument.substring(separator + 1);
                if (!known(name)) {
                    throw new IllegalArgumentException(
                            "Unknown Worker Lab reliability argument: " + name
                    );
                }
                if (values.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate Worker Lab reliability argument: "
                                    + name
                    );
                }
            }
            String proofId = values.getOrDefault(
                    "proof-id",
                    "worker-lab-reliability"
            );
            return new Options(
                    URI.create(values.getOrDefault(
                            "runtime-api-base-url",
                            DEFAULT_RUNTIME_API.toString()
                    )),
                    URI.create(values.getOrDefault(
                            "lab-control-base-url",
                            DEFAULT_LAB_CONTROL.toString()
                    )),
                    values.getOrDefault(
                            "endpoint-manager-id",
                            "scenario-websocket"
                    ),
                    proofId,
                    Path.of(values.getOrDefault(
                            "evidence-dir",
                            "build/worker-lab-reliability/" + proofId
                    )),
                    number(values, "maximum-wait-millis", 120_000),
                    number(values, "request-timeout-millis", 10_000),
                    number(values, "scheduled-stop-delay-millis", 1_000)
            );
        }

        Duration requestTimeout() {
            return Duration.ofMillis(requestTimeoutMillis);
        }

        Duration maximumWait() {
            return Duration.ofMillis(maximumWaitMillis);
        }

        private static boolean known(String name) {
            return switch (name) {
                case "runtime-api-base-url",
                     "lab-control-base-url",
                     "endpoint-manager-id",
                     "proof-id",
                     "evidence-dir",
                     "maximum-wait-millis",
                     "request-timeout-millis",
                     "scheduled-stop-delay-millis" -> true;
                default -> false;
            };
        }

        private static long number(
                Map<String, String> values,
                String name,
                long defaultValue
        ) {
            String value = values.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(
                        name + " must be an integer",
                        error
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
                        name + " must be between " + minimum + " and "
                                + maximum
                );
            }
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

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        name + " must be non-blank"
                );
            }
        }
    }
}
