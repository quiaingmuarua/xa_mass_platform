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
            local queueValue = ARGV[1]
            local createdAtEpochMillis = ARGV[2]
            local maxItemsPerKey = tonumber(ARGV[3])
            local maxQueuedItems = tonumber(ARGV[4])
            local encodedKeyPart = ARGV[5]

            if (not queueKey) or (not queueValue) or (not createdAtEpochMillis) or
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

            redis.call('RPUSH', queueKey, queueValue)
            redis.call('HINCRBY', globalStatsKey, 'queuedItems', 1)
            redis.call('HINCRBY', globalStatsKey, 'enqueuedItems', 1)

            if queueLength == 0 then
                redis.call('SADD', activeQueuesKey, encodedKeyPart)
                redis.call('HSET', metaKey, 'oldestCreatedAtEpochMillis', createdAtEpochMillis)
            end

            return {'ENQUEUED'}
            """;

    private static final String DRAIN_SCRIPT = """
            local queueKey = KEYS[1]
            local metaKey = KEYS[2]
            local activeQueuesKey = KEYS[3]
            local globalStatsKey = KEYS[4]
            local maxItems = tonumber(ARGV[1])
            local encodedKeyPart = ARGV[2]

            if (not queueKey) or (not maxItems) or (not encodedKeyPart) then
                return {'INVALID', 'key and maxItems must not be null'}
            end

            if maxItems <= 0 then
                return {'INVALID', 'maxItems must be positive'}
            end

            local drained = {}
            local count = 0
            while count < maxItems do
                local value = redis.call('LPOP', queueKey)
                if not value then
                    break
                end
                count = count + 1
                drained[count] = value
            end

            if count == 0 then
                return {'EMPTY', '0'}
            end

            redis.call('HINCRBY', globalStatsKey, 'queuedItems', -count)
            redis.call('HINCRBY', globalStatsKey, 'drainedItems', count)

            local nextHead = redis.call('LINDEX', queueKey, 0)
            if nextHead then
                local delimiter = string.find(nextHead, '|', 1, true)
                if delimiter and delimiter > 1 then
                    local nextCreatedAt = string.sub(nextHead, 1, delimiter - 1)
                    redis.call('HSET', metaKey, 'oldestCreatedAtEpochMillis', nextCreatedAt)
                else
                    redis.call('HDEL', metaKey, 'oldestCreatedAtEpochMillis')
                end
            else
                redis.call('SREM', activeQueuesKey, encodedKeyPart)
                redis.call('DEL', metaKey)
            end

            local response = {'DRAINED', tostring(count)}
            for i = 1, count do
                response[#response + 1] = drained[i]
            end
            return response
            """;

    public String offerScript() {
        return OFFER_SCRIPT;
    }

    public String drainScript() {
        return DRAIN_SCRIPT;
    }
}
