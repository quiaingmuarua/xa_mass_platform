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
        assertEquals(0L, profile.resolveDelayMillis("worker", "corr", 1));
        assertFalse(profile.shouldDropResult("worker", "corr", 1));
        assertEquals(0, profile.duplicateResultCount());
        assertEquals(0L, profile.lateResultDelayMillis());
        assertEquals(SampleWorkerFaultProfile.StallMode.OFF, profile.stallMode());
        assertEquals(0L, profile.stallMillis());
        assertEquals(SampleWorkerFaultProfile.MalformedResultKind.NONE, profile.malformedResultKind());
        assertEquals(SampleWorkerFaultProfile.ResultIdentityKind.NONE, profile.resultIdentityKind());
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
    void stallProfilesExposeWhetherResultShouldBeSuppressedOrDelayed() {
        SampleWorkerFaultProfile stuck = SampleWorkerFaultProfile.fromProfile("STUCK", 1L);

        assertTrue(stuck.shouldStallWithoutResult());
        assertEquals(SampleWorkerFaultProfile.StallMode.FOREVER, stuck.stallMode());

        SampleWorkerFaultProfile duration = SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.STUCK,
                        2L
                )
                .stallDuration(250L)
                .build();

        assertFalse(duration.shouldStallWithoutResult());
        assertEquals(SampleWorkerFaultProfile.StallMode.DURATION, duration.stallMode());
        assertEquals(250L, duration.resolveStallDelayMillis());
        assertEquals(250L, duration.toMap().get("stallMillis"));
    }

    @Test
    void malformedProfileExposesMalformedResultKind() {
        SampleWorkerFaultProfile malformed = SampleWorkerFaultProfile.fromProfile("MALFORMED_RESULT", 3L);

        assertTrue(malformed.enabled());
        assertEquals(SampleWorkerFaultProfile.ProfileName.MALFORMED_RESULT, malformed.profileName());
        assertEquals(SampleWorkerFaultProfile.MalformedResultKind.MISSING_CORRELATION_REF, malformed.malformedResultKind());
        assertEquals("MISSING_CORRELATION_REF", malformed.toMap().get("malformedResultKind"));
    }

    @Test
    void lateResultProfileExposesLateDelay() {
        SampleWorkerFaultProfile late = SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.NEAR_TIMEOUT,
                        5L
                )
                .lateResultDelay(1_234L)
                .build();

        assertTrue(late.enabled());
        assertEquals(1_234L, late.lateResultDelayMillis());
        assertEquals(1_234L, late.toMap().get("lateResultDelayMillis"));
    }

    @Test
    void wrongIdentityProfileExposesIdentityKind() {
        SampleWorkerFaultProfile identity = SampleWorkerFaultProfile.fromProfile("WRONG_IDENTITY", 6L);

        assertTrue(identity.enabled());
        assertEquals(SampleWorkerFaultProfile.ProfileName.WRONG_IDENTITY, identity.profileName());
        assertEquals(SampleWorkerFaultProfile.ResultIdentityKind.WRONG_CORRELATION, identity.resultIdentityKind());
        assertEquals("WRONG_CORRELATION", identity.toMap().get("resultIdentityKind"));
    }

    @Test
    void transportProfileExposesDisconnectPhase() {
        SampleWorkerFaultProfile flakyTransport = SampleWorkerFaultProfile.fromProfile("FLAKY_TRANSPORT", 4L);

        assertTrue(flakyTransport.enabled());
        assertEquals(SampleWorkerFaultProfile.ProfileName.FLAKY_TRANSPORT, flakyTransport.profileName());
        assertEquals(SampleWorkerFaultProfile.DisconnectPhase.BEFORE_RESULT, flakyTransport.disconnectPhase());
        assertEquals("BEFORE_RESULT", flakyTransport.toMap().get("disconnectPhase"));
    }

    @Test
    void profileDelayAndDropDecisionsAreDeterministicBySeedAndIdentity() {
        SampleWorkerFaultProfile profile = SampleWorkerFaultProfile.fromProfile("SLOW", 7L);

        long first = profile.resolveDelayMillis("worker", "corr", 1);
        long second = profile.resolveDelayMillis("worker", "corr", 1);

        assertEquals(first, second);
        assertTrue(first >= profile.minDelayMillis());
        assertTrue(first <= profile.maxDelayMillis());

        SampleWorkerFaultProfile dropProfile = SampleWorkerFaultProfile.builder(
                        SampleWorkerFaultProfile.ProfileName.FLAKY_RESULT,
                        11L
                )
                .resultDrop(SampleWorkerFaultProfile.ResultDropMode.PERCENT, 100)
                .build();
        assertTrue(dropProfile.shouldDropResult("worker", "corr", 1));
    }
}
