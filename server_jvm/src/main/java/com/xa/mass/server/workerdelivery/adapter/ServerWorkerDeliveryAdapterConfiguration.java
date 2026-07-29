package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.http.HttpWorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.socket.SocketWorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.websocket.WebSocketWorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
        ServerWorkerDeliveryAdapterProperties.class
)
public class ServerWorkerDeliveryAdapterConfiguration {

    private static final Set<String> INSTANCE_FIELDS = Set.of(
            "type",
            "listen-host",
            "listen-port",
            "dispatch-interval",
            "command-consume-limit",
            "delivery-parallelism",
            "result-batch-size",
            "result-buffer-capacity",
            "send-time-limit"
    );

    @Bean
    WorkerDeliveryGatewayClient workerDeliveryGatewayClient(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerDeliveryCodec codec
    ) {
        return new HttpWorkerDeliveryGatewayClient(
                properties.gateway().baseUrl(),
                properties.gateway().requestTimeout(),
                codec
        );
    }

    @Bean
    WorkerDeliveryAdapterManager workerDeliveryAdapterManager(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec
    ) {
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        properties.instanceConfigs().forEach((adapterId, config) ->
                manager.register(createAdapter(
                        adapterId,
                        config,
                        properties.gateway().requestTimeout(),
                        gateway,
                        codec
                ))
        );
        return manager;
    }

    @Bean
    WorkerDeliveryAdapterLifecycleHost
    workerDeliveryAdapterLifecycleHost(
            WorkerDeliveryAdapterManager manager
    ) {
        return new WorkerDeliveryAdapterLifecycleHost(manager);
    }

    private static WorkerDeliveryAdapter createAdapter(
            String adapterId,
            JsonNode config,
            Duration shutdownTimeout,
            WorkerDeliveryGatewayClient gateway,
            WorkerDeliveryCodec codec
    ) {
        if (!(config instanceof ObjectNode object)) {
            throw invalid(adapterId, "config must be an object");
        }
        Set<String> fields = new HashSet<>(object.propertyNames());
        if (!INSTANCE_FIELDS.containsAll(fields)) {
            fields.removeAll(INSTANCE_FIELDS);
            throw invalid(
                    adapterId,
                    "unknown fields: " + fields
            );
        }
        String type = requiredText(object, "type", adapterId);
        int listenPort = requiredInt(
                object,
                "listen-port",
                adapterId
        );
        String listenHost = optionalText(
                object,
                "listen-host",
                "0.0.0.0",
                adapterId
        );
        Duration dispatchInterval = optionalDuration(
                object,
                "dispatch-interval",
                Duration.ofMillis(100),
                adapterId
        );
        int commandConsumeLimit = optionalInt(
                object,
                "command-consume-limit",
                100,
                adapterId
        );
        int deliveryParallelism = optionalInt(
                object,
                "delivery-parallelism",
                16,
                adapterId
        );
        int resultBatchSize = optionalInt(
                object,
                "result-batch-size",
                100,
                adapterId
        );
        int resultBufferCapacity = optionalInt(
                object,
                "result-buffer-capacity",
                1000,
                adapterId
        );
        Duration sendTimeLimit = optionalDuration(
                object,
                "send-time-limit",
                Duration.ofSeconds(5),
                adapterId
        );
        return switch (type) {
            case "WEBSOCKET" -> new WebSocketWorkerDeliveryAdapter(
                    adapterId,
                    gateway,
                    codec,
                    listenHost,
                    listenPort,
                    dispatchInterval,
                    commandConsumeLimit,
                    deliveryParallelism,
                    resultBatchSize,
                    resultBufferCapacity,
                    sendTimeLimit,
                    shutdownTimeout
            );
            case "SOCKET" -> new SocketWorkerDeliveryAdapter(
                    adapterId,
                    gateway,
                    codec,
                    listenHost,
                    listenPort,
                    dispatchInterval,
                    commandConsumeLimit,
                    deliveryParallelism,
                    resultBatchSize,
                    resultBufferCapacity,
                    sendTimeLimit,
                    shutdownTimeout
            );
            default -> throw invalid(
                    adapterId,
                    "type must be WEBSOCKET or SOCKET"
            );
        };
    }

    private static String requiredText(
            ObjectNode object,
            String field,
            String adapterId
    ) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()
                || value.textValue().isBlank()) {
            throw invalid(
                    adapterId,
                    field + " must be a non-blank string"
            );
        }
        return value.textValue();
    }

    private static String optionalText(
            ObjectNode object,
            String field,
            String defaultValue,
            String adapterId
    ) {
        JsonNode value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(
                    adapterId,
                    field + " must be a non-blank string"
            );
        }
        return value.textValue();
    }

    private static int requiredInt(
            ObjectNode object,
            String field,
            String adapterId
    ) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw invalid(
                    adapterId,
                    field + " must be an integer"
            );
        }
        return parseInt(value, field, adapterId);
    }

    private static int optionalInt(
            ObjectNode object,
            String field,
            int defaultValue,
            String adapterId
    ) {
        JsonNode value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        int parsed = parseInt(value, field, adapterId);
        if (parsed <= 0) {
            throw invalid(
                    adapterId,
                    field + " must be a positive integer"
            );
        }
        return parsed;
    }

    private static int parseInt(
            JsonNode value,
            String field,
            String adapterId
    ) {
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.textValue());
            } catch (NumberFormatException error) {
                throw invalid(
                        adapterId,
                        field + " must be an integer",
                        error
                );
            }
        }
        throw invalid(
                adapterId,
                field + " must be an integer"
        );
    }

    private static Duration optionalDuration(
            ObjectNode object,
            String field,
            Duration defaultValue,
            String adapterId
    ) {
        JsonNode value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.isTextual()) {
            throw invalid(
                    adapterId,
                    field + " must be a duration string"
            );
        }
        try {
            Duration duration = DurationStyle.detectAndParse(
                    value.textValue()
            );
            if (duration.isZero()
                    || duration.isNegative()
                    || duration.toMillis() <= 0) {
                throw invalid(
                        adapterId,
                        field + " must be positive"
                );
            }
            return duration;
        } catch (IllegalArgumentException error) {
            throw invalid(
                    adapterId,
                    field + " must be a positive duration",
                    error
            );
        }
    }

    private static IllegalArgumentException invalid(
            String adapterId,
            String message
    ) {
        return new IllegalArgumentException(
                "Invalid Adapter " + adapterId + ": " + message
        );
    }

    private static IllegalArgumentException invalid(
            String adapterId,
            String message,
            Exception cause
    ) {
        return new IllegalArgumentException(
                "Invalid Adapter " + adapterId + ": " + message,
                cause
        );
    }
}
