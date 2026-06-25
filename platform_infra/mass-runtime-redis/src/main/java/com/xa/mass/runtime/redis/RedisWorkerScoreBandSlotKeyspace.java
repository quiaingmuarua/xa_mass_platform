package com.xa.mass.runtime.redis;

import java.util.Objects;

/**
 * Redis keyspace for the score-band worker slot state machine.
 */
public final class RedisWorkerScoreBandSlotKeyspace {

    public static final String DEFAULT_NAMESPACE = RedisWorkerRegistryKeyspace.DEFAULT_NAMESPACE + ":score-band";

    private final String namespace;

    public RedisWorkerScoreBandSlotKeyspace() {
        this(DEFAULT_NAMESPACE);
    }

    public RedisWorkerScoreBandSlotKeyspace(String namespace) {
        this.namespace = normalizeNamespace(namespace);
    }

    public String namespace() {
        return namespace;
    }

    public String scoreZset(String homeBucketId) {
        return namespaced("score:" + requireToken(homeBucketId, "homeBucketId"));
    }

    public String metadataHash(String homeBucketId) {
        return namespaced("meta:" + requireToken(homeBucketId, "homeBucketId"));
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
