package com.xa.mass.workerdelivery.protocol;

import java.util.Objects;
import java.util.UUID;

public final class WorkerDeliveryProtocol {

    public static final String SYSTEM_POLLING_ENDPOINT_MANAGER_ID =
            "system-polling";
    private static final String SUCCESS_OUTCOME_CODE = "200";

    private WorkerDeliveryProtocol() {
    }

    public enum WorkerMessageType {
        TASK_ITEM
    }

    public enum WorkerConnectionMessageType {
        TASK_ITEM_COMMAND,
        TASK_ITEM_RESULT
    }

    public interface WorkerConnectionMessage {

        WorkerConnectionMessageType messageType();
    }

    public static final class TaskItemCommandMessage
            implements WorkerConnectionMessage {

        private final WorkerCommandEnvelope command;

        public TaskItemCommandMessage(WorkerCommandEnvelope command) {
            if (command == null) {
                throw new IllegalArgumentException(
                        "command must be present"
                );
            }
            this.command = command;
        }

        public WorkerCommandEnvelope command() {
            return command;
        }

        @Override
        public WorkerConnectionMessageType messageType() {
            return WorkerConnectionMessageType.TASK_ITEM_COMMAND;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof TaskItemCommandMessage)) {
                return false;
            }
            TaskItemCommandMessage other = (TaskItemCommandMessage) value;
            return command.equals(other.command);
        }

        @Override
        public int hashCode() {
            return command.hashCode();
        }

        @Override
        public String toString() {
            return "TaskItemCommandMessage[command=" + command + "]";
        }
    }

    public static final class TaskItemResultMessage
            implements WorkerConnectionMessage {

        private final SeedResult result;

        public TaskItemResultMessage(SeedResult result) {
            if (result == null) {
                throw new IllegalArgumentException(
                        "result must be present"
                );
            }
            this.result = result;
        }

        public SeedResult result() {
            return result;
        }

        @Override
        public WorkerConnectionMessageType messageType() {
            return WorkerConnectionMessageType.TASK_ITEM_RESULT;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof TaskItemResultMessage)) {
                return false;
            }
            TaskItemResultMessage other = (TaskItemResultMessage) value;
            return result.equals(other.result);
        }

        @Override
        public int hashCode() {
            return result.hashCode();
        }

        @Override
        public String toString() {
            return "TaskItemResultMessage[result=" + result + "]";
        }
    }

    public enum SeedResultOutcomeClass {
        SUCCESS,
        WORKER_FAILURE,
        ADAPTER_REJECTION
    }

    public static final class WorkerConnectionBind {

        private final String workerId;

        public WorkerConnectionBind(String workerId) {
            requireNonBlank(workerId, "workerId");
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
            return workerId.hashCode();
        }

        @Override
        public String toString() {
            return "WorkerConnectionBind[workerId=" + workerId + "]";
        }
    }

    public static final class DeliverSeed {

        private final String workerId;
        private final String opaqueDeliveryItem;
        private final String opaqueResultContext;

        public DeliverSeed(
                String workerId,
                String opaqueDeliveryItem,
                String opaqueResultContext
        ) {
            requireNonBlank(workerId, "workerId");
            requireNonBlank(opaqueDeliveryItem, "opaqueDeliveryItem");
            requireNonBlank(opaqueResultContext, "opaqueResultContext");
            this.workerId = workerId;
            this.opaqueDeliveryItem = opaqueDeliveryItem;
            this.opaqueResultContext = opaqueResultContext;
        }

        public String workerId() {
            return workerId;
        }

        public String opaqueDeliveryItem() {
            return opaqueDeliveryItem;
        }

        public String opaqueResultContext() {
            return opaqueResultContext;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof DeliverSeed)) {
                return false;
            }
            DeliverSeed other = (DeliverSeed) value;
            return workerId.equals(other.workerId)
                    && opaqueDeliveryItem.equals(other.opaqueDeliveryItem)
                    && opaqueResultContext.equals(other.opaqueResultContext);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    workerId,
                    opaqueDeliveryItem,
                    opaqueResultContext
            );
        }

        @Override
        public String toString() {
            return "DeliverSeed[workerId=" + workerId
                    + ", opaqueDeliveryItem=" + opaqueDeliveryItem
                    + ", opaqueResultContext=" + opaqueResultContext + "]";
        }
    }

    public static final class WorkerCommandEnvelope {

        private final String commandId;
        private final WorkerMessageType messageType;
        private final long executeBeforeMillis;
        private final String opaqueItem;

        public WorkerCommandEnvelope(
                String commandId,
                WorkerMessageType messageType,
                long executeBeforeMillis,
                String opaqueItem
        ) {
            requireCanonicalUuid(commandId);
            if (messageType == null) {
                throw new IllegalArgumentException(
                        "messageType must be present"
                );
            }
            if (executeBeforeMillis <= 0) {
                throw new IllegalArgumentException(
                        "executeBeforeMillis must be positive"
                );
            }
            requireNonBlank(opaqueItem, "opaqueItem");
            this.commandId = commandId;
            this.messageType = messageType;
            this.executeBeforeMillis = executeBeforeMillis;
            this.opaqueItem = opaqueItem;
        }

        public String commandId() {
            return commandId;
        }

        public WorkerMessageType messageType() {
            return messageType;
        }

        public long executeBeforeMillis() {
            return executeBeforeMillis;
        }

        public String opaqueItem() {
            return opaqueItem;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof WorkerCommandEnvelope)) {
                return false;
            }
            WorkerCommandEnvelope other = (WorkerCommandEnvelope) value;
            return executeBeforeMillis == other.executeBeforeMillis
                    && commandId.equals(other.commandId)
                    && messageType == other.messageType
                    && opaqueItem.equals(other.opaqueItem);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    commandId,
                    messageType,
                    executeBeforeMillis,
                    opaqueItem
            );
        }

        @Override
        public String toString() {
            return "WorkerCommandEnvelope[commandId=" + commandId
                    + ", messageType=" + messageType
                    + ", executeBeforeMillis=" + executeBeforeMillis
                    + ", opaqueItem=" + opaqueItem + "]";
        }
    }

    public static final class SeedResult {

        private final String commandId;
        private final String opaqueResultContext;
        private final String outcomeCode;
        private final String opaqueResultPayload;

        public SeedResult(
                String commandId,
                String opaqueResultContext,
                String outcomeCode,
                String opaqueResultPayload
        ) {
            requireCanonicalUuid(commandId);
            requireNonBlank(opaqueResultContext, "opaqueResultContext");
            if (classifyOutcomeCode(outcomeCode) == null) {
                throw new IllegalArgumentException(
                        "outcomeCode must be 200, 1xxx, or 3xxx"
                );
            }
            if (SUCCESS_OUTCOME_CODE.equals(outcomeCode)
                    && opaqueResultPayload == null) {
                throw new IllegalArgumentException(
                        "Successful result must carry an opaque payload"
                );
            }
            if (opaqueResultPayload != null
                    && opaqueResultPayload.isEmpty()) {
                throw new IllegalArgumentException(
                        "opaqueResultPayload must be non-empty when present"
                );
            }
            this.commandId = commandId;
            this.opaqueResultContext = opaqueResultContext;
            this.outcomeCode = outcomeCode;
            this.opaqueResultPayload = opaqueResultPayload;
        }

        public String commandId() {
            return commandId;
        }

        public String opaqueResultContext() {
            return opaqueResultContext;
        }

        public String outcomeCode() {
            return outcomeCode;
        }

        public String opaqueResultPayload() {
            return opaqueResultPayload;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (!(value instanceof SeedResult)) {
                return false;
            }
            SeedResult other = (SeedResult) value;
            return commandId.equals(other.commandId)
                    && opaqueResultContext.equals(other.opaqueResultContext)
                    && outcomeCode.equals(other.outcomeCode)
                    && Objects.equals(
                            opaqueResultPayload,
                            other.opaqueResultPayload
                    );
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    commandId,
                    opaqueResultContext,
                    outcomeCode,
                    opaqueResultPayload
            );
        }

        @Override
        public String toString() {
            return "SeedResult[commandId=" + commandId
                    + ", opaqueResultContext=" + opaqueResultContext
                    + ", outcomeCode=" + outcomeCode
                    + ", opaqueResultPayload=" + opaqueResultPayload + "]";
        }
    }

    public static SeedResultOutcomeClass classifyOutcomeCode(
            String outcomeCode
    ) {
        if (SUCCESS_OUTCOME_CODE.equals(outcomeCode)) {
            return SeedResultOutcomeClass.SUCCESS;
        }
        if (outcomeCode == null
                || outcomeCode.length() != 4
                || !isDecimal(outcomeCode)) {
            return null;
        }
        char outcomeClass = outcomeCode.charAt(0);
        if (outcomeClass == '1') {
            return SeedResultOutcomeClass.WORKER_FAILURE;
        }
        if (outcomeClass == '3') {
            return SeedResultOutcomeClass.ADAPTER_REJECTION;
        }
        return null;
    }

    private static void requireCanonicalUuid(String value) {
        requireNonBlank(value, "commandId");
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "commandId must be a canonical UUID",
                    error
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private static boolean isDecimal(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
