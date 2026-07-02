package com.xa.mass.task.runtime.redis;

final class TaskRuntimeRedisKeyspaceV1 {

    private final String namespace;
    private final TaskRuntimeRedisKeyCodecV1 codec;

    TaskRuntimeRedisKeyspaceV1(String namespace, TaskRuntimeRedisKeyCodecV1 codec) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        this.namespace = namespace;
        this.codec = codec == null ? new TaskRuntimeRedisKeyCodecV1() : codec;
    }

    String lanesKey() {
        return namespace + ":lanes";
    }

    String taskScoreKey(String laneKey) {
        return namespace + ":task:score:" + codec.encodeSegment(laneKey);
    }

    String taskMetaKey(String taskId) {
        return namespace + ":task:" + codec.encodeSegment(taskId) + ":meta";
    }

    String taskBacklogKey(String taskId) {
        return namespace + ":task:" + codec.encodeSegment(taskId) + ":backlog";
    }

    String taskRetryScoreKey(String taskId) {
        return namespace + ":task:" + codec.encodeSegment(taskId) + ":retry:score";
    }

    String taskRetryItemKey(String taskId) {
        return namespace + ":task:" + codec.encodeSegment(taskId) + ":retry:item";
    }

    String taskRuntimeStateKey(String taskId) {
        return namespace + ":task:" + codec.encodeSegment(taskId) + ":rt";
    }

    String taskResultKey(String taskId) {
        return namespace + ":task:" + codec.encodeSegment(taskId) + ":result";
    }
}
