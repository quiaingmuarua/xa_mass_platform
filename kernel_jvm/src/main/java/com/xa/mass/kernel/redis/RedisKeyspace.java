package com.xa.mass.kernel.redis;

import java.util.regex.Pattern;

/** Validated XA Mass Redis root and runtime scope. */
public record RedisKeyspace(String scope) {

    public static final String ROOT = "xa_mass";
    private static final Pattern SCOPE_PATTERN = Pattern.compile(
            "(?:profile|test)_[a-z0-9_]+"
    );

    public RedisKeyspace {
        if (scope == null || !SCOPE_PATTERN.matcher(scope).matches()) {
            throw new IllegalArgumentException(
                    "Redis scope must match profile_[a-z0-9_]+ "
                            + "or test_[a-z0-9_]+"
            );
        }
    }

    public String base() {
        return ROOT + ":" + scope;
    }
}
