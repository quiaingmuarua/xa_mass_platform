package com.xa.mass.workerdelivery.protocol;

import java.util.Objects;

public final class WorkerDeliveryProtocol {

    public static final String SYSTEM_POLLING_ENDPOINT_MANAGER_ID =
            "system-polling";
    public static final String WORKER_CONNECTION_IDENTIFY_EVENT_CODE =
            "worker.connection.identify";
    public static final String WORKER_CONNECTION_CLOSE_EVENT_CODE =
            "worker.connection.close";
    private static final String SUCCESS_OUTCOME_CODE = "200";

    private WorkerDeliveryProtocol() {
    }

    public enum DeliveryEndpoint {
        TASK("TASK"),
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

    public enum DeliveryReportOutcomeClass {
        SUCCESS,
        WORKER_FAILURE,
        ADAPTER_REJECTION
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
        private final String outcomeCode;
        private final String payload;
        private final String forward;

        private DeliveryReport(
                DeliveryEndpoint src,
                String sourceId,
                DeliveryEndpoint dst,
                String messageType,
                String outcomeCode,
                String payload,
                String forward
        ) {
            this.src = Objects.requireNonNull(src, "src");
            requireNonBlank(sourceId, "sourceId");
            this.dst = Objects.requireNonNull(dst, "dst");
            requireNonBlank(messageType, "messageType");
            if (classifyDeliveryReportOutcomeCode(outcomeCode) == null) {
                throw new IllegalArgumentException(
                        "outcomeCode must be non-blank"
                );
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(forward, "forward");
            if (dst == DeliveryEndpoint.TASK && forward.isEmpty()) {
                throw new IllegalArgumentException(
                        "TASK report forward must be non-empty"
                );
            }
            this.sourceId = sourceId;
            this.messageType = messageType;
            this.outcomeCode = outcomeCode;
            this.payload = payload;
            this.forward = forward;
        }

        public static DeliveryReport fromCommand(
                DeliveryCommand command,
                DeliveryEndpoint src,
                String sourceId,
                String outcomeCode,
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
                    source.messageType(),
                    outcomeCode,
                    payload,
                    source.forward()
            );
        }

        public static DeliveryReport create(
                DeliveryEndpoint src,
                String sourceId,
                DeliveryEndpoint dst,
                String messageType,
                String outcomeCode,
                String payload,
                String forward
        ) {
            return new DeliveryReport(
                    src,
                    sourceId,
                    dst,
                    messageType,
                    outcomeCode,
                    payload,
                    forward
            );
        }

        static DeliveryReport restore(
                DeliveryEndpoint src,
                String sourceId,
                DeliveryEndpoint dst,
                String messageType,
                String outcomeCode,
                String payload,
                String forward
        ) {
            return new DeliveryReport(
                    src,
                    sourceId,
                    dst,
                    messageType,
                    outcomeCode,
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

        public String outcomeCode() {
            return outcomeCode;
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
                    && outcomeCode.equals(other.outcomeCode)
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
                    outcomeCode,
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
                    + ", outcomeCode=" + outcomeCode
                    + ", payload=<opaque>, forward=<opaque>]";
        }
    }

    public static DeliveryReportOutcomeClass classifyDeliveryReportOutcomeCode(
            String outcomeCode
    ) {
        if (SUCCESS_OUTCOME_CODE.equals(outcomeCode)) {
            return DeliveryReportOutcomeClass.SUCCESS;
        }
        if (outcomeCode == null || outcomeCode.isBlank()) {
            return null;
        }
        if (outcomeCode.charAt(0) == '3') {
            return DeliveryReportOutcomeClass.WORKER_FAILURE;
        }
        return DeliveryReportOutcomeClass.ADAPTER_REJECTION;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }

}
