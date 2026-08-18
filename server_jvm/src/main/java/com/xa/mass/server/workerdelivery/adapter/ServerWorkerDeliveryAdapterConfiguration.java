package com.xa.mass.server.workerdelivery.adapter;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterManager;
import com.xa.mass.workerdelivery.adapter.netty.NettyAdapterProcessConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerPropertiesCacheConfig;
import com.xa.mass.workerdelivery.adapter.netty.NettyWorkerRouteCacheConfig;
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

    private static final Duration DEFAULT_RECONNECT_VERIFICATION_RETENTION =
            Duration.ofMinutes(10);
    private static final long DEFAULT_MAXIMUM_DISCONNECTED_WORKERS = 100_000L;
    private static final long DEFAULT_MAXIMUM_PROPERTIES_BYTES =
            64L * 1024L * 1024L;

    private static final Set<String> INSTANCE_FIELDS = Set.of(
            "type",
            "listen-host",
            "listen-port",
            "processes",
            "route-cache",
            "properties-cache",
            "send-time-limit"
    );
    private static final Set<String> ROUTE_CACHE_FIELDS = Set.of(
            "reconnect-verification-retention",
            "maximum-disconnected-workers"
    );
    private static final Set<String> PROPERTIES_CACHE_FIELDS = Set.of(
            "maximum-encoded-bytes"
    );
    private static final Set<String> COMMAND_PROCESS_FIELDS = Set.of(
            "type",
            "interval",
            "consume-limit",
            "queue-capacity"
    );
    private static final Set<String> REPORT_PROCESS_FIELDS = Set.of(
            "type",
            "interval",
            "queue-capacity"
    );

    @Bean
    WorkerDeliveryAdapterManager workerDeliveryAdapterManager(
            ServerWorkerDeliveryAdapterProperties properties,
            WorkerEndpointDirectory endpointDirectory
    ) {
        WorkerDeliveryAdapterManager manager =
                new WorkerDeliveryAdapterManager();
        properties.instanceConfigs().forEach((adapterId, config) ->
                manager.register(createAdapter(
                        adapterId,
                        config,
                        properties.httpClient(),
                        endpointDirectory
                ))
        );
        return manager;
    }

    private static WorkerDeliveryAdapter createAdapter(
            String adapterId,
            JsonNode config,
            ServerWorkerDeliveryAdapterProperties.HttpClientProperties
                    httpProperties,
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
        NettyWorkerRouteCacheConfig routeCacheConfig =
                parseRouteCacheConfig(object, adapterId);
        NettyWorkerPropertiesCacheConfig propertiesCacheConfig =
                parsePropertiesCacheConfig(object, adapterId);
        return switch (type) {
            case "WEBSOCKET" -> NettyWorkerDeliveryAdapters.webSocket(
                    adapterId,
                    httpProperties.baseUrl(),
                    httpProperties.requestTimeout(),
                    listenHost,
                    listenPort,
                    processConfigs,
                    routeCacheConfig,
                    propertiesCacheConfig,
                    sendTimeLimit,
                    httpProperties.requestTimeout()
            );
            case "SOCKET" -> NettyWorkerDeliveryAdapters.socket(
                    adapterId,
                    httpProperties.baseUrl(),
                    httpProperties.requestTimeout(),
                    listenHost,
                    listenPort,
                    processConfigs,
                    routeCacheConfig,
                    propertiesCacheConfig,
                    sendTimeLimit,
                    httpProperties.requestTimeout()
            );
            default -> throw invalid(adapterId, "unsupported adapter type");
        };
    }

    private static NettyWorkerRouteCacheConfig parseRouteCacheConfig(
            ObjectNode adapter,
            String adapterId
    ) {
        JsonNode value = adapter.get("route-cache");
        if (value == null) {
            return new NettyWorkerRouteCacheConfig(
                    DEFAULT_RECONNECT_VERIFICATION_RETENTION,
                    DEFAULT_MAXIMUM_DISCONNECTED_WORKERS
            );
        }
        if (!(value instanceof ObjectNode object)) {
            throw invalid(adapterId, "route-cache must be an object");
        }
        rejectUnknownFields(
                object,
                ROUTE_CACHE_FIELDS,
                adapterId,
                "route-cache"
        );
        return new NettyWorkerRouteCacheConfig(
                optionalDuration(
                        object,
                        "reconnect-verification-retention",
                        DEFAULT_RECONNECT_VERIFICATION_RETENTION,
                        adapterId
                ),
                optionalLong(
                        object,
                        "maximum-disconnected-workers",
                        DEFAULT_MAXIMUM_DISCONNECTED_WORKERS,
                        adapterId
                )
        );
    }

    private static NettyWorkerPropertiesCacheConfig
            parsePropertiesCacheConfig(
            ObjectNode adapter,
            String adapterId
    ) {
        JsonNode value = adapter.get("properties-cache");
        if (value == null) {
            return new NettyWorkerPropertiesCacheConfig(
                    DEFAULT_MAXIMUM_PROPERTIES_BYTES
            );
        }
        if (!(value instanceof ObjectNode object)) {
            throw invalid(adapterId, "properties-cache must be an object");
        }
        rejectUnknownFields(
                object,
                PROPERTIES_CACHE_FIELDS,
                adapterId,
                "properties-cache"
        );
        return new NettyWorkerPropertiesCacheConfig(
                optionalLong(
                        object,
                        "maximum-encoded-bytes",
                        DEFAULT_MAXIMUM_PROPERTIES_BYTES,
                        adapterId
                )
        );
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
                    case "DELIVERY_COMMAND" -> {
                        rejectUnknownFields(
                                object,
                                COMMAND_PROCESS_FIELDS,
                                adapterId,
                                index
                        );
                        configs.add(new NettyAdapterProcessConfig
                                .DeliveryCommand(
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
                    case "DELIVERY_REPORT" -> {
                        rejectUnknownFields(
                                object,
                                REPORT_PROCESS_FIELDS,
                                adapterId,
                                index
                        );
                        configs.add(new NettyAdapterProcessConfig
                                .DeliveryReport(
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
                "DELIVERY_COMMAND",
                "DELIVERY_REPORT"
        ))) {
            throw invalid(
                    adapterId,
                    "processes require exactly DELIVERY_COMMAND and "
                            + "DELIVERY_REPORT"
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
        rejectUnknownFields(
                object,
                allowed,
                adapterId,
                "processes[" + index + "]"
        );
    }

    private static void rejectUnknownFields(
            ObjectNode object,
            Set<String> allowed,
            String adapterId,
            String location
    ) {
        Set<String> fields = new HashSet<>(object.propertyNames());
        if (allowed.containsAll(fields)) {
            return;
        }
        fields.removeAll(allowed);
        throw invalid(
                adapterId,
                "unknown fields in " + location + ": " + fields
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

    private static long optionalLong(
            ObjectNode object,
            String field,
            long defaultValue,
            String adapterId
    ) {
        JsonNode value = object.get(field);
        if (value == null) {
            return defaultValue;
        }
        long parsed;
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            parsed = value.longValue();
        } else if (value.isTextual()) {
            try {
                parsed = Long.parseLong(value.textValue());
            } catch (NumberFormatException error) {
                throw invalid(
                        adapterId,
                        field + " must be an integer",
                        error
                );
            }
        } else {
            throw invalid(adapterId, field + " must be an integer");
        }
        if (parsed <= 0) {
            throw invalid(
                    adapterId,
                    field + " must be a positive integer"
            );
        }
        return parsed;
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
