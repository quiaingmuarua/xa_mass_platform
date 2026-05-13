package com.xa.mass.runtime.redis;

import java.util.Objects;

public final class RedisTaskResultKeyspace {

    public static final String DEFAULT_NAMESPACE = RedisTaskWorkKeyspace.DEFAULT_NAMESPACE + ":result";

    private final String namespace;

    public RedisTaskResultKeyspace() {
        this(DEFAULT_NAMESPACE);
    }

    public RedisTaskResultKeyspace(String namespace) {
        this.namespace = normalizeNamespace(namespace);
    }

    public String namespace() {
        return namespace;
    }

    public String allStagesZset() {
        return namespaced("stages");
    }

    public String stagedDraft(String stageId) {
        return namespaced("stage:" + requireToken(stageId, "stageId"));
    }

    public String taskStagesSet(String taskId) {
        return taskPrefix(taskId) + ":stages";
    }

    public String taskSeqCounter(String taskId) {
        return taskPrefix(taskId) + ":seq";
    }

    public String taskVisibleZset(String taskId) {
        return taskPrefix(taskId) + ":visible";
    }

    public String taskVisibleRow(String taskId, String messageId) {
        return taskPrefix(taskId) + ":visible:" + requireToken(messageId, "messageId");
    }

    public String logicalFinalBarrier(String taskId, String messageId, long seq) {
        return taskPrefix(taskId) + ":barrier:logical-final:" + requireToken(messageId, "messageId") + ":" + seq;
    }

    public String progressBarrier(String taskId, String messageId, long seq) {
        return taskPrefix(taskId) + ":barrier:progress:" + requireToken(messageId, "messageId") + ":" + seq;
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
}
