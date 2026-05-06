package com.xa.mass.runtime.redis.queue;

/**
 * Lua script holder for Redis-backed keyed queue primitives.
 *
 * <p>This type exists to keep queue script text stable and colocated with the
 * Redis queue implementation. Script execution wiring will be added by the
 * concrete store implementation.
 */
public final class RedisKeyedQueueScripts {

    private static final String OFFER_SCRIPT = """
            -- TODO: atomically enforce global/per-key admission, push queue
            -- payload, and update queue/global stats.
            return {"UNIMPLEMENTED"}
            """;

    private static final String DRAIN_SCRIPT = """
            -- TODO: atomically pop up to maxItems, update stats, and remove
            -- empty queues from the active queue set.
            return {}
            """;

    public String offerScript() {
        return OFFER_SCRIPT;
    }

    public String drainScript() {
        return DRAIN_SCRIPT;
    }
}
