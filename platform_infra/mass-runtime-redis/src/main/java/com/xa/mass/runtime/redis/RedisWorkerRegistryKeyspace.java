package com.xa.mass.runtime.redis;

import java.util.Objects;

/**
 * Redis keyspace for the group-partitioned WorkerRegistry implementation.
 */
public final class RedisWorkerRegistryKeyspace {

    public static final String DEFAULT_NAMESPACE = RedisTaskWorkKeyspace.DEFAULT_NAMESPACE + ":worker";
    private final String namespace;

    public RedisWorkerRegistryKeyspace() {
        this(DEFAULT_NAMESPACE);
    }

    public RedisWorkerRegistryKeyspace(String namespace) {
        this.namespace = normalizeNamespace(namespace);
    }

    public String namespace() {
        return namespace;
    }

    public String workerGroupHash() {
        return namespaced("worker:group");
    }

    public String workerGroupsSet() {
        return namespaced("groups");
    }

    public String groupHeartbeatDeadlinesZset(String groupId) {
        return groupPrefix(groupId) + ":heartbeat:0";
    }

    public String exclusiveLeasesSet() {
        return namespaced("exclusive-leases");
    }

    public String groupSlotsHash(String groupId) {
        return groupPrefix(groupId) + ":slots";
    }

    public String groupCandidateBucket(String groupId, String candidateBucketKey) {
        return groupPrefix(groupId) + ":bucket:" + requireToken(candidateBucketKey, "candidateBucketKey") + ":workers";
    }

    public String groupCandidateBucketLifecycleDeadlinesZset(String groupId, String candidateBucketKey) {
        return candidateBucketLifecycleDeadlinesZset(groupCandidateBucket(groupId, candidateBucketKey));
    }

    public String groupCandidateBucketsSet(String groupId) {
        return groupPrefix(groupId) + ":buckets";
    }

    public String candidateBucketLifecycleDeadlinesZset(String candidateBucketStorageKey) {
        return requireToken(candidateBucketStorageKey, "candidateBucketStorageKey") + ":slot-lifecycle-deadlines";
    }

    public String groupBucketMembershipHash(String groupId) {
        return groupPrefix(groupId) + ":bucket-membership";
    }

    public String taskWorkerActiveCountsHash(String taskId) {
        return taskPrefix(taskId) + ":worker-active-count";
    }

    private String groupPrefix(String groupId) {
        return namespaced("group:" + requireToken(groupId, "groupId"));
    }

    private String taskPrefix(String taskId) {
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
        return value.trim();
    }

}
