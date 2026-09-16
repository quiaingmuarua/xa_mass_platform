package com.xa.mass.kernel.score.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only Redis witnesses. Production callers have no coordinate inspection capability. */
public final class WorkerScoreRedisFixture {
    private WorkerScoreRedisFixture() {}

    public static Map<String, Long> readScores(RedisCommands<String, String> redis, RedisKeyspace keyspace,
                                               String group, List<String> ids) {
        var rows = redis.zmscore(keyspace.base() + ":worker:score:" + group, ids.toArray(String[]::new));
        var scores = new LinkedHashMap<String, Long>();
        for (int i = 0; i < ids.size(); i++) {
            Double row = rows.get(i);
            scores.put(ids.get(i), row == null ? null : WorkerScoreEncoding.scoreToLong(row));
        }
        return scores;
    }

    public static long timeMillis(long score) {
        return WorkerScoreEncoding.decodeState("fixture", score).timeMillis();
    }

    public static int mark(long score) {
        return WorkerScoreEncoding.decodeState("fixture", score).mark();
    }

    public static boolean hasPolarity(long score,
            com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity expected) {
        return WorkerScoreEncoding.decodeState("fixture", score).polarity() == expected;
    }

    public static long slotStart(long millis) {
        return millis / WorkerScoreEncoding.SLOT_MILLIS * WorkerScoreEncoding.SLOT_MILLIS;
    }

    public static long dueMarkedScore(long millis) {
        return WorkerScoreEncoding.absoluteScore(millis / WorkerScoreEncoding.SLOT_MILLIS - 100, 1);
    }
}
