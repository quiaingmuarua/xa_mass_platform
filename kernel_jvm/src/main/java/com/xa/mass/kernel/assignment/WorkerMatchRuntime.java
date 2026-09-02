package com.xa.mass.kernel.assignment;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded identity-only handoff between Kernel scheduling and Worker matching.
 */
public interface WorkerMatchRuntime {

    int MAX_BATCH_SIZE = 100;
    int MAX_MATCHED_WORKERS = 100;

    Map<String, DemandOfferStatus> offerTaskDemands(
            List<TaskRuleMatchDemand> demands
    );

    Map<String, TaskRuleMatchEvidence> takeTaskEvidence(
            List<String> taskIds
    );

    Map<ItemMatchKey, DemandOfferStatus> offerItemDemands(
            List<ItemRuleMatchDemand> demands
    );

    Map<ItemMatchKey, ItemRuleMatchEvidence> takeItemEvidence(
            List<ItemMatchKey> keys
    );

    enum DemandOfferStatus {
        OFFERED,
        ALREADY_PENDING,
        CAPACITY
    }

    record ItemMatchKey(String taskId, String messageId) {
        public ItemMatchKey {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(messageId, "messageId");
        }
    }

    record TaskRuleMatchDemand(
            String taskId,
            String workerGroupId,
            List<String> heldWorkerIds,
            long holdUntilMillis
    ) {
        public TaskRuleMatchDemand {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(workerGroupId, "workerGroupId");
            heldWorkerIds = immutableWorkerIds(
                    heldWorkerIds,
                    "heldWorkerIds"
            );
            requirePositiveHold(holdUntilMillis);
        }
    }

    record ItemRuleMatchDemand(
            ItemMatchKey key,
            String workerGroupId,
            List<String> heldWorkerIds,
            long holdUntilMillis
    ) {
        public ItemRuleMatchDemand {
            Objects.requireNonNull(key, "key");
            requireNonBlank(workerGroupId, "workerGroupId");
            heldWorkerIds = immutableWorkerIds(heldWorkerIds, "heldWorkerIds");
            requirePositiveHold(holdUntilMillis);
        }
    }

    record TaskRuleMatchEvidence(
            String taskId,
            String workerGroupId,
            List<String> matchedWorkerIds,
            long holdUntilMillis
    ) {
        public TaskRuleMatchEvidence {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(workerGroupId, "workerGroupId");
            matchedWorkerIds = immutableWorkerIds(
                    matchedWorkerIds,
                    "matchedWorkerIds"
            );
            requirePositiveHold(holdUntilMillis);
        }
    }

    record ItemRuleMatchEvidence(
            ItemMatchKey key,
            String workerGroupId,
            List<String> matchedWorkerIds,
            long holdUntilMillis
    ) {
        public ItemRuleMatchEvidence {
            Objects.requireNonNull(key, "key");
            requireNonBlank(workerGroupId, "workerGroupId");
            matchedWorkerIds = immutableWorkerIds(
                    matchedWorkerIds,
                    "matchedWorkerIds"
            );
            requirePositiveHold(holdUntilMillis);
        }
    }

    private static List<String> immutableWorkerIds(
            List<String> workerIds,
            String name
    ) {
        Objects.requireNonNull(workerIds, name);
        if (workerIds.size() > MAX_MATCHED_WORKERS) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + MAX_MATCHED_WORKERS
                            + " workers"
            );
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            requireNonBlank(workerId, "workerId");
            if (!unique.add(workerId)) {
                throw new IllegalArgumentException(
                        name + " must not contain duplicate workers"
                );
            }
        }
        return List.copyOf(unique);
    }

    private static void requirePositiveHold(long holdUntilMillis) {
        if (holdUntilMillis < 1) {
            throw new IllegalArgumentException(
                    "holdUntilMillis must be positive"
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

}
