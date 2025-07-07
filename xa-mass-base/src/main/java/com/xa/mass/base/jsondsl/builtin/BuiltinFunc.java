package com.xa.mass.base.jsondsl.builtin;

/**
 * 内置函数枚举，定义了所有支持的内置函数。
 *
 * 新标准提供更丰富的表达式引擎支持和内置函数扩展机制。
 */
public enum BuiltinFunc {
    CHOICE("$CHOICE", "choice"),
    RANGE("$RANGE", "range"),
    UUID("$UUID", "uuid"),
    RANDOM("$RANDOM", "random", "rand"),
    JOIN("$JOIN", "join"),
    CONTEXT("$CONTEXT", "context"),
    NOW("$NOW", "now"),
    TIME_RANGE("$TIME_RANGE", "timeRange", "timerange");

    private final String key;
    private final String[] aliases;

    BuiltinFunc(String key, String... aliases) {
        this.key = key;
        this.aliases = aliases;
    }

    public static BuiltinFunc fromKey(String key) {
        for (BuiltinFunc f : values()) {
            if (f.key.equals(key)) return f;
        }
        return null;
    }

    public String key() {
        return key;
    }

    public String[] aliases() {
        return aliases;
    }
} 