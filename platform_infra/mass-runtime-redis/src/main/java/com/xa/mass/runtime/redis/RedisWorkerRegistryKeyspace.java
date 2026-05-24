package com.xa.mass.runtime.redis;

import java.util.Objects;

/**
 * Redis keyspace for the group-partitioned WorkerRegistry implementation.
 */
public final class RedisWorkerRegistryKeyspace {

    public static final String DEFAULT_NAMESPACE = RedisTaskWorkKeyspace.DEFAULT_NAMESPACE + ":worker";
    static final String NODE_ROUTE_SEPARATOR = "\u001F";

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

    public String heartbeatDeadlinesZset() {
        return namespaced("heartbeat:deadlines");
    }

    public String exclusiveLeasesSet() {
        return namespaced("exclusive-leases");
    }

    public String groupSlotsHash(String groupId) {
        return groupPrefix(groupId) + ":slots";
    }

    public String groupRouteBucket(String groupId, String routeBucketKey) {
        return groupPrefix(groupId) + ":route:" + requireToken(routeBucketKey, "routeBucketKey") + ":workers";
    }

    public String groupRoutesSet(String groupId) {
        return groupPrefix(groupId) + ":routes";
    }

    public String nodeRouteBucket(String groupId, String adapterNodeId, String routeBucketKey) {
        return groupPrefix(groupId)
                + ":node:" + requireToken(adapterNodeId, "adapterNodeId")
                + ":route:" + requireToken(routeBucketKey, "routeBucketKey")
                + ":workers";
    }

    public String groupNodeRoutesSet(String groupId) {
        return groupPrefix(groupId) + ":node-routes";
    }

    public String taskActiveWorkersSet(String taskId) {
        return taskPrefix(taskId) + ":active-workers";
    }

    public String taskWorkerActiveCountsHash(String taskId) {
        return taskPrefix(taskId) + ":worker-active-count";
    }

    public String heartbeatMember(String groupId, String workerId) {
        return requireToken(groupId, "groupId") + NODE_ROUTE_SEPARATOR + requireToken(workerId, "workerId");
    }

    public String nodeRouteMember(String adapterNodeId, String routeBucketKey) {
        return requireToken(adapterNodeId, "adapterNodeId")
                + NODE_ROUTE_SEPARATOR
                + requireToken(routeBucketKey, "routeBucketKey");
    }

    public HeartbeatMember parseHeartbeatMember(String member) {
        String[] parts = splitPair(member, "heartbeat member");
        return new HeartbeatMember(parts[0], parts[1]);
    }

    public NodeRouteMember parseNodeRouteMember(String member) {
        String[] parts = splitPair(member, "node route member");
        return new NodeRouteMember(parts[0], parts[1]);
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
        int separator = token.indexOf(NODE_ROUTE_SEPARATOR);
        if (separator <= 0 || separator == token.length() - NODE_ROUTE_SEPARATOR.length()) {
            throw new IllegalArgumentException(fieldName + " is malformed");
        }
        return new String[]{
                token.substring(0, separator),
                token.substring(separator + NODE_ROUTE_SEPARATOR.length())
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

    public record HeartbeatMember(String groupId, String workerId) {
    }

    public record NodeRouteMember(String adapterNodeId, String routeBucketKey) {
    }
}
