package com.xa.mass.testing.soak;

import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

final class SoakConfig {

    static final int DEFAULT_DURATION_SECONDS = 120;
    static final int DEFAULT_WORKER_COUNT = 16;
    static final int DEFAULT_GROUP_COUNT = 2;
    static final int DEFAULT_EVENT_CODE_COUNT = 2;
    static final int DEFAULT_LATE_WORKER_START_AFTER_MILLIS = 0;
    static final int DEFAULT_SUBMIT_RATE_PER_SECOND = 20;
    static final int DEFAULT_MESSAGES_PER_TASK = 8;
    static final int DEFAULT_POLL_BATCH_SIZE = 4;
    static final int DEFAULT_EMPTY_POLL_BACKOFF_MILLIS = 20;
    static final int DEFAULT_PROCESSING_DELAY_MILLIS = 5;
    static final int DEFAULT_PROCESSING_JITTER_MILLIS = 0;
    static final long DEFAULT_PROCESSING_JITTER_SEED = 0L;
    static final int DEFAULT_FAILURE_EVERY_NTH = 0;
    static final int DEFAULT_DRAIN_TIMEOUT_SECONDS = 60;
    static final int DEFAULT_RESULT_WINDOW_LIMIT = 64;
    static final int DEFAULT_TRACE_QUEUE_CAPACITY = 65_536;
    static final int DEFAULT_TRACE_ROTATE_AFTER_LINES = 100_000;
    static final long NOISY_MIXED_RESULT_SEED = 20260602L;

    private final WorkerFaultScenarioIndex.Scenario scenario;
    private final int durationSeconds;
    private final int workerCount;
    private final int groupCount;
    private final int eventCodeCount;
    private final int initialWorkerCount;
    private final int lateWorkerStartAfterMillis;
    private final boolean requireLateWorkerWork;
    private final int submitRatePerSecond;
    private final int messagesPerTask;
    private final int pollBatchSize;
    private final int emptyPollBackoffMillis;
    private final int processingDelayMillis;
    private final int processingJitterMillis;
    private final long processingJitterSeed;
    private final int failureEveryNth;
    private final int drainTimeoutSeconds;
    private final int resultWindowLimit;
    private final boolean traceEnabled;
    private final int traceQueueCapacity;
    private final int traceRotateAfterLines;
    private final boolean forceExit;

    private SoakConfig(WorkerFaultScenarioIndex.Scenario scenario,
                       int durationSeconds,
                       int workerCount,
                       int groupCount,
                       int eventCodeCount,
                       int initialWorkerCount,
                       int lateWorkerStartAfterMillis,
                       boolean requireLateWorkerWork,
                       int submitRatePerSecond,
                       int messagesPerTask,
                       int pollBatchSize,
                       int emptyPollBackoffMillis,
                       int processingDelayMillis,
                       int processingJitterMillis,
                       long processingJitterSeed,
                       int failureEveryNth,
                       int drainTimeoutSeconds,
                       int resultWindowLimit,
                       boolean traceEnabled,
                       int traceQueueCapacity,
                       int traceRotateAfterLines,
                       boolean forceExit) {
        this.scenario = scenario;
        this.durationSeconds = durationSeconds;
        this.workerCount = workerCount;
        this.groupCount = groupCount;
        this.eventCodeCount = eventCodeCount;
        this.initialWorkerCount = initialWorkerCount;
        this.lateWorkerStartAfterMillis = lateWorkerStartAfterMillis;
        this.requireLateWorkerWork = requireLateWorkerWork;
        this.submitRatePerSecond = submitRatePerSecond;
        this.messagesPerTask = messagesPerTask;
        this.pollBatchSize = pollBatchSize;
        this.emptyPollBackoffMillis = emptyPollBackoffMillis;
        this.processingDelayMillis = processingDelayMillis;
        this.processingJitterMillis = processingJitterMillis;
        this.processingJitterSeed = processingJitterSeed;
        this.failureEveryNth = failureEveryNth;
        this.drainTimeoutSeconds = drainTimeoutSeconds;
        this.resultWindowLimit = resultWindowLimit;
        this.traceEnabled = traceEnabled;
        this.traceQueueCapacity = traceQueueCapacity;
        this.traceRotateAfterLines = traceRotateAfterLines;
        this.forceExit = forceExit;
    }

    static SoakConfig fromSystemProperties() {
        return from(System.getProperties());
    }

    static SoakConfig from(Properties properties) {
        WorkerFaultScenarioIndex.Scenario scenario = scenarioProperty(properties);
        ScenarioDefaults scenarioDefaults = ScenarioDefaults.forScenario(scenario);
        int workerCount = intProperty(properties, "mass.soak.workerCount", DEFAULT_WORKER_COUNT);
        SoakConfig config = new SoakConfig(
                scenario,
                intProperty(properties, "mass.soak.durationSeconds", DEFAULT_DURATION_SECONDS),
                workerCount,
                intProperty(properties, "mass.soak.groupCount", DEFAULT_GROUP_COUNT),
                intProperty(properties, "mass.soak.eventCodeCount", DEFAULT_EVENT_CODE_COUNT),
                intProperty(properties, "mass.soak.initialWorkerCount", workerCount),
                intProperty(properties, "mass.soak.lateWorkerStartAfterMillis",
                        scenarioDefaults.lateWorkerStartAfterMillis()),
                booleanProperty(properties, "mass.soak.requireLateWorkerWork",
                        scenarioDefaults.requireLateWorkerWork()),
                intProperty(properties, "mass.soak.submitRatePerSecond", DEFAULT_SUBMIT_RATE_PER_SECOND),
                intProperty(properties, "mass.soak.messagesPerTask", DEFAULT_MESSAGES_PER_TASK),
                intProperty(properties, "mass.soak.pollBatchSize", DEFAULT_POLL_BATCH_SIZE),
                intProperty(properties, "mass.soak.emptyPollBackoffMillis", DEFAULT_EMPTY_POLL_BACKOFF_MILLIS),
                intProperty(properties, "mass.soak.processingDelayMillis",
                        scenarioDefaults.processingDelayMillis()),
                intProperty(properties, "mass.soak.processingJitterMillis",
                        scenarioDefaults.processingJitterMillis()),
                longProperty(properties, "mass.soak.processingJitterSeed",
                        scenarioDefaults.processingJitterSeed()),
                intProperty(properties, "mass.soak.failureEveryNth", scenarioDefaults.failureEveryNth()),
                intProperty(properties, "mass.soak.drainTimeoutSeconds", DEFAULT_DRAIN_TIMEOUT_SECONDS),
                intProperty(properties, "mass.soak.resultWindowLimit", DEFAULT_RESULT_WINDOW_LIMIT),
                booleanProperty(properties, "mass.soak.trace", true),
                intProperty(properties, "mass.soak.traceQueueCapacity", DEFAULT_TRACE_QUEUE_CAPACITY),
                intProperty(properties, "mass.soak.traceRotateAfterLines", DEFAULT_TRACE_ROTATE_AFTER_LINES),
                booleanProperty(properties, "mass.soak.forceExit", true)
        );
        config.validate();
        return config;
    }

    private void validate() {
        require(durationSeconds > 0, "durationSeconds must be positive");
        require(workerCount > 0, "workerCount must be positive");
        require(groupCount > 0, "groupCount must be positive");
        require(eventCodeCount > 0, "eventCodeCount must be positive");
        require(initialWorkerCount > 0, "initialWorkerCount must be positive");
        require(initialWorkerCount <= workerCount, "initialWorkerCount must not exceed workerCount");
        require(lateWorkerStartAfterMillis >= 0, "lateWorkerStartAfterMillis must not be negative");
        require(!requireLateWorkerWork || initialWorkerCount < workerCount,
                "requireLateWorkerWork requires initialWorkerCount to be smaller than workerCount");
        require(initialWorkerCount == workerCount
                        || lateWorkerStartAfterMillis < TimeUnit.SECONDS.toMillis(durationSeconds),
                "lateWorkerStartAfterMillis must be smaller than durationSeconds when late workers are configured");
        require(submitRatePerSecond > 0, "submitRatePerSecond must be positive");
        require(messagesPerTask > 0, "messagesPerTask must be positive");
        require(pollBatchSize > 0, "pollBatchSize must be positive");
        require(emptyPollBackoffMillis >= 0, "emptyPollBackoffMillis must not be negative");
        require(processingDelayMillis >= 0, "processingDelayMillis must not be negative");
        require(processingJitterMillis >= 0, "processingJitterMillis must not be negative");
        require(processingJitterSeed >= 0L, "processingJitterSeed must not be negative");
        require(failureEveryNth >= 0, "failureEveryNth must not be negative");
        require(drainTimeoutSeconds > 0, "drainTimeoutSeconds must be positive");
        require(resultWindowLimit > 0, "resultWindowLimit must be positive");
        require(traceQueueCapacity > 0, "traceQueueCapacity must be positive");
        require(traceRotateAfterLines > 0, "traceRotateAfterLines must be positive");
    }

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scenarioId", scenario.scenarioId());
        values.put("durationSeconds", durationSeconds);
        values.put("workerCount", workerCount);
        values.put("groupCount", groupCount);
        values.put("eventCodeCount", eventCodeCount);
        values.put("initialWorkerCount", initialWorkerCount);
        values.put("lateWorkerStartAfterMillis", lateWorkerStartAfterMillis);
        values.put("requireLateWorkerWork", requireLateWorkerWork);
        values.put("submitRatePerSecond", submitRatePerSecond);
        values.put("messagesPerTask", messagesPerTask);
        values.put("pollBatchSize", pollBatchSize);
        values.put("emptyPollBackoffMillis", emptyPollBackoffMillis);
        values.put("processingDelayMillis", processingDelayMillis);
        values.put("processingJitterMillis", processingJitterMillis);
        values.put("processingJitterSeed", processingJitterSeed);
        values.put("failureEveryNth", failureEveryNth);
        values.put("drainTimeoutSeconds", drainTimeoutSeconds);
        values.put("resultWindowLimit", resultWindowLimit);
        values.put("trace", traceEnabled);
        values.put("traceQueueCapacity", traceQueueCapacity);
        values.put("traceRotateAfterLines", traceRotateAfterLines);
        values.put("forceExit", forceExit);
        return Map.copyOf(values);
    }

    WorkerFaultScenarioIndex.Scenario scenario() { return scenario; }
    int durationSeconds() { return durationSeconds; }
    int workerCount() { return workerCount; }
    int groupCount() { return groupCount; }
    int eventCodeCount() { return eventCodeCount; }
    int initialWorkerCount() { return initialWorkerCount; }
    int lateWorkerStartAfterMillis() { return lateWorkerStartAfterMillis; }
    boolean requireLateWorkerWork() { return requireLateWorkerWork; }
    int submitRatePerSecond() { return submitRatePerSecond; }
    int messagesPerTask() { return messagesPerTask; }
    int pollBatchSize() { return pollBatchSize; }
    int emptyPollBackoffMillis() { return emptyPollBackoffMillis; }
    int processingDelayMillis() { return processingDelayMillis; }
    int processingJitterMillis() { return processingJitterMillis; }
    long processingJitterSeed() { return processingJitterSeed; }
    int failureEveryNth() { return failureEveryNth; }
    int drainTimeoutSeconds() { return drainTimeoutSeconds; }
    int resultWindowLimit() { return resultWindowLimit; }
    boolean traceEnabled() { return traceEnabled; }
    int traceQueueCapacity() { return traceQueueCapacity; }
    int traceRotateAfterLines() { return traceRotateAfterLines; }
    boolean forceExit() { return forceExit; }

    private static WorkerFaultScenarioIndex.Scenario scenarioProperty(Properties properties) {
        String scenarioId = properties.getProperty("mass.soak.scenarioId");
        WorkerFaultScenarioIndex.Scenario scenario;
        if (scenarioId == null || scenarioId.isBlank()) {
            scenario = WorkerFaultScenarioIndex.Scenario.POLLING_SCHEDULING_SOAK;
        } else {
            scenario = WorkerFaultScenarioIndex.scenarioForId(scenarioId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown mass.soak.scenarioId: " + scenarioId.trim()));
        }
        if (scenario.runnerFamily() != WorkerFaultScenarioIndex.RunnerFamily.SDK_POLLING_SCHEDULING_SOAK) {
            throw new IllegalArgumentException("mass.soak.scenarioId must reference an SDK polling soak scenario: "
                    + scenario.scenarioId());
        }
        return scenario;
    }

    private static int intProperty(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer: " + value, e);
        }
    }

    private static boolean booleanProperty(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static long longProperty(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a long: " + value, e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record ScenarioDefaults(int lateWorkerStartAfterMillis,
                                    boolean requireLateWorkerWork,
                                    int processingDelayMillis,
                                    int processingJitterMillis,
                                    long processingJitterSeed,
                                    int failureEveryNth) {
        private static ScenarioDefaults forScenario(WorkerFaultScenarioIndex.Scenario scenario) {
            return switch (scenario) {
                case POLLING_SOAK_NOISY_MIXED_RESULT -> new ScenarioDefaults(
                        DEFAULT_LATE_WORKER_START_AFTER_MILLIS,
                        false,
                        DEFAULT_PROCESSING_DELAY_MILLIS,
                        25,
                        NOISY_MIXED_RESULT_SEED,
                        5
                );
                default -> new ScenarioDefaults(
                        DEFAULT_LATE_WORKER_START_AFTER_MILLIS,
                        false,
                        DEFAULT_PROCESSING_DELAY_MILLIS,
                        DEFAULT_PROCESSING_JITTER_MILLIS,
                        DEFAULT_PROCESSING_JITTER_SEED,
                        DEFAULT_FAILURE_EVERY_NTH
                );
            };
        }
    }
}
