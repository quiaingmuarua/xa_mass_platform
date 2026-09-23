package com.xa.mass.kernel.score.redis;

import static com.xa.mass.kernel.score.WorkerScoreCore.*;
import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkerScoreEncodingTest {
    @Test
    void partitionedCoordinatesAndTimeReplacementPreserveOtherFields() {
        for (int sign : new int[]{-1, 1}) {
            for (int mark : new int[]{0, 1}) {
                long score = sign * (123L + mark * MARK_BASE);
                var state = decodeState("w", score);
                assertEquals(12300, state.timeMillis());
                assertEquals(mark, state.mark());
                assertEquals(sign, polarityValue(state.polarity()));
                assertEquals(sign * (124L + mark * MARK_BASE), replaceTime(score, 124));
            }
        }
    }

    @Test
    void coldPauseAndSlotBoundariesUseOnlyTimeAndMark() {
        assertEquals(-1, -absoluteScore(COLD_PARK_TIME_SLOT, 0));
        assertEquals(0, decodeState("w", MARK_BASE).timeMillis());
        assertEquals(100, decodeState("w", 1).timeMillis());
        assertEquals(1, absoluteScore(199 / SLOT_MILLIS, 0));
        assertEquals(2, absoluteScore(200 / SLOT_MILLIS, 0));
        for (int sign : new int[]{-1, 1}) {
            var pause = decodeState("w", sign * 199_999_999_999L);
            assertEquals(PAUSE_TIME_MILLIS, pause.timeMillis());
            assertEquals(1, pause.mark());
        }
        assertTrue(validTimeMillis(0));
        assertTrue(validTimeMillis(PAUSE_TIME_MILLIS));
        assertFalse(validTimeMillis(-1));
        assertFalse(validTimeMillis(PAUSE_TIME_MILLIS + 1));
        for (double invalid : new double[]{0, 0.5, Double.NaN, Double.POSITIVE_INFINITY,
                Long.MIN_VALUE, 200_000_000_000L, -200_000_000_000L}) {
            assertThrows(IllegalStateException.class, () -> decodeState("w", invalid));
        }
    }
}
