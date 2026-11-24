package com.xa.mass.kernel.score.redis;

/** Shared encoding-blind Redis scripts for package-local Score Owners. */
final class ScoreRedisScripts {

    private ScoreRedisScripts() {
    }

    // Binary-search the (score, member) insertion position. Unlike rank/offset
    // cursors this still progresses after the preceding page is purged.
    // ARGV: afterScore, afterMember, upperBound, limit, lowerExclusive('1'|'0'), upperExclusive('1'|'0')
    static final String KEYSET_PAGE_SCRIPT = """
            local lo, hi = 0, redis.call('ZCARD', KEYS[1])
            local score, member = tonumber(ARGV[1]), ARGV[2]
            local upper, limit = tonumber(ARGV[3]), tonumber(ARGV[4])
            local exclusive, upper_exclusive = ARGV[5] == '1', ARGV[6] == '1'
            while lo < hi do
              local mid = math.floor((lo + hi) / 2)
              local row = redis.call('ZRANGE', KEYS[1], mid, mid, 'WITHSCORES')
              local s = tonumber(row[2])
              if s < score or (exclusive and s == score and row[1] <= member) then lo = mid + 1 else hi = mid end
            end
            local rows = redis.call('ZRANGE', KEYS[1], lo, lo + limit - 1, 'WITHSCORES')
            local result = {}
            for i = 1, #rows, 2 do
              local s = tonumber(rows[i + 1])
              if (upper_exclusive and s >= upper) or (not upper_exclusive and s > upper) then break end
              result[#result + 1] = rows[i]
              result[#result + 1] = rows[i + 1]
            end
            return result
            """;
}
