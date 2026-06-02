package com.xa.mass.testing.soak;

import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoakConfigTest {

    @Test
    void usesDefaultsWhenPropertiesAreAbsent() {
        SoakConfig config = SoakConfig.from(new Properties());

        assertEquals(SoakConfig.DEFAULT_DURATION_SECONDS, config.durationSeconds());
        assertEquals(WorkerFaultScenarioIndex.Scenario.POLLING_SCHEDULING_SOAK, config.scenario());
        assertEquals(SoakConfig.DEFAULT_WORKER_COUNT, config.workerCount());
        assertEquals(SoakConfig.DEFAULT_WORKER_COUNT, config.initialWorkerCount());
        assertEquals(SoakConfig.DEFAULT_LATE_WORKER_START_AFTER_MILLIS, config.lateWorkerStartAfterMillis());
        assertEquals(SoakConfig.DEFAULT_GROUP_COUNT, config.groupCount());
        assertEquals(SoakConfig.DEFAULT_EVENT_CODE_COUNT, config.eventCodeCount());
        assertEquals(SoakConfig.DEFAULT_SUBMIT_RATE_PER_SECOND, config.submitRatePerSecond());
        assertEquals(SoakConfig.DEFAULT_MESSAGES_PER_TASK, config.messagesPerTask());
        assertEquals(SoakConfig.DEFAULT_POLL_BATCH_SIZE, config.pollBatchSize());
        assertEquals(SoakConfig.DEFAULT_PROCESSING_JITTER_SEED, config.processingJitterSeed());
        assertTrue(config.traceEnabled());
        assertTrue(config.forceExit());
    }

    @Test
    void parsesExplicitProperties() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.scenarioId", "polling-scheduling-soak");
        properties.setProperty("mass.soak.durationSeconds", "20");
        properties.setProperty("mass.soak.workerCount", "4");
        properties.setProperty("mass.soak.initialWorkerCount", "2");
        properties.setProperty("mass.soak.lateWorkerStartAfterMillis", "1000");
        properties.setProperty("mass.soak.requireLateWorkerWork", "true");
        properties.setProperty("mass.soak.groupCount", "3");
        properties.setProperty("mass.soak.eventCodeCount", "5");
        properties.setProperty("mass.soak.submitRatePerSecond", "7");
        properties.setProperty("mass.soak.messagesPerTask", "2");
        properties.setProperty("mass.soak.processingJitterMillis", "25");
        properties.setProperty("mass.soak.processingJitterSeed", "12345");
        properties.setProperty("mass.soak.failureEveryNth", "3");
        properties.setProperty("mass.soak.trace", "false");
        properties.setProperty("mass.soak.forceExit", "false");

        SoakConfig config = SoakConfig.from(properties);

        assertEquals(WorkerFaultScenarioIndex.Scenario.POLLING_SCHEDULING_SOAK, config.scenario());
        assertEquals(20, config.durationSeconds());
        assertEquals(4, config.workerCount());
        assertEquals(2, config.initialWorkerCount());
        assertEquals(1000, config.lateWorkerStartAfterMillis());
        assertEquals(true, config.requireLateWorkerWork());
        assertEquals(3, config.groupCount());
        assertEquals(5, config.eventCodeCount());
        assertEquals(7, config.submitRatePerSecond());
        assertEquals(2, config.messagesPerTask());
        assertEquals(25, config.processingJitterMillis());
        assertEquals(12345L, config.processingJitterSeed());
        assertEquals(3, config.failureEveryNth());
        assertEquals(false, config.traceEnabled());
        assertEquals(false, config.forceExit());
    }

    @Test
    void scenarioIdSelectsNoisyMixedResultDefaults() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.scenarioId", "polling-soak-noisy-mixed-result");

        SoakConfig config = SoakConfig.from(properties);

        assertEquals(WorkerFaultScenarioIndex.Scenario.POLLING_SOAK_NOISY_MIXED_RESULT, config.scenario());
        assertEquals(SoakConfig.DEFAULT_PROCESSING_DELAY_MILLIS, config.processingDelayMillis());
        assertEquals(25, config.processingJitterMillis());
        assertEquals(SoakConfig.NOISY_MIXED_RESULT_SEED, config.processingJitterSeed());
        assertEquals(5, config.failureEveryNth());
        assertEquals("polling-soak-noisy-mixed-result", config.toMap().get("scenarioId"));
    }

    @Test
    void explicitPropertiesOverrideScenarioDefaults() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.scenarioId", "polling-soak-noisy-mixed-result");
        properties.setProperty("mass.soak.processingJitterMillis", "7");
        properties.setProperty("mass.soak.processingJitterSeed", "42");
        properties.setProperty("mass.soak.failureEveryNth", "3");

        SoakConfig config = SoakConfig.from(properties);

        assertEquals(7, config.processingJitterMillis());
        assertEquals(42L, config.processingJitterSeed());
        assertEquals(3, config.failureEveryNth());
    }

    @Test
    void rejectsInvalidNumericProperties() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.workerCount", "0");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertEquals("workerCount must be positive", error.getMessage());
    }

    @Test
    void rejectsMalformedIntegers() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.submitRatePerSecond", "fast");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertTrue(error.getMessage().contains("mass.soak.submitRatePerSecond must be an integer"));
    }

    @Test
    void rejectsMalformedLongs() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.processingJitterSeed", "seed");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertTrue(error.getMessage().contains("mass.soak.processingJitterSeed must be a long"));
    }

    @Test
    void rejectsInitialWorkerCountAboveWorkerCount() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.workerCount", "2");
        properties.setProperty("mass.soak.initialWorkerCount", "3");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertEquals("initialWorkerCount must not exceed workerCount", error.getMessage());
    }

    @Test
    void rejectsLateWorkerRequirementWithoutLateWorkers() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.workerCount", "2");
        properties.setProperty("mass.soak.initialWorkerCount", "2");
        properties.setProperty("mass.soak.requireLateWorkerWork", "true");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertEquals("requireLateWorkerWork requires initialWorkerCount to be smaller than workerCount",
                error.getMessage());
    }

    @Test
    void rejectsNegativeProcessingJitterSeed() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.processingJitterSeed", "-1");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertEquals("processingJitterSeed must not be negative", error.getMessage());
    }

    @Test
    void rejectsUnknownScenarioId() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.scenarioId", "missing-soak-row");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertEquals("unknown mass.soak.scenarioId: missing-soak-row", error.getMessage());
    }

    @Test
    void rejectsNonSoakScenarioId() {
        Properties properties = new Properties();
        properties.setProperty("mass.soak.scenarioId", "sdk-transport-load");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SoakConfig.from(properties));

        assertEquals("mass.soak.scenarioId must reference an SDK polling soak scenario: sdk-transport-load",
                error.getMessage());
    }
}
