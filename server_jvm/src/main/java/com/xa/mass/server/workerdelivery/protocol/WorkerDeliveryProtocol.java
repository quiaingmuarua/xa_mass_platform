package com.xa.mass.server.workerdelivery.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class WorkerDeliveryProtocol {

    public static final String SYSTEM_POLLING_ENDPOINT_MANAGER_ID =
            "system-polling";
    public static final String SUCCESS_OUTCOME_CODE = "200";

    private WorkerDeliveryProtocol() {
    }

    public enum WorkerMessageType {
        TASK_ITEM
    }

    public enum SeedResultOutcomeClass {
        SUCCESS("success"),
        WORKER_FAILURE("worker-failure"),
        ADAPTER_REJECTION("adapter-rejection");

        private final String redisKeySuffix;

        SeedResultOutcomeClass(String redisKeySuffix) {
            this.redisKeySuffix = redisKeySuffix;
        }

        public String redisKeySuffix() {
            return redisKeySuffix;
        }
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

    public record WorkerCommandPage(
            Map<String, WorkerCommandEnvelope> workerCommandsByWorkerId,
            String nextCursor
    ) {
        public WorkerCommandPage {
            if (workerCommandsByWorkerId == null) {
                throw new IllegalArgumentException(
                        "workerCommandsByWorkerId must be present"
                );
            }
            var commands = new LinkedHashMap<>(workerCommandsByWorkerId);
            commands.forEach((workerId, command) -> {
                requireNonBlank(workerId, "workerId");
                if (command == null) {
                    throw new IllegalArgumentException(
                            "Worker command must be present"
                    );
                }
            });
            workerCommandsByWorkerId = Collections.unmodifiableMap(commands);
            if (nextCursor != null && !isDecimal(nextCursor)) {
                throw new IllegalArgumentException(
                        "nextCursor must be a Redis cursor or null"
                );
            }
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
            if (opaqueResultPayload != null && opaqueResultPayload.isEmpty()) {
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

    public static void requireCanonicalUuid(String value) {
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

    public static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    public static boolean isDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
