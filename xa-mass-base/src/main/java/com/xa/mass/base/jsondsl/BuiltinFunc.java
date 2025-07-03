package com.xa.mass.base.jsondsl;

public enum BuiltinFunc {
    CHOICE("$CHOICE"),
    RANGE("$RANGE"),
    UUID("$UUID"),
    RANDOM("$RANDOM"),
    JOIN("$JOIN"),
    CONTEXT("$CONTEXT"),
    NOW("$NOW"),
    TIME_RANGE("$TIME_RANGE");

    private final String key;
    BuiltinFunc(String key) { this.key = key; }
    public String key() { return key; }
    public static BuiltinFunc fromKey(String key) {
        for (BuiltinFunc f : values()) {
            if (f.key.equals(key)) return f;
        }
        return null;
    }
} 