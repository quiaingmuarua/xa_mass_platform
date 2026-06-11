package com.xa.mass.runtime.redis;

import java.util.Objects;

/**
 * Redis keyspace for the group-partitioned WorkerRegistry implementation.
 */
public final class RedisWorkerRegistryKeyspace {

    public static final String DEFAULT_NAMESPACE = RedisTaskWorkKeyspace.DEFAULT_NAMESPACE + ":worker";
    static final String NODE_BUCKET_SEPARATOR = "\u001F";

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

    public String groupCandidateBucketsSet(String groupId) {
        return groupPrefix(groupId) + ":buckets";
    }

    public String nodeCandidateBucket(String groupId, String adapterNodeId, String candidateBucketKey) {
        return groupPrefix(groupId)
                + ":node:" + requireToken(adapterNodeId, "adapterNodeId")
                + ":bucket:" + requireToken(candidateBucketKey, "candidateBucketKey")
                + ":workers";
    }

    public String groupNodeCandidateBucketsSet(String groupId) {
        return groupPrefix(groupId) + ":node-buckets";
    }

    public String groupBucketMembershipHash(String groupId) {
        return groupPrefix(groupId) + ":bucket-membership";
    }

    public String taskWorkerActiveCountsHash(String taskId) {
        return taskPrefix(taskId) + ":worker-active-count";
    }

    public String nodeCandidateBucketMember(String adapterNodeId, String candidateBucketKey) {
        return requireToken(adapterNodeId, "adapterNodeId")
                + NODE_BUCKET_SEPARATOR
                + requireToken(candidateBucketKey, "candidateBucketKey");
    }

    public NodeCandidateBucketMember parseNodeCandidateBucketMember(String member) {
        String[] parts = splitPair(member, "node candidate bucket member");
        return new NodeCandidateBucketMember(parts[0], parts[1]);
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

    private static String[] splitPair(String value, String fieldName) {
        String token = requireToken(value, fieldName);
        int separator = token.indexOf(NODE_BUCKET_SEPARATOR);
        if (separator <= 0 || separator == token.length() - NODE_BUCKET_SEPARATOR.length()) {
            throw new IllegalArgumentException(fieldName + " is malformed");
        }
        return new String[]{
                token.substring(0, separator),
                token.substring(separator + NODE_BUCKET_SEPARATOR.length())
        };
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

    public record NodeCandidateBucketMember(String adapterNodeId, String candidateBucketKey) {
    }
}
