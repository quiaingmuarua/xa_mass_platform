package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.*;
import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkerScoreEncodingTest {
    @Test
    void compactCoordinatesAndSingleFieldReplacementsPreserveOtherFields() {
        for (int sign : new int[]{-1, 1}) {
            for (int rank : new int[]{0, 99}) {
                for (int dirty : new int[]{0, 1}) {
                    long score = sign * (24600L + rank * 2 + dirty);
                    var state = decodeState("w", score);
                    assertEquals(12300, state.timeMillis());
                    assertEquals(rank, state.laneRank());
                    assertEquals(dirty, state.dirty());
                    assertEquals(sign, state.polarity().value());
                    assertEquals(sign * (24800L + rank * 2 + dirty), replaceTime(score, 124));
                    assertEquals(sign * (24600L + 84 + dirty), replaceRank(score, 42));
                    assertEquals(sign * (24600L + rank * 2 + 1 - dirty), replaceDirty(score, 1 - dirty));
                    assertEquals(Math.abs(score), replacePolarity(score, WorkerScorePolarity.HOT_ACQUIRE));
                    assertEquals(-Math.abs(score), replacePolarity(score, WorkerScorePolarity.RECOVERY_RECHECK));
                }
            }
        }
    }

    @Test
    void coldPauseAndSlotBoundariesKeepTheExistingEncoding() {
        assertEquals(-200, -absoluteScore(COLD_PARK_TIME_SLOT, 0, 0));
        assertEquals(0, decodeState("w", 199).timeMillis());
        assertEquals(100, decodeState("w", 200).timeMillis());
        assertEquals(200, absoluteScore(199 / SLOT_MILLIS, 0, 0));
        assertEquals(400, absoluteScore(200 / SLOT_MILLIS, 0, 0));
        for (int sign : new int[]{-1, 1}) {
            var pause = decodeState("w", sign * 19_999_999_999_999L);
            assertEquals(PAUSE_TIME_MILLIS, pause.timeMillis());
            assertEquals(99, pause.laneRank());
            assertEquals(1, pause.dirty());
        }
        assertTrue(validTimeMillis(0));
        assertTrue(validTimeMillis(PAUSE_TIME_MILLIS));
        assertFalse(validTimeMillis(-1));
        assertFalse(validTimeMillis(PAUSE_TIME_MILLIS + 1));
        assertFalse(validLaneRank(-1));
        assertFalse(validLaneRank(100));
        for (double invalid : new double[]{0, 0.5, Double.NaN, Double.POSITIVE_INFINITY,
                Long.MIN_VALUE, 20_000_000_000_000L, -20_000_000_000_000L}) {
            assertThrows(IllegalStateException.class, () -> decodeState("w", invalid));
        }
    }
}
