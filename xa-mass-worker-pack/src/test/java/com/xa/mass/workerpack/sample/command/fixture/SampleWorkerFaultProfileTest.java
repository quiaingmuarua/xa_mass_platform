package com.xa.mass.workerpack.sample.command.fixture;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleWorkerFaultProfileTest {

    @Test
    void disabledProfileKeepsDefaultWorkerBehaviorStable() {
        SampleWorkerFaultProfile profile = SampleWorkerFaultProfile.disabled();

        assertFalse(profile.enabled());
        assertEquals(0L, profile.resolveDelayMillis("worker", "task", "msg", 1));
        assertFalse(profile.shouldDropResult("worker", "task", "msg", 1));
        assertEquals(0, profile.duplicateResultCount());
        assertEquals(SampleWorkerFaultProfile.StallMode.OFF, profile.stallMode());
        assertEquals(SampleWorkerFaultProfile.DisconnectPhase.NONE, profile.disconnectPhase());
    }

    @Test
    void namedProfilesExpandToInspectablePrimitiveConfiguration() {
        SampleWorkerFaultProfile noisy = SampleWorkerFaultProfile.fromProfile("NOISY", 42L);

        assertTrue(noisy.enabled());
        assertEquals(SampleWorkerFaultProfile.ProfileName.NOISY, noisy.profileName());
        assertEquals(SampleWorkerFaultProfile.DelayDistribution.UNIFORM, noisy.delayDistribution());
        assertEquals(SampleWorkerFaultProfile.ResultDropMode.PERCENT, noisy.resultDropMode());
        assertEquals(10, noisy.resultDropPercent());
        assertEquals(1, noisy.duplicateResultCount());

        Map<String, Object> snapshot = noisy.toMap();
        assertEquals("NOISY", snapshot.get("profile"));
        assertEquals(42L, snapshot.get("seed"));
        assertEquals("UNIFORM", snapshot.get("delayDistribution"));
    }

    @Test
    void profileDelayAndDropDecisionsAreDeterministicBySeedAndIdentity() {
        SampleWorkerFaultProfile profile = SampleWorkerFaultProfile.fromProfile("SLOW", 7L);

        long first = profile.resolveDelayMillis("worker", "task", "msg", 1);
        long second = profile.resolveDelayMillis("worker", "task", "msg", 1);

        assertEquals(first, second);
        assertTrue(first >= profile.minDelayMillis());
        assertTrue(first <= profile.maxDelayMillis());

        SampleWorkerFaultProfile dropProfile = SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.FLAKY_RESULT,
                        11L
                )
                .resultDrop(SampleWorkerFaultProfile.ResultDropMode.PERCENT, 100)
                .build();
        assertTrue(dropProfile.shouldDropResult("worker", "task", "msg", 1));
    }
}
