package com.xa.mass.workersimulator.messaging;

/** Interpretation installed by this Host for a Group, never inferred from a message body. */
public enum MessageContentMode {
    TEXT("text"), LAB_JSON("lab-json");

    private final String value;
    MessageContentMode(String value) { this.value = value; }
    public String value() { return value; }
    public static MessageContentMode parse(String value) {
        for (var mode : values()) if (mode.value.equals(value)) return mode;
        throw new IllegalArgumentException("Unknown messageContentMode");
    }
}
