package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.KERNEL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.SYSTEM;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerPropertiesObservation;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Static local event dispatch for Adapter-targeted commands. */
public final class AdapterEventDispatcher {

    private static final Pattern PLATFORM_ADAPTER_EVENT_PATTERN =
            Pattern.compile(
                    "platform\\.adapter\\.[a-z][a-z0-9-]*"
                            + "(?:\\.[a-z][a-z0-9-]*)*"
            );

    static final String PROBE_EVENT = "platform.adapter.probe";
    static final String CONNECTION_SNAPSHOT_EVENT =
            "platform.adapter.worker-connections.snapshot";
    static final String CLOSE_CURRENT_EVENT =
            "platform.adapter.worker-connections.close-current";
    static final String WORKER_PROPERTIES_SNAPSHOT_EVENT =
            "platform.adapter.worker-properties.snapshot";
    static final String EVENTS_SNAPSHOT_EVENT =
            "platform.adapter.events.snapshot";

    private static final System.Logger LOGGER = System.getLogger(
            AdapterEventDispatcher.class.getName()
    );

    private final String adapterId;
    private final Map<String, AdapterEventHandler> handlers;

    public static AdapterEventDispatcher defaults(
            String adapterId,
            WorkerConnectionMechanism connections
    ) {
        Objects.requireNonNull(connections, "connections");
        Map<String, AdapterEventHandler> handlers = new LinkedHashMap<>();
        handlers.put(PROBE_EVENT, payload -> {
            requireNullPayload(payload);
            return Jsons.toJson(Map.of(
                    "adapterId", adapterId,
                    "reachable", true
            ));
        });
        handlers.put(CONNECTION_SNAPSHOT_EVENT, payload -> {
            Map<String, String> encoded = new LinkedHashMap<>();
            connections.connectionStates(parseWorkerIds(payload))
                    .forEach((workerId, state) -> encoded.put(
                            workerId,
                            state.name()
                    ));
            return Jsons.toJson(Map.of("stateByWorkerId", encoded));
        });
        handlers.put(CLOSE_CURRENT_EVENT, payload -> {
            Map<String, String> encoded = new LinkedHashMap<>();
            connections.closeCurrentConnections(parseWorkerIds(payload))
                    .forEach((workerId, outcome) -> encoded.put(
                            workerId,
                            outcome.wireValue()
                    ));
            return Jsons.toJson(Map.of("outcomeByWorkerId", encoded));
        });
        handlers.put(WORKER_PROPERTIES_SNAPSHOT_EVENT, payload -> {
            Map<String, Object> encoded = new LinkedHashMap<>();
            connections.workerProperties(parseWorkerIds(payload))
                    .forEach((workerId, observation) -> encoded.put(
                            workerId,
                            encodeProperties(observation)
                    ));
            return Jsons.toJson(Map.of(
                    "propertiesByWorkerId",
                    encoded
            ));
        });
        return new AdapterEventDispatcher(adapterId, handlers);
    }

    AdapterEventDispatcher(
            String adapterId,
            Map<String, AdapterEventHandler> handlers
    ) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        Objects.requireNonNull(handlers, "handlers");
        Map<String, AdapterEventHandler> copied = new LinkedHashMap<>();
        handlers.forEach((eventCode, handler) -> {
            if (eventCode == null
                    || !PLATFORM_ADAPTER_EVENT_PATTERN
                            .matcher(eventCode)
                            .matches()) {
                throw new IllegalArgumentException(
                        "Adapter eventCode must be a "
                                + "platform.adapter event"
                );
            }
            if (EVENTS_SNAPSHOT_EVENT.equals(eventCode)) {
                throw new IllegalArgumentException(
                        EVENTS_SNAPSHOT_EVENT + " is reserved"
                );
            }
            copied.put(
                    eventCode,
                    Objects.requireNonNull(handler, "handler")
            );
        });
        List<String> eventNames = new ArrayList<>(copied.keySet());
        eventNames.add(EVENTS_SNAPSHOT_EVENT);
        Collections.sort(eventNames);
        String eventsSnapshot = Jsons.toJson(Map.of(
                "eventNames",
                List.copyOf(eventNames)
        ));
        copied.put(EVENTS_SNAPSHOT_EVENT, payload -> {
            requireNullPayload(payload);
            return eventsSnapshot;
        });
        this.adapterId = adapterId;
        this.handlers = Collections.unmodifiableMap(copied);
    }

    DeliveryReport dispatch(DeliveryCommand command) {
        Objects.requireNonNull(command, "command");
        boolean systemEvent = command.src() == SYSTEM;
        boolean kernelSnapshot = command.src() == KERNEL
                && CONNECTION_SNAPSHOT_EVENT.equals(command.messageType());
        if (command.dst() != ADAPTER
                || (!systemEvent && !kernelSnapshot)) {
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode.ADAPTER_COMMAND_INVALID,
                    "null"
            );
        }
        AdapterEventHandler handler = handlers.get(command.messageType());
        if (handler == null) {
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode.ADAPTER_EVENT_UNSUPPORTED,
                    "null"
            );
        }

        String payload;
        try {
            payload = handler.execute(command.payload());
            if (payload == null || payload.isBlank()) {
                throw new IllegalStateException(
                        "Adapter event result must be present"
                );
            }
        } catch (InvalidAdapterEventPayloadException error) {
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode.ADAPTER_COMMAND_INVALID,
                    "null"
            );
        } catch (Exception error) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "errorCode={0} operation={1} adapterId={2} "
                            + "messageType={3} failureType={4}",
                    WorkerDeliveryAdapterErrorCode
                            .ADAPTER_EVENT_EXECUTION_FAILED.code(),
                    "adapterEvent.dispatch",
                    adapterId,
                    command.messageType(),
                    error.getClass().getName()
            );
            return result(
                    command,
                    WorkerDeliveryAdapterErrorCode
                            .ADAPTER_EVENT_EXECUTION_FAILED,
                    "null"
            );
        }
        return DeliveryReport.fromCommand(
                command,
                ADAPTER,
                adapterId,
                "200",
                payload
        );
    }

    private DeliveryReport result(
            DeliveryCommand command,
            WorkerDeliveryAdapterErrorCode errorCode,
            String payload
    ) {
        return DeliveryReport.fromCommand(
                command,
                ADAPTER,
                adapterId,
                Integer.toString(errorCode.code()),
                payload
        );
    }

    private static void requireNullPayload(String payload) {
        if (!"null".equals(payload)) {
            throw new InvalidAdapterEventPayloadException(
                    "Adapter event payload must be null"
            );
        }
    }

    private static List<String> parseWorkerIds(String payload) {
        Map<String, Object> parsed;
        try {
            parsed = Jsons.parseObject(payload);
        } catch (RuntimeException error) {
            throw new InvalidAdapterEventPayloadException(
                    "Adapter event payload must be a JSON object",
                    error
            );
        }
        if (parsed.size() != 1 || !parsed.containsKey("workerIds")) {
            throw new InvalidAdapterEventPayloadException(
                    "Adapter event payload must contain only workerIds"
            );
        }
        Object rawWorkerIds = parsed.get("workerIds");
        if (!(rawWorkerIds instanceof List<?> values)
                || values.isEmpty()
                || values.size() > 100) {
            throw new InvalidAdapterEventPayloadException(
                    "workerIds must contain between 1 and 100 entries"
            );
        }
        List<String> workerIds = new ArrayList<>(values.size());
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String workerId)
                    || workerId.isBlank()) {
                throw new InvalidAdapterEventPayloadException(
                        "workerIds must contain non-blank strings"
                );
            }
            if (!unique.add(workerId)) {
                throw new InvalidAdapterEventPayloadException(
                        "workerIds must be unique"
                );
            }
            workerIds.add(workerId);
        }
        return List.copyOf(workerIds);
    }

    private static Map<String, Object> encodeProperties(
            WorkerPropertiesObservation observation
    ) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("updatedAtMillis", observation.updatedAtMillis());
        encoded.put("properties", observation.properties());
        return encoded;
    }

    @FunctionalInterface
    interface AdapterEventHandler {
        String execute(String payload) throws Exception;
    }

    private static final class InvalidAdapterEventPayloadException
            extends IllegalArgumentException {

        private InvalidAdapterEventPayloadException(String message) {
            super(message);
        }

        private InvalidAdapterEventPayloadException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}
