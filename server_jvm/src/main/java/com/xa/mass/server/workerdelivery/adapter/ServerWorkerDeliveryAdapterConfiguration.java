package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.http.WorkerDeliveryHttpClient;
import com.xa.mass.workerdelivery.adapter.netty.NettyAdapterProcessConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerDeliveryAdapters;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
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
            "processes",
            "send-time-limit"
    );
    private static final Set<String> TASK_COMMAND_FIELDS = Set.of(
            "type",
            "interval",
            "consume-limit",
            "queue-capacity"
    );
    private static final Set<String> TASK_REPORT_FIELDS = Set.of(
            "type",
            "interval",
            "queue-capacity"
    );

    @Bean
    WorkerDeliveryHttpClient workerDeliveryHttpClient(
            ServerWorkerDeliveryAdapterProperties properties
    ) {
        return new WorkerDeliveryHttpClient(
                properties.httpClient().baseUrl(),
                properties.httpClient().requestTimeout()
        );
    }

    @Bean
    WorkerDeliveryAdapterManager workerDeliveryAdapterManager(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerDeliveryHttpClient httpClient,
            WorkerEndpointDirectory endpointDirectory
    ) {
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        properties.instanceConfigs().forEach((adapterId, config) ->
                manager.register(createAdapter(
                        adapterId,
                        config,
                        properties.httpClient().requestTimeout(),
                        httpClient,
                        endpointDirectory
                ))
        );
        return manager;
    }

    private static WorkerDeliveryAdapter createAdapter(
            String adapterId,
            JsonNode config,
            Duration shutdownTimeout,
            WorkerDeliveryHttpClient httpClient,
            WorkerEndpointDirectory endpointDirectory
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
        WorkerTransportType transportType;
        try {
            transportType = WorkerTransportType.valueOf(type);
        } catch (IllegalArgumentException error) {
            throw invalid(adapterId, "type must be WEBSOCKET or SOCKET");
        }
        if (!endpointDirectory.contains(adapterId, transportType)) {
            throw invalid(
                    adapterId,
                    "a matching worker-binding endpoint must be configured"
            );
        }
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
        List<NettyAdapterProcessConfig> processConfigs =
                parseProcessConfigs(object, adapterId);
        Duration sendTimeLimit = optionalDuration(
                object,
                "send-time-limit",
                Duration.ofSeconds(5),
                adapterId
        );
        return switch (type) {
            case "WEBSOCKET" -> NettyWorkerDeliveryAdapters.webSocket(
                    adapterId,
                    httpClient,
                    listenHost,
                    listenPort,
                    processConfigs,
                    sendTimeLimit,
                    shutdownTimeout
            );
            case "SOCKET" -> NettyWorkerDeliveryAdapters.socket(
                    adapterId,
                    httpClient,
                    listenHost,
                    listenPort,
                    processConfigs,
                    sendTimeLimit,
                    shutdownTimeout
            );
            default -> throw invalid(adapterId, "unsupported adapter type");
        };
    }

    private static List<NettyAdapterProcessConfig> parseProcessConfigs(
            ObjectNode adapter,
            String adapterId
    ) {
        JsonNode value = adapter.get("processes");
        List<JsonNode> processes = orderedProcessNodes(value, adapterId);
        if (processes.isEmpty()) {
            throw invalid(
                    adapterId,
                    "processes must be a non-empty list"
            );
        }
        ArrayList<NettyAdapterProcessConfig> configs = new ArrayList<>(
                processes.size()
        );
        HashSet<String> observedTypes = new HashSet<>();
        for (int index = 0; index < processes.size(); index++) {
            JsonNode process = processes.get(index);
            if (!(process instanceof ObjectNode object)) {
                throw invalid(
                        adapterId,
                        "processes[" + index + "] must be an object"
                );
            }
            String type = requiredText(
                    object,
                    "type",
                    adapterId
            );
            if (!observedTypes.add(type)) {
                throw invalid(
                        adapterId,
                        "process type must be unique: " + type
                );
            }
            try {
                switch (type) {
                    case "TASK_COMMAND" -> {
                        rejectUnknownFields(
                                object,
                                TASK_COMMAND_FIELDS,
                                adapterId,
                                index
                        );
                        configs.add(new NettyAdapterProcessConfig.TaskCommand(
                                optionalDuration(
                                        object,
                                        "interval",
                                        Duration.ofMillis(100),
                                        adapterId
                                ),
                                optionalInt(
                                        object,
                                        "consume-limit",
                                        100,
                                        adapterId
                                ),
                                optionalInt(
                                        object,
                                        "queue-capacity",
                                        1000,
                                        adapterId
                                )
                        ));
                    }
                    case "TASK_REPORT" -> {
                        rejectUnknownFields(
                                object,
                                TASK_REPORT_FIELDS,
                                adapterId,
                                index
                        );
                        configs.add(new NettyAdapterProcessConfig.TaskReport(
                                optionalDuration(
                                        object,
                                        "interval",
                                        Duration.ofSeconds(1),
                                        adapterId
                                ),
                                optionalInt(
                                        object,
                                        "queue-capacity",
                                        1000,
                                        adapterId
                                )
                        ));
                    }
                    default -> throw invalid(
                            adapterId,
                            "unsupported process type: " + type
                    );
                }
            } catch (IllegalArgumentException error) {
                if (error.getMessage() != null
                        && error.getMessage().startsWith("Invalid Adapter ")) {
                    throw error;
                }
                throw invalid(
                        adapterId,
                        "invalid process " + type + ": "
                                + error.getMessage(),
                        error
                );
            }
        }
        if (!observedTypes.equals(Set.of(
                "TASK_COMMAND",
                "TASK_REPORT"
        ))) {
            throw invalid(
                    adapterId,
                    "processes require exactly TASK_COMMAND and TASK_REPORT"
            );
        }
        return List.copyOf(configs);
    }

    private static List<JsonNode> orderedProcessNodes(
            JsonNode value,
            String adapterId
    ) {
        if (value instanceof ArrayNode array) {
            ArrayList<JsonNode> values = new ArrayList<>(array.size());
            array.forEach(values::add);
            return values;
        }
        if (value instanceof ObjectNode indexed) {
            ArrayList<JsonNode> values = new ArrayList<>(indexed.size());
            for (int index = 0; index < indexed.size(); index++) {
                JsonNode process = indexed.get(Integer.toString(index));
                if (process == null) {
                    throw invalid(
                            adapterId,
                            "processes indices must be contiguous from zero"
                    );
                }
                values.add(process);
            }
            return values;
        }
        throw invalid(adapterId, "processes must be a list");
    }

    private static void rejectUnknownFields(
            ObjectNode object,
            Set<String> allowed,
            String adapterId,
            int index
    ) {
        Set<String> fields = new HashSet<>(object.propertyNames());
        if (allowed.containsAll(fields)) {
            return;
        }
        fields.removeAll(allowed);
        throw invalid(
                adapterId,
                "unknown fields in processes[" + index + "]: " + fields
        );
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
