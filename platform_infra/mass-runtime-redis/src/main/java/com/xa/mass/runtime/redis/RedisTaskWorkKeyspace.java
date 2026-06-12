package com.xa.mass.runtime.redis;

import java.util.Objects;

/**
 * Redis keyspace owner for the future {@code TaskWorkRuntime} implementation.
 *
 * <p>This class fixes the key and member naming model early so the eventual
 * Redis runtime does not grow ad hoc indexes or scan-based recovery paths.
 * It is an internal infrastructure helper, not a repo-external contract.</p>
 */
public final class RedisTaskWorkKeyspace {

    public static final String DEFAULT_NAMESPACE = "xa:mass:runtime:v1";

    public static final String FIELD_EVENT_CODE = "eventCode";
    public static final String FIELD_PAYLOAD_JSON = "payloadJson";
    public static final String FIELD_PAYLOAD_REF = "payloadRef";
    public static final String FIELD_RETRY_COUNT = "retryCount";
    public static final String FIELD_MAX_RETRY_COUNT = "maxRetryCount";
    public static final String FIELD_SHARD_KEY = "shardKey";
    public static final String FIELD_NEXT_VISIBLE_AT_MILLIS = "nextVisibleAtMillis";
    public static final String FIELD_CREATED_AT_MILLIS = "createdAtMillis";

    public static final String FIELD_LEASE_TOKEN = "leaseToken";
    public static final String FIELD_WORKER_ID = "workerId";
    public static final String FIELD_WORKER_GROUP_ID = "workerGroupId";
    public static final String FIELD_BATCH_ID = "batchId";
    public static final String FIELD_LEASE_PAYLOAD_REF = "payloadRef";
    public static final String FIELD_LEASE_RETRY_COUNT = "retryCount";
    public static final String FIELD_LEASE_EXPIRE_AT_MILLIS = "leaseExpireAtMillis";
    public static final String FIELD_LEASED_AT_MILLIS = "leasedAtMillis";
    public static final String FIELD_FINAL_STATUS = "status";
    public static final String FIELD_FINAL_ERROR_CODE = "errorCode";
    public static final String FIELD_FINAL_RETRY_COUNT = "retryCount";
    public static final String FIELD_FINAL_COMPLETED_AT_MILLIS = "completedAtMillis";

    public static final String COUNTER_TOTAL_COUNT = "totalCount";
    public static final String COUNTER_READY_COUNT = "readyCount";
    public static final String COUNTER_INFLIGHT_COUNT = "inflightCount";
    public static final String COUNTER_DELAYED_COUNT = "delayedCount";
    public static final String COUNTER_SUCCESS_COUNT = "successCount";
    public static final String COUNTER_FAILED_COUNT = "failedCount";
    public static final String COUNTER_EXPIRED_COUNT = "expiredCount";

    public static final String COUNTER_ENQUEUED_ITEMS = "enqueuedItems";
    public static final String COUNTER_CLAIMED_ITEMS = "claimedItems";
    public static final String COUNTER_RESULT_APPLIED_ITEMS = "resultAppliedItems";
    public static final String COUNTER_BACKPRESSURE_REJECTED_ITEMS = "backpressureRejectedItems";
    public static final String COUNTER_DUPLICATE_RESULT_ITEMS = "duplicateResultItems";
    public static final String COUNTER_STALE_RESULT_ITEMS = "staleResultItems";
    public static final String COUNTER_EXPIRED_LEASE_ITEMS = "expiredLeaseItems";
    public static final String COUNTER_DISCARDED_ITEMS = "discardedItems";
    public static final String COUNTER_SHUTDOWN_CLEARED_ITEMS = "shutdownClearedItems";

    private final String namespace;

    public RedisTaskWorkKeyspace() {
        this(DEFAULT_NAMESPACE);
    }

    public RedisTaskWorkKeyspace(String namespace) {
        this.namespace = normalizeNamespace(namespace);
    }

    public String namespace() {
        return namespace;
    }

    public String readyTasksZset() {
        return namespaced("ready:tasks");
    }

    public String delayedWorkZset() {
        return namespaced("delayed:work");
    }

    public String leaseExpiryZset() {
        return namespaced("lease:expiry");
    }

    public String recentFinalReceiptsZset() {
        return namespaced("recent-final");
    }

    public String runtimeStatsHash() {
        return namespaced("stats");
    }

    public String taskRegistrySet() {
        return namespaced("tasks");
    }

    public String taskReadyQueue(String taskId) {
        return taskPrefix(taskId) + ":ready";
    }

    public String taskDelayedZset(String taskId) {
        return taskPrefix(taskId) + ":delayed";
    }

    public String taskWorkHash(String taskId, String messageId) {
        return taskPrefix(taskId) + ":work:" + requireToken(messageId, "messageId");
    }

    public String taskLeaseHash(String taskId, String messageId) {
        return taskPrefix(taskId) + ":lease:" + requireToken(messageId, "messageId");
    }

    public String taskActiveSet(String taskId) {
        return taskPrefix(taskId) + ":active";
    }

    public String taskMembersSet(String taskId) {
        return taskPrefix(taskId) + ":members";
    }

    public String taskRecentFinalReceiptSet(String taskId) {
        return taskPrefix(taskId) + ":recent-final";
    }

    public String taskRecentFinalReceiptHash(String taskId, String messageId) {
        return taskPrefix(taskId) + ":recent-final:" + requireToken(messageId, "messageId");
    }

    public String taskStatsHash(String taskId) {
        return taskPrefix(taskId) + ":stats";
    }

    public String workerActiveSet(String workerId) {
        return namespaced("worker:" + requireToken(workerId, "workerId") + ":active");
    }

    public String workMember(String taskId, String messageId) {
        String normalizedTaskId = requireToken(taskId, "taskId");
        return normalizedTaskId.length() + ":" + normalizedTaskId + requireToken(messageId, "messageId");
    }

    public WorkRef parseWorkMember(String member) {
        String value = requireToken(member, "member");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator >= value.length() - 1) {
            throw new IllegalArgumentException("invalid work member: " + member);
        }
        int taskIdLength;
        try {
            taskIdLength = Integer.parseInt(value.substring(0, separator));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid work member: " + member, e);
        }
        int taskIdStart = separator + 1;
        int taskIdEnd = taskIdStart + taskIdLength;
        if (taskIdLength <= 0 || taskIdEnd >= value.length() + 1 || taskIdEnd > value.length()) {
            throw new IllegalArgumentException("invalid work member: " + member);
        }
        return new WorkRef(
                value.substring(taskIdStart, taskIdEnd),
                value.substring(taskIdEnd)
        );
    }

    public String taskPrefix(String taskId) {
        return namespaced("task:" + requireToken(taskId, "taskId"));
    }

    private String namespaced(String suffix) {
        return namespace + ":" + suffix;
    }

    private static String normalizeNamespace(String namespace) {
        String value = requireToken(namespace, "namespace");
        while (value.endsWith(":")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return value;
    }

    private static String requireToken(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public record WorkRef(String taskId, String messageId) {
    }
}
