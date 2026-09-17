package com.xa.mass.workermatching.index;

import com.xa.mass.kernel.redis.RedisKeyspace;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Fixed, trusted Facts-write assembly: prepare validates before returning the write operation. */
public record IndexMutation(String namespace, String prepareLua) {
    public IndexMutation {
        if (namespace == null || !namespace.matches("[A-Za-z0-9_-]+")
                || prepareLua == null || prepareLua.isBlank()) {
            throw new IllegalArgumentException("invalid Rule index mutation");
        }
    }

    public static String base(RedisKeyspace keyspace, String group) {
        return keyspace.base() + ":matching:worker:index:"
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(group.getBytes(StandardCharsets.UTF_8));
    }
}
