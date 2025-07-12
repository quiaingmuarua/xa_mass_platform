package com.xa.mass.base.channel.queue;

public enum QueueProviderType {
    IN_MEMORY("memory"),
    REDIS("redis"),
    IN_MEMORY_STREAM("memory-stream"),
    REDIS_STREAM("redis-stream"),
    DATABASE("database"),
    KAFKA("kafka"),
    RABBITMQ("rabbitmq");

    private final String typeName;

    QueueProviderType(String typeName) {
        this.typeName = typeName;
    }

    @Override
    public String toString() {
        return typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    public static QueueProviderType fromString(String typeName) {
        for (QueueProviderType t : values()) {
            if (t.typeName.equalsIgnoreCase(typeName)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown queue provider type: " + typeName);
    }
} 