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
            local queueKey = KEYS[1]
            local metaKey = KEYS[2]
            local activeQueuesKey = KEYS[3]
            local globalStatsKey = KEYS[4]
            local payload = ARGV[1]
            local createdAtEpochMillis = ARGV[2]
            local maxItemsPerKey = tonumber(ARGV[3])
            local maxQueuedItems = tonumber(ARGV[4])
            local encodedKeyPart = ARGV[5]

            if (not queueKey) or (not payload) or (not createdAtEpochMillis) or
               (not maxItemsPerKey) or (not maxQueuedItems) or (not encodedKeyPart) then
                redis.call('HINCRBY', globalStatsKey, 'invalidItems', 1)
                return {'INVALID', 'key and entry must not be null'}
            end

            if maxItemsPerKey <= 0 then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_KEY', 'queue capacity is exhausted'}
            end

            local queueLength = redis.call('LLEN', queueKey)
            if queueLength >= maxItemsPerKey then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_KEY', 'queue is full'}
            end

            local queuedItems = tonumber(redis.call('HGET', globalStatsKey, 'queuedItems') or '0')
            if queuedItems >= maxQueuedItems then
                redis.call('HINCRBY', globalStatsKey, 'backpressureRejectedItems', 1)
                redis.call('HINCRBY', metaKey, 'backpressureRejectedItems', 1)
                return {'BACKPRESSURE_GLOBAL', 'runtime backlog is full'}
            end

            redis.call('RPUSH', queueKey, payload)
            redis.call('HINCRBY', globalStatsKey, 'queuedItems', 1)
            redis.call('HINCRBY', globalStatsKey, 'enqueuedItems', 1)

            if queueLength == 0 then
                redis.call('SADD', activeQueuesKey, encodedKeyPart)
                redis.call('HSET', metaKey, 'oldestCreatedAtEpochMillis', createdAtEpochMillis)
            end

            return {'ENQUEUED'}
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
