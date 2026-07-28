package com.xa.mass.worker;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record WorkerConfiguration(
        WorkerTransportMode transport,
        String workerId,
        URI serverUrl,
        Duration requestTimeout,
        String endpointManagerId,
        Duration pollInterval,
        Duration reconnectInterval
) {

    private static final Set<String> OPTIONS = Set.of(
            "--transport",
            "--worker-id",
            "--server-url",
            "--request-timeout-millis",
            "--endpoint-manager-id",
            "--poll-interval-millis",
            "--reconnect-interval-millis"
    );

    public WorkerConfiguration {
        if (transport == null) {
            throw new IllegalArgumentException("transport must be present");
        }
        requireNonBlank(workerId, "--worker-id");
        if (serverUrl == null
                || serverUrl.getScheme() == null
                || serverUrl.getHost() == null
                || !Set.of("http", "https").contains(
                        serverUrl.getScheme()
                )) {
            throw new IllegalArgumentException(
                    "--server-url must be an absolute HTTP URL"
            );
        }
        requirePositive(requestTimeout, "--request-timeout-millis");
        if (transport == WorkerTransportMode.POLLING) {
            requireNonBlank(endpointManagerId, "--endpoint-manager-id");
            requirePositive(pollInterval, "--poll-interval-millis");
            if (reconnectInterval != null) {
                throw new IllegalArgumentException(
                        "--reconnect-interval-millis is WebSocket-only"
                );
            }
        } else {
            requirePositive(
                    reconnectInterval,
                    "--reconnect-interval-millis"
            );
            if (endpointManagerId != null || pollInterval != null) {
                throw new IllegalArgumentException(
                        "--endpoint-manager-id and --poll-interval-millis "
                                + "are polling-only"
                );
            }
        }
    }

    public static WorkerConfiguration parse(String[] args) {
        Map<String, String> values = parseOptions(args);
        WorkerTransportMode transport = WorkerTransportMode.parse(
                values.getOrDefault("--transport", "polling")
        );
        String workerId = values.get("--worker-id");
        URI serverUrl = URI.create(values.getOrDefault(
                "--server-url",
                transport == WorkerTransportMode.POLLING
                        ? "http://127.0.0.1:18082"
                        : "http://127.0.0.1:18083"
        ));
        Duration timeout = positiveDuration(
                values.getOrDefault("--request-timeout-millis", "5000"),
                "--request-timeout-millis"
        );

        if (transport == WorkerTransportMode.POLLING) {
            if (values.containsKey("--reconnect-interval-millis")) {
                throw new IllegalArgumentException(
                        "--reconnect-interval-millis is WebSocket-only"
                );
            }
            return new WorkerConfiguration(
                    transport,
                    workerId,
                    serverUrl,
                    timeout,
                    values.getOrDefault(
                            "--endpoint-manager-id",
                            SYSTEM_POLLING_ENDPOINT_MANAGER_ID
                    ),
                    positiveDuration(
                            values.getOrDefault(
                                    "--poll-interval-millis",
                                    "500"
                            ),
                            "--poll-interval-millis"
                    ),
                    null
            );
        }

        if (values.containsKey("--endpoint-manager-id")
                || values.containsKey("--poll-interval-millis")) {
            throw new IllegalArgumentException(
                    "--endpoint-manager-id and --poll-interval-millis "
                            + "are polling-only"
            );
        }
        return new WorkerConfiguration(
                transport,
                workerId,
                serverUrl,
                timeout,
                null,
                null,
                positiveDuration(
                        values.getOrDefault(
                                "--reconnect-interval-millis",
                                "1000"
                        ),
                        "--reconnect-interval-millis"
                )
        );
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            String option = args[index];
            if (!OPTIONS.contains(option)) {
                throw new IllegalArgumentException(
                        "Unknown option: " + option
                );
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException(
                        option + " requires a value"
                );
            }
            if (values.putIfAbsent(option, args[index + 1]) != null) {
                throw new IllegalArgumentException(
                        option + " must not be repeated"
                );
            }
        }
        return values;
    }

    private static Duration positiveDuration(String value, String option) {
        try {
            Duration duration = Duration.ofMillis(Long.parseLong(value));
            requirePositive(duration, option);
            return duration;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    option + " must be a positive integer",
                    error
            );
        }
    }

    private static void requirePositive(Duration value, String option) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    option + " must be positive"
            );
        }
    }

    private static void requireNonBlank(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(option + " is required");
        }
    }
}
