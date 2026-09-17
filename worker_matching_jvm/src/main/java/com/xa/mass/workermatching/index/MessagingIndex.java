package com.xa.mass.workermatching.index;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.function.Supplier;

/** Facts-derived qualification resource; independent of held candidate inventory. */
public final class MessagingIndex extends PartitionedZsetIndex {
    public MessagingIndex(Supplier<RedisCommands<String, String>> commands, RedisKeyspace keyspace) {
        super(commands, keyspace, "messaging");
    }
    public static IndexMutation mutation() {
        return new IndexMutation("messaging",ZsetProjection.prepare( """
            if w['messaging.enabled'] ~= 'true' then return -1, {} end
            local parts = {}
            if type(w.phone) == 'string' and w.phone ~= '' then parts[1]='phone:'..w.phone end
            return country(w.country), parts
            """));
    }
}
