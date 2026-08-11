package com.xa.mass.workerdelivery.protocol;

import java.util.Objects;
import java.util.UUID;

public final class WorkerDeliveryProtocol {

    public static final String SYSTEM_POLLING_ENDPOINT_MANAGER_ID =
            "system-polling";
    private static final String SUCCESS_OUTCOME_CODE = "200";

    private WorkerDeliveryProtocol() {
    }

    public enum WorkerMessageEndpoint {
        TASK("TASK"),
        SYSTEM("SYSTEM"),
        ADAPTER("ADAPTER"),
        WORKER("WORKER");

        private final String wireValue;

        WorkerMessageEndpoint(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static WorkerMessageEndpoint fromWire(String value) {
            for (WorkerMessageEndpoint endpoint : values()) {
                if (endpoint.wireValue.equals(value)) {
                    return endpoint;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown Worker message endpoint: " + value
            );
        }
    }

    public enum WorkerResultOutcomeClass {
        SUCCESS,
        WORKER_FAILURE,
        ADAPTER_REJECTION
    }

    public static final class WorkerConnectionBind {

        private final String workerId;

        public WorkerConnectionBind(String workerId) {
            requireCanonicalUuid(workerId, "workerId");
            this.workerId = workerId;
        }

        public String workerId() {
            return workerId;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof WorkerConnectionBind)) {
                return false;
            }
            WorkerConnectionBind other = (WorkerConnectionBind) value;
            return workerId.equals(other.workerId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workerId);
        }

        @Override
        public String toString() {
            return "WorkerConnectionBind[workerId=" + workerId + "]";
        }
    }

    public static final class WorkerCommand {

        private final String messageId;
        private final WorkerMessageEndpoint src;
        private final WorkerMessageEndpoint dst;
        private final String messageType;
        private final long executeBeforeMillis;
        private final String payload;
        private final String forward;

        public WorkerCommand(
                String messageId,
                WorkerMessageEndpoint src,
                WorkerMessageEndpoint dst,
                String messageType,
                long executeBeforeMillis,
                String payload,
                String forward
        ) {
            requireCanonicalUuid(messageId, "messageId");
            if (src == null || src == WorkerMessageEndpoint.WORKER) {
                throw new IllegalArgumentException(
                        "Worker command src must be TASK, SYSTEM, or ADAPTER"
                );
            }
            if (dst != WorkerMessageEndpoint.WORKER) {
                throw new IllegalArgumentException(
                        "Worker command dst must be WORKER"
                );
            }
            requireNonBlank(messageType, "messageType");
            if (executeBeforeMillis <= 0) {
                throw new IllegalArgumentException(
                        "executeBeforeMillis must be positive"
                );
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(forward, "forward");
            if (src == WorkerMessageEndpoint.TASK && forward.isEmpty()) {
                throw new IllegalArgumentException(
                        "TASK command forward must be non-empty"
                );
            }
            this.messageId = messageId;
            this.src = src;
            this.dst = dst;
            this.messageType = messageType;
            this.executeBeforeMillis = executeBeforeMillis;
            this.payload = payload;
            this.forward = forward;
        }

        public String messageId() {
            return messageId;
        }

        public WorkerMessageEndpoint src() {
            return src;
        }

        public WorkerMessageEndpoint dst() {
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
            if (!(value instanceof WorkerCommand)) {
                return false;
            }
            WorkerCommand other = (WorkerCommand) value;
            return executeBeforeMillis == other.executeBeforeMillis
                    && messageId.equals(other.messageId)
                    && src == other.src
                    && dst == other.dst
                    && messageType.equals(other.messageType)
                    && payload.equals(other.payload)
                    && forward.equals(other.forward);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    messageId,
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
            return "WorkerCommand[messageId=" + messageId
                    + ", src=" + src
                    + ", dst=" + dst
                    + ", messageType=" + messageType
                    + ", executeBeforeMillis=" + executeBeforeMillis
                    + ", payload=<opaque>, forward=<opaque>]";
        }
    }

    public static final class WorkerResult {

        private final String messageId;
        private final WorkerMessageEndpoint dst;
        private final String messageType;
        private final String outcomeCode;
        private final String payload;
        private final String forward;

        public WorkerResult(
                String messageId,
                WorkerMessageEndpoint dst,
                String messageType,
                String outcomeCode,
                String payload,
                String forward
        ) {
            requireCanonicalUuid(messageId, "messageId");
            if (dst == null || dst == WorkerMessageEndpoint.WORKER) {
                throw new IllegalArgumentException(
                        "Worker result dst must be TASK, SYSTEM, or ADAPTER"
                );
            }
            requireNonBlank(messageType, "messageType");
            if (classifyWorkerResultOutcomeCode(outcomeCode) == null) {
                throw new IllegalArgumentException(
                        "outcomeCode must be non-blank"
                );
            }
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(forward, "forward");
            if (dst == WorkerMessageEndpoint.TASK && forward.isEmpty()) {
                throw new IllegalArgumentException(
                        "TASK result forward must be non-empty"
                );
            }
            this.messageId = messageId;
            this.dst = dst;
            this.messageType = messageType;
            this.outcomeCode = outcomeCode;
            this.payload = payload;
            this.forward = forward;
        }

        public static WorkerResult fromCommand(
                WorkerCommand command,
                String outcomeCode,
                String payload
        ) {
            WorkerCommand source = Objects.requireNonNull(
                    command,
                    "command"
            );
            return new WorkerResult(
                    source.messageId(),
                    source.src(),
                    source.messageType(),
                    outcomeCode,
                    payload,
                    source.forward()
            );
        }

        public String messageId() {
            return messageId;
        }

        public WorkerMessageEndpoint dst() {
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
            if (!(value instanceof WorkerResult)) {
                return false;
            }
            WorkerResult other = (WorkerResult) value;
            return messageId.equals(other.messageId)
                    && dst == other.dst
                    && messageType.equals(other.messageType)
                    && outcomeCode.equals(other.outcomeCode)
                    && payload.equals(other.payload)
                    && forward.equals(other.forward);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    messageId,
                    dst,
                    messageType,
                    outcomeCode,
                    payload,
                    forward
            );
        }

        @Override
        public String toString() {
            return "WorkerResult[messageId=" + messageId
                    + ", dst=" + dst
                    + ", messageType=" + messageType
                    + ", outcomeCode=" + outcomeCode
                    + ", payload=<opaque>, forward=<opaque>]";
        }
    }

    public static WorkerResultOutcomeClass classifyWorkerResultOutcomeCode(
            String outcomeCode
    ) {
        if (SUCCESS_OUTCOME_CODE.equals(outcomeCode)) {
            return WorkerResultOutcomeClass.SUCCESS;
        }
        if (outcomeCode == null || outcomeCode.isBlank()) {
            return null;
        }
        if (outcomeCode.charAt(0) == '3') {
            return WorkerResultOutcomeClass.WORKER_FAILURE;
        }
        return WorkerResultOutcomeClass.ADAPTER_REJECTION;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }

    private static void requireCanonicalUuid(
            String value,
            String name
    ) {
        requireNonBlank(value, name);
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    name + " must be a canonical UUID",
                    error
            );
        }
    }

}
