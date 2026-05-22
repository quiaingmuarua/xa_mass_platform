package com.xa.mass.workerpack.sample.command.fixture;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SampleClientStateTest {

    @Test
    void faultProfileIsPartOfSampleStateSnapshotAndReset() {
        SampleClientState state = new SampleClientState();
        state.setFaultProfile(SampleWorkerFaultProfile.fromProfile("NOISY", 99L));

        Map<?, ?> configuredProfile = faultProfileSnapshot(state);
        assertEquals(true, configuredProfile.get("enabled"));
        assertEquals("NOISY", configuredProfile.get("profile"));
        assertEquals(99L, configuredProfile.get("seed"));

        state.reset();

        Map<?, ?> resetProfile = faultProfileSnapshot(state);
        assertEquals(false, resetProfile.get("enabled"));
        assertEquals("FAST", resetProfile.get("profile"));
        assertFalse(state.getFaultProfile().enabled());
    }

    private static Map<?, ?> faultProfileSnapshot(SampleClientState state) {
        Object profile = state.snapshot().get("faultProfile");
        if (profile instanceof Map<?, ?> map) {
            return map;
        }
        throw new AssertionError("faultProfile snapshot missing");
    }
}
