package com.xa.mass.task.runtime.redis;

final class RedisScoreBandTaskRuntimeScripts {

    private RedisScoreBandTaskRuntimeScripts() {
    }

    static final String CLAIM_BACKLOG = """
            local function encode_segment(value)
                local text = tostring(value)
                local result = {}
                for index = 1, #text do
                    local byte = string.byte(text, index)
                    if (byte >= 65 and byte <= 90)
                            or (byte >= 97 and byte <= 122)
                            or (byte >= 48 and byte <= 57)
                            or byte == 46
                            or byte == 95
                            or byte == 45 then
                        result[#result + 1] = string.char(byte)
                    else
                        result[#result + 1] = string.format("%%%02X", byte)
                    end
                end
                return table.concat(result)
            end

            local taskId = ARGV[1]
            local laneKey = ARGV[2]
            local runtimeEpoch = tonumber(ARGV[3]) or 0
            local fenceToken = ARGV[4]
            local observedScore = tonumber(ARGV[5]) or 0
            local dispatchScoreFloor = tonumber(ARGV[6]) or 1000000000000
            local nowMillis = tonumber(ARGV[7]) or 0
            local leaseMillis = tonumber(ARGV[8]) or 1
            local maxItems = tonumber(ARGV[9]) or 1
            local reservations = cjson.decode(ARGV[10])
            local leaseTokens = cjson.decode(ARGV[11])
            local claimed = {}
            local encodedTaskId = encode_segment(taskId)

            local function stale(reason)
                return cjson.encode({
                    status = 'STALE_CANDIDATE',
                    reason = reason,
                    claimed = {}
                })
            end

            local metaLane = redis.call('HGET', KEYS[3], 'laneBucketId')
            local metaEpoch = tonumber(redis.call('HGET', KEYS[3], 'runtimeEpoch') or '-1')
            local metaFence = redis.call('HGET', KEYS[3], 'fenceToken') or ''
            local currentScore = tonumber(redis.call('ZSCORE', KEYS[4], encodedTaskId) or '')

            if metaLane ~= laneKey then
                return stale('lane mismatch')
            end
            if metaEpoch ~= runtimeEpoch then
                return stale('runtime epoch mismatch')
            end
            if metaFence ~= fenceToken then
                return stale('fence token mismatch')
            end
            if not currentScore or currentScore ~= observedScore then
                return stale('score mismatch')
            end
            if observedScore < dispatchScoreFloor then
                return stale('score band mismatch')
            end
            if observedScore > nowMillis then
                return stale('score is not due')
            end

            if #reservations == 0 then
                return cjson.encode({
                    status = 'EMPTY',
                    reason = 'no reservations',
                    claimed = claimed
                })
            end

            for index = 1, maxItems do
                local encodedFrame = redis.call('LPOP', KEYS[1])
                if not encodedFrame then
                    break
                end
                local frame = cjson.decode(encodedFrame)
                local messageId = tostring(frame.messageId)
                local encodedMessageId = encode_segment(messageId)
                local retryCount = tonumber(frame.retryCount) or 0
                local attemptNo = retryCount + 1
                local leaseToken = tostring(leaseTokens[index])
                local reservation = reservations[((index - 1) % #reservations) + 1]
                local leaseExpireAtMillis = nowMillis + math.max(1, leaseMillis)
                local state = {
                    schemaVersion = 1,
                    taskId = taskId,
                    messageId = messageId,
                    state = 'LEASED',
                    sourceFrame = frame,
                    attemptNo = attemptNo,
                    retryCount = retryCount,
                    runtimeEpoch = runtimeEpoch,
                    fenceToken = fenceToken,
                    leaseToken = leaseToken,
                    workerReservationToken = reservation.reservationToken,
                    workerId = reservation.workerId,
                    workerGroupId = reservation.workerGroupId,
                    dispatchTargetRef = reservation.dispatchTargetRef,
                    batchId = reservation.batchId,
                    scoreBandClaimScore = reservation.scoreBandClaimScore,
                    leasedAtMillis = nowMillis,
                    leaseExpireAtMillis = leaseExpireAtMillis,
                    updatedAtMillis = nowMillis
                }
                redis.call('HSET', KEYS[2], encodedMessageId, cjson.encode(state))
                claimed[#claimed + 1] = {
                    taskId = taskId,
                    messageId = messageId,
                    eventCode = frame.eventCode,
                    payloadJson = frame.payloadJson or {},
                    payloadRef = frame.payloadRef,
                    leaseToken = leaseToken,
                    workerReservationToken = reservation.reservationToken,
                    scoreBandClaimScore = reservation.scoreBandClaimScore,
                    workerId = reservation.workerId,
                    workerGroupId = reservation.workerGroupId,
                    batchId = reservation.batchId,
                    attemptNo = attemptNo,
                    leaseExpireAtMillis = leaseExpireAtMillis
                }
            end

            return cjson.encode({
                status = #claimed > 0 and 'CLAIMED' or 'EMPTY',
                reason = #claimed > 0 and '' or 'no backlog',
                claimed = claimed
            })
            """;

    static final String PROMOTE_DUE_RETRIES = """
            local nowMillis = tonumber(ARGV[1]) or 0
            local itemLimit = tonumber(ARGV[2]) or 1
            local dueIds = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', nowMillis, 'LIMIT', 0, itemLimit)
            local promoted = {}

            for _, encodedMessageId in ipairs(dueIds) do
                local retryFrame = redis.call('HGET', KEYS[2], encodedMessageId)
                if retryFrame then
                    redis.call('RPUSH', KEYS[3], retryFrame)
                    promoted[#promoted + 1] = encodedMessageId
                end
                redis.call('ZREM', KEYS[1], encodedMessageId)
                redis.call('HDEL', KEYS[2], encodedMessageId)
            end

            return cjson.encode(promoted)
            """;

    static final String APPLY_RESULT = """
            local encodedMessageId = ARGV[1]
            local taskId = ARGV[2]
            local messageId = ARGV[3]
            local leaseToken = ARGV[4]
            local workerId = ARGV[5]
            local attemptNo = tonumber(ARGV[6]) or 1
            local source = ARGV[7]
            local success = ARGV[8] == 'true'
            local resultPayloadJson = cjson.decode(ARGV[9])
            local failureReason = ARGV[10] or ''
            local observedAtMillis = tonumber(ARGV[11]) or 0
            local retryAllowed = ARGV[12] == 'true'
            local retryAtMillis = tonumber(ARGV[13]) or 0
            local finalExpiresAt = tonumber(ARGV[14]) or 0

            local activeJson = redis.call('HGET', KEYS[1], encodedMessageId)
            if not activeJson then
                if redis.call('HEXISTS', KEYS[2], encodedMessageId) == 1 then
                    return cjson.encode({
                        status = 'DUPLICATE_OR_LATE',
                        reason = 'already final'
                    })
                end
                return cjson.encode({
                    status = 'DUPLICATE_OR_LATE',
                    reason = 'missing active state'
                })
            end

            local active = cjson.decode(activeJson)
            if tostring(active.leaseToken) ~= leaseToken
                    or tostring(active.workerId) ~= workerId
                    or tonumber(active.attemptNo) ~= attemptNo then
                return cjson.encode({
                    status = 'DUPLICATE_OR_LATE',
                    reason = 'stale lease token'
                })
            end

            redis.call('HDEL', KEYS[1], encodedMessageId)

            if success or not retryAllowed then
                local finalResult = {
                    schemaVersion = 1,
                    taskId = taskId,
                    messageId = messageId,
                    attemptNo = attemptNo,
                    retryCount = tonumber(active.retryCount) or 0,
                    workerId = workerId,
                    workerGroupId = active.workerGroupId,
                    batchId = active.batchId,
                    leaseToken = leaseToken,
                    source = source,
                    success = success,
                    status = success and 'SUCCESS' or 'FAILED',
                    finalReason = success and 'SUCCESS' or failureReason,
                    resultPayloadJson = resultPayloadJson,
                    errorMessage = failureReason,
                    completedAtMillis = observedAtMillis,
                    expiresAtMillis = finalExpiresAt
                }
                redis.call('HSET', KEYS[2], encodedMessageId, cjson.encode(finalResult))
                return cjson.encode({
                    status = 'LOGICAL_FINAL',
                    finalResultExpiresAtMillis = finalExpiresAt
                })
            end

            local retryFrame = active.sourceFrame or {}
            retryFrame.taskId = taskId
            retryFrame.messageId = messageId
            retryFrame.frameType = 'FAST_RETRY'
            retryFrame.retryCount = (tonumber(active.retryCount) or 0) + 1
            retryFrame.nextSchedulableAtMillis = retryAtMillis
            retryFrame.reason = source .. ':' .. failureReason
            retryFrame.updatedAtMillis = observedAtMillis
            local retryFrameJson = cjson.encode(retryFrame)
            if retryAtMillis <= observedAtMillis then
                redis.call('RPUSH', KEYS[5], retryFrameJson)
            else
                redis.call('ZADD', KEYS[3], retryAtMillis, encodedMessageId)
                redis.call('HSET', KEYS[4], encodedMessageId, retryFrameJson)
            end

            return cjson.encode({
                status = 'RETRY_SCHEDULED',
                retryAtMillis = retryAtMillis,
                reason = failureReason
            })
            """;

    static final String CLOSE_IF_DRAINED = """
            local terminalScore = tonumber(ARGV[2]) or -1
            if redis.call('LLEN', KEYS[1]) == 0
                    and redis.call('ZCARD', KEYS[2]) == 0
                    and redis.call('HLEN', KEYS[3]) == 0
                    and redis.call('HLEN', KEYS[4]) == 0 then
                redis.call('ZADD', KEYS[5], terminalScore, ARGV[1])
                return 1
            end
            return 0
            """;

    static final String DISCARD_RUNTIME = """
            local ready = redis.call('LLEN', KEYS[1])
                    + redis.call('ZCARD', KEYS[2])
                    + redis.call('HLEN', KEYS[3])
            local active = redis.call('HLEN', KEYS[4])
            local results = redis.call('HLEN', KEYS[5])
            redis.call('ZREM', KEYS[7], ARGV[1])
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6])
            return cjson.encode({
                ready = ready,
                active = active,
                results = results
            })
            """;

    static final String DISCARD_WORK = """
            local ready = redis.call('LLEN', KEYS[1])
                    + redis.call('ZCARD', KEYS[2])
                    + redis.call('HLEN', KEYS[3])
            local active = redis.call('HLEN', KEYS[4])
            redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
            return cjson.encode({
                ready = ready,
                active = active
            })
            """;
}
