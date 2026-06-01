package com.xa.mass.engine.watchdog;

import com.xa.mass.engine.PollingResourceKey;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingIdleAdmissionTrackerTest {

    @Test
    void suppressesIdleResourceUntilPolicyDelayExpires() {
        PollingResourceKey key = new PollingResourceKey("test-source", "resource-1");
        PollingIdleAdmissionTracker tracker = new PollingIdleAdmissionTracker(
                50L,
                100L,
                1_000L,
                decision -> 200L
        );

        assertTrue(tracker.admit(key, 1_000L));

        tracker.recordIdle(key, 1_000L);

        assertFalse(tracker.admit(key, 1_199L));
        assertTrue(tracker.admit(key, 1_200L));
    }

    @Test
    void recordProgressClearsIdleAdmission() {
        PollingResourceKey key = new PollingResourceKey("test-source", "resource-1");
        PollingIdleAdmissionTracker tracker = new PollingIdleAdmissionTracker(
                50L,
                100L,
                1_000L,
                decision -> 1_000L
        );

        tracker.recordIdle(key, 1_000L);
        assertFalse(tracker.admit(key, 1_100L));

        tracker.recordProgress(key);

        assertTrue(tracker.admit(key, 1_100L));
        assertEquals(0, tracker.idleAdmissionCount());
    }

    @Test
    void wakeAllClearsIdleAdmissions() {
        PollingResourceKey first = new PollingResourceKey("test-source", "resource-1");
        PollingResourceKey second = new PollingResourceKey("test-source", "resource-2");
        PollingIdleAdmissionTracker tracker = new PollingIdleAdmissionTracker(
                50L,
                100L,
                1_000L,
                decision -> 1_000L
        );

        tracker.recordIdle(first, 1_000L);
        tracker.recordIdle(second, 1_000L);

        tracker.wakeAll();

        assertTrue(tracker.admit(first, 1_100L));
        assertTrue(tracker.admit(second, 1_100L));
        assertEquals(0, tracker.idleAdmissionCount());
    }

    @Test
    void resourcesAreIsolated() {
        PollingResourceKey first = new PollingResourceKey("test-source", "resource-1");
        PollingResourceKey second = new PollingResourceKey("test-source", "resource-2");
        PollingIdleAdmissionTracker tracker = new PollingIdleAdmissionTracker(
                50L,
                100L,
                1_000L,
                decision -> 1_000L
        );

        tracker.recordIdle(first, 1_000L);

        assertFalse(tracker.admit(first, 1_100L));
        assertTrue(tracker.admit(second, 1_100L));
    }

    @Test
    void policyReceivesResourceAndConsecutiveIdleCount() {
        PollingResourceKey key = new PollingResourceKey("test-source", "resource-1");
        AtomicInteger lastCount = new AtomicInteger();
        PollingIdleAdmissionTracker tracker = new PollingIdleAdmissionTracker(
                50L,
                100L,
                1_000L,
                decision -> {
                    assertEquals(key, decision.resourceKey());
                    lastCount.set(decision.consecutiveIdleCount());
                    return 100L;
                }
        );

        tracker.recordIdle(key, 1_000L);
        tracker.recordIdle(key, 1_200L);

        assertEquals(2, lastCount.get());
    }
}
