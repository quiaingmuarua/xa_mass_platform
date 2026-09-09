package com.xa.mass.workerdelivery.protocol;

import java.util.Objects;

public final class WorkerDeliveryProtocol {

    public static final String SYSTEM_POLLING_ENDPOINT_MANAGER_ID =
            "system-polling";
    public static final String WORKER_CONNECTION_IDENTIFY_EVENT_CODE =
            "worker.connection.identify";
    public static final String WORKER_CONNECTION_CLOSE_EVENT_CODE =
            "worker.connection.close";
    public static final String WORKER_COMMAND_SUCCEEDED =
            "platform.worker.command.succeeded";
    public static final String WORKER_COMMAND_FAILED =
            "platform.worker.command.failed";
    public static final String ADAPTER_COMMAND_SUCCEEDED =
            "platform.adapter.command.succeeded";
    public static final String ADAPTER_COMMAND_FAILED =
            "platform.adapter.command.failed";
    public static final String ADAPTER_COMMAND_DELIVERY_FAILED =
            "platform.adapter.command.delivery-failed";
    public static final String WORKER_PROPERTIES_UPDATED =
            "platform.worker.properties.updated";
    public static final String WORKER_PROPERTIES_REPLACED =
            "platform.worker.properties.replaced";
    public static final String ADAPTER_WORKER_PROPERTIES_OBSERVED =
            "platform.adapter.worker-properties.observed";
    public static final String ADAPTER_WORKER_CONNECTION_CHANGED =
            "platform.adapter.worker-connection.changed";
    public static final String ADAPTER_WORKER_DELIVERY_EXPIRED =
            "platform.adapter.worker-delivery.expired";
    public static final String SERVER_WORKER_POLL_OBSERVED =
            "platform.server.worker-poll.observed";

    private WorkerDeliveryProtocol() {
    }

    public enum DeliveryEndpoint {
        TASK("TASK"),
        /** Server-owned calls and their correlated replies. */
        SERVER("SERVER"),
        /** Platform events; their message contract determines the semantic owner. */
        SYSTEM("SYSTEM"),
        KERNEL("KERNEL"),
        ADAPTER("ADAPTER"),
        WORKER("WORKER");

        private final String wireValue;

        DeliveryEndpoint(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static DeliveryEndpoint fromWire(String value) {
            for (DeliveryEndpoint endpoint : values()) {
                if (endpoint.wireValue.equals(value)) {
                    return endpoint;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown delivery endpoint: " + value
            );
        }
    }

    public static final class DeliveryCommand {

        private final DeliveryEndpoint src;
        private final DeliveryEndpoint dst;
        private final String messageType;
        private final long executeBeforeMillis;
        private final String payload;
        private final String forward;

        private DeliveryCommand(
                DeliveryEndpoint src,
                DeliveryEndpoint dst,
                String messageType,
                long executeBeforeMillis,
                String payload,
                String forward
        ) {
            this.src = Objects.requireNonNull(src, "src");
            this.dst = Objects.requireNonNull(dst, "dst");
            requireNonBlank(messageType, "messageType");
            if (executeBeforeMillis <= 0) {
                throw new IllegalArgumentException(
                        "executeBeforeMillis must be positive"
                );
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(forward, "forward");
            if (src == DeliveryEndpoint.TASK && forward.isEmpty()) {
                throw new IllegalArgumentException(
                        "TASK command forward must be non-empty"
                );
            }
            this.messageType = messageType;
            this.executeBeforeMillis = executeBeforeMillis;
            this.payload = payload;
            this.forward = forward;
        }

        public static DeliveryCommand create(
                DeliveryEndpoint src,
                DeliveryEndpoint dst,
                String messageType,
                long executeBeforeMillis,
                String payload,
                String forward
        ) {
            return new DeliveryCommand(
                    src,
                    dst,
                    messageType,
                    executeBeforeMillis,
                    payload,
                    forward
            );
        }

        static DeliveryCommand restore(
                DeliveryEndpoint src,
                DeliveryEndpoint dst,
                String messageType,
                long executeBeforeMillis,
                String payload,
                String forward
        ) {
            return new DeliveryCommand(
                    src,
                    dst,
                    messageType,
                    executeBeforeMillis,
                    payload,
                    forward
            );
        }

        public DeliveryEndpoint src() {
            return src;
        }

        public DeliveryEndpoint dst() {
            return dst;
        }

        public String messageType() {
            return messageType;
        }

        public long executeBeforeMillis() {
            return executeBeforeMillis;
        }

        public String payload() {
            return payload;
        }

        public String forward() {
            return forward;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof DeliveryCommand)) {
                return false;
            }
            DeliveryCommand other = (DeliveryCommand) value;
            return executeBeforeMillis == other.executeBeforeMillis
                    && src == other.src
                    && dst == other.dst
                    && messageType.equals(other.messageType)
                    && payload.equals(other.payload)
                    && forward.equals(other.forward);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    src,
                    dst,
                    messageType,
                    executeBeforeMillis,
                    payload,
                    forward
            );
        }

        @Override
        public String toString() {
            return "DeliveryCommand[src=" + src
                    + ", dst=" + dst
                    + ", messageType=" + messageType
                    + ", executeBeforeMillis=" + executeBeforeMillis
                    + ", payload=<opaque>, forward=<opaque>]";
        }
    }

    public static final class DeliveryReport {

        private final DeliveryEndpoint src;
        private final String sourceId;
        private final DeliveryEndpoint dst;
        private final String messageType;
        private final String diagnosticCode;
        private final String payload;
        private final String forward;

        private DeliveryReport(
                DeliveryEndpoint src,
                String sourceId,
                DeliveryEndpoint dst,
                String messageType,
                String diagnosticCode,
                String payload,
                String forward
        ) {
            this.src = Objects.requireNonNull(src, "src");
            requireNonBlank(sourceId, "sourceId");
            this.dst = Objects.requireNonNull(dst, "dst");
            requireNonBlank(messageType, "messageType");
            Objects.requireNonNull(diagnosticCode, "diagnosticCode");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(forward, "forward");
            if (dst == DeliveryEndpoint.TASK && forward.isEmpty()) {
                throw new IllegalArgumentException(
                        "TASK report forward must be non-empty"
                );
            }
            this.sourceId = sourceId;
            this.messageType = messageType;
            this.diagnosticCode = diagnosticCode;
            this.payload = payload;
            this.forward = forward;
        }

        public static DeliveryReport fromCommand(
                DeliveryCommand command,
                DeliveryEndpoint src,
                String sourceId,
                String reportMessageType,
                String diagnosticCode,
                String payload
        ) {
            DeliveryCommand source = Objects.requireNonNull(
                    command,
                    "command"
            );
            return new DeliveryReport(
                    src,
                    sourceId,
                    source.src(),
                    reportMessageType,
                    diagnosticCode,
                    payload,
                    source.forward()
            );
        }

        public static DeliveryReport create(
                DeliveryEndpoint src,
                String sourceId,
                DeliveryEndpoint dst,
                String messageType,
                String diagnosticCode,
                String payload,
                String forward
        ) {
            return new DeliveryReport(
                    src,
                    sourceId,
                    dst,
                    messageType,
                    diagnosticCode,
                    payload,
                    forward
            );
        }

        static DeliveryReport restore(
                DeliveryEndpoint src,
                String sourceId,
                DeliveryEndpoint dst,
                String messageType,
                String diagnosticCode,
                String payload,
                String forward
        ) {
            return new DeliveryReport(
                    src,
                    sourceId,
                    dst,
                    messageType,
                    diagnosticCode,
                    payload,
                    forward
            );
        }

        public DeliveryEndpoint src() {
            return src;
        }

        public String sourceId() {
            return sourceId;
        }

        public DeliveryEndpoint dst() {
            return dst;
        }

        public String messageType() {
            return messageType;
        }

        public String diagnosticCode() {
            return diagnosticCode;
        }

        public String payload() {
            return payload;
        }

        public String forward() {
            return forward;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof DeliveryReport)) {
                return false;
            }
            DeliveryReport other = (DeliveryReport) value;
            return src == other.src
                    && sourceId.equals(other.sourceId)
                    && dst == other.dst
                    && messageType.equals(other.messageType)
                    && diagnosticCode.equals(other.diagnosticCode)
                    && payload.equals(other.payload)
                    && forward.equals(other.forward);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    src,
                    sourceId,
                    dst,
                    messageType,
                    diagnosticCode,
                    payload,
                    forward
            );
        }

        @Override
        public String toString() {
            return "DeliveryReport[src=" + src
                    + ", sourceId=" + sourceId
                    + ", dst=" + dst
                    + ", messageType=" + messageType
                    + ", diagnosticCode=" + diagnosticCode
                    + ", payload=<opaque>, forward=<opaque>]";
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
