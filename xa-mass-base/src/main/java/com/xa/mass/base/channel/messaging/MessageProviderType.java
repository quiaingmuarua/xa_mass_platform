package com.xa.mass.base.channel.messaging;

public enum MessageProviderType {
    IN_MEMORY("memory"),
    REDIS("redis"),
    DATABASE("database"),
    KAFKA("kafka"),
    RABBITMQ("rabbitmq"),
    CUSTOM("custom");

    private final String typeName;

    MessageProviderType(String typeName) {
        this.typeName = typeName;
    }

    @Override
    public String toString() {
        return typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    public static MessageProviderType fromString(String typeName) {
        for (MessageProviderType t : values()) {
            if (t.typeName.equalsIgnoreCase(typeName)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown queue provider type: " + typeName);
    }
} 