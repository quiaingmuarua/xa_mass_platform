package com.xa.mass.workerdelivery.protocol;

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

    public sealed interface WorkerConnectionMessage
            permits TaskItemCommandMessage, TaskItemResultMessage {

        WorkerConnectionMessageType messageType();
    }

    public record TaskItemCommandMessage(
            WorkerCommandEnvelope command
    ) implements WorkerConnectionMessage {

        public TaskItemCommandMessage {
            if (command == null) {
                throw new IllegalArgumentException(
                        "command must be present"
                );
            }
        }

        @Override
        public WorkerConnectionMessageType messageType() {
            return WorkerConnectionMessageType.TASK_ITEM_COMMAND;
        }
    }

    public record TaskItemResultMessage(
            SeedResult result
    ) implements WorkerConnectionMessage {

        public TaskItemResultMessage {
            if (result == null) {
                throw new IllegalArgumentException(
                        "result must be present"
                );
            }
        }

        @Override
        public WorkerConnectionMessageType messageType() {
            return WorkerConnectionMessageType.TASK_ITEM_RESULT;
        }
    }

    public enum SeedResultOutcomeClass {
        SUCCESS,
        WORKER_FAILURE,
        ADAPTER_REJECTION
    }

    public record DeliverSeed(
            String workerId,
            String opaqueDeliveryItem,
            String opaqueResultContext
    ) {
        public DeliverSeed {
            requireNonBlank(workerId, "workerId");
            requireNonBlank(opaqueDeliveryItem, "opaqueDeliveryItem");
            requireNonBlank(opaqueResultContext, "opaqueResultContext");
        }
    }

    public record WorkerCommandEnvelope(
            String commandId,
            WorkerMessageType messageType,
            long executeBeforeMillis,
            String opaqueItem
    ) {
        public WorkerCommandEnvelope {
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
        }
    }

    public record SeedResult(
            String commandId,
            String opaqueResultContext,
            String outcomeCode,
            String opaqueResultPayload
    ) {
        public SeedResult {
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
        return switch (outcomeCode.charAt(0)) {
            case '1' -> SeedResultOutcomeClass.WORKER_FAILURE;
            case '3' -> SeedResultOutcomeClass.ADAPTER_REJECTION;
            default -> null;
        };
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
        if (value == null || value.isBlank()) {
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
