package com.xa.mass.testing.concurrency;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.sdk.MassSdk;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runnable SDK-driven concurrency/load model using embedded polling workers.
 *
 * <p>This scenario starts an in-process {@link MassSdkApplication}, registers
 * polling workers through the SDK surface, and drives the runtime with real
 * {@link PullWorkerSession} polling/result submission instead of engine-private
 * test seams. It is intended to be a fast, non-Boot harness for modeling
 * realistic single-runtime concurrent pressure before escalating to full E2E.
 *
 * <p>Useful JVM properties:
 *
 * <pre>{@code
 * -Dmass.sdk.load.tasks=16
 * -Dmass.sdk.load.messagesPerTask=64
 * -Dmass.sdk.load.workers=8
 * -Dmass.sdk.load.batchSize=8
 * -Dmass.sdk.load.pollBatchSize=8
 * -Dmass.sdk.load.workerProcessingThreads=2
 * -Dmass.sdk.load.processingDelayMillis=5
 * -Dmass.sdk.load.retryFailureEveryNth=11
 * }</pre>
 */
public final class SdkPollingWorkerLoadRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SdkPollingWorkerLoadRunner() {
    }

    public static void main(String[] args) throws Exception {
        LoadConfig config = LoadConfig.fromSystemProperties();
        LoadReport report = new ScenarioRunner(config).run();
        System.out.println(report.toConsoleSummary());
        System.out.println("SDK polling worker load report written to: " + report.reportPath());
    }

    private static final class ScenarioRunner {
        private final LoadConfig config;
        private final RuntimeMetrics metrics = new RuntimeMetrics();
        private final Map<String, AtomicInteger> deliveryAttempts = new ConcurrentHashMap<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);

        private ScenarioRunner(LoadConfig config) {
            this.config = config;
        }

        private LoadReport run() throws Exception {
            MassSdkApplication app = buildApplication();
            List<PollWorker> workers = new ArrayList<>();
            List<String> taskIds = new ArrayList<>(config.taskCount());
            long wallStartNanos = System.nanoTime();

            try {
                app.start();
                registerWorkers(app, config.workerCount());
                workers = startPollingWorkers(app, config.workerCount());

                for (int i = 0; i < config.taskCount(); i++) {
                    Task task = app.createTask(buildTaskRequest(i));
                    taskIds.add(task.getTid());
                    require(app.approveTask(task.getTid()), "task approval should succeed for " + task.getTid());
                }

                waitForTerminalTasks(app, taskIds);

                long wallNanos = System.nanoTime() - wallStartNanos;
                FinalTaskStats finalTaskStats = collectFinalTaskStats(app, taskIds);
                FinalMessageStats finalMessageStats = collectFinalMessageStats(app, taskIds);
                Path reportPath = writeReport(config, finalTaskStats, finalMessageStats, wallNanos, metrics.snapshot());

                return new LoadReport(
                        config,
                        taskIds.size(),
                        finalTaskStats.terminalTasks(),
                        finalTaskStats.terminalReasons(),
                        finalMessageStats.totalMessages(),
                        finalMessageStats.successMessages(),
                        finalMessageStats.failedMessages(),
                        finalMessageStats.expiredMessages(),
                        nanosToMillis(wallNanos),
                        metrics.pollCalls.sum(),
                        metrics.emptyPollCalls.sum(),
                        metrics.dispatchedItems.sum(),
                        metrics.resultSubmissions.sum(),
                        metrics.syntheticRetryFailures.sum(),
                        metrics.maxPolledBatchSize.get(),
                        metrics.maxConcurrentProcessing.get(),
                        nanosToMillis(metrics.totalProcessingNanos.sum()),
                        reportPath
                );
            } finally {
                stopRequested.set(true);
                for (PollWorker worker : workers) {
                    worker.close();
                }
                app.stop();
            }
        }

        private MassSdkApplication buildApplication() {
            return MassSdk.builder()
                    .transportServer(0, "/testing")
                    .gateway(gateway -> gateway
                            .enabled(true)
                            .transportServerEnabled(false)
                            .queueMode())
                    .engine(engine -> engine.enabled(true))
                    .build();
        }

        private void registerWorkers(MassSdkApplication app, int workerCount) {
            for (int i = 0; i < workerCount; i++) {
                String workerId = "sdk-poll-worker-" + i;
                app.registerWorker(WorkerRegistration.builder()
                        .workerId(workerId)
                        .workerGroupId("sdk-load")
                        .supportedProjects(List.of("demoApp"))
                        .transportHint(WorkerTransportHints.POLLING)
                        .build());
            }
        }

        private List<PollWorker> startPollingWorkers(MassSdkApplication app, int workerCount) {
            List<PollWorker> workers = new ArrayList<>(workerCount);
            for (int i = 0; i < workerCount; i++) {
                String workerId = "sdk-poll-worker-" + i;
                PullWorkerSession session = app.pullWorker(workerId);
                PollWorker worker = new PollWorker(workerId, session, config, metrics, stopRequested, deliveryAttempts);
                worker.start();
                workers.add(worker);
            }
            return workers;
        }

        private MassTaskCreateRequest buildTaskRequest(int taskIndex) {
            return MassTaskCreateRequest.builder()
                    .userId("sdk-load")
                    .project("demoApp")
                    .taskName("sdk-polling-load-" + taskIndex)
                    .sharedConfig(Map.of(
                            "source", "SdkPollingWorkerLoadRunner",
                            "taskIndex", taskIndex
                    ))
                    .inputs(buildInputs(taskIndex))
                    .batchSize(config.batchSize())
                    .defaultMsgMaxRetryCount(config.maxRetryCount())
                    .openEnded(false)
                    .maxRuntimeSeconds(config.timeoutSeconds())
                    .build();
        }

        private List<Map<String, Object>> buildInputs(int taskIndex) {
            List<Map<String, Object>> inputs = new ArrayList<>(config.messagesPerTask());
            for (int messageIndex = 0; messageIndex < config.messagesPerTask(); messageIndex++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("taskIndex", taskIndex);
                input.put("seq", taskIndex * config.messagesPerTask() + messageIndex);
                input.put("target", "sdk-target-" + taskIndex + "-" + messageIndex);
                inputs.add(input);
            }
            return inputs;
        }

        private void waitForTerminalTasks(MassSdkApplication app, List<String> taskIds) throws Exception {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
            Set<String> pending = new LinkedHashSet<>(taskIds);
            while (!pending.isEmpty()) {
                require(System.nanoTime() < deadlineNanos,
                        "timed out before all SDK load-model tasks reached TERMINAL; pending=" + pending.size());
                pending.removeIf(taskId -> {
                    Task task = app.getTask(taskId);
                    return task != null && task.getStatus() == TaskStatus.TERMINAL;
                });
                if (!pending.isEmpty()) {
                    Thread.sleep(100L);
                }
            }
        }

        private FinalTaskStats collectFinalTaskStats(MassSdkApplication app, List<String> taskIds) {
            Map<String, Long> terminalReasons = new LinkedHashMap<>();
            int terminalTasks = 0;
            for (String taskId : taskIds) {
                Task task = app.getTask(taskId);
                require(task != null, "task should exist: " + taskId);
                require(task.getStatus() == TaskStatus.TERMINAL, "task should be terminal: " + taskId);
                terminalTasks++;
                TaskTerminalReason terminalReason = task.getTerminalReason();
                String terminalReasonName = terminalReason != null ? terminalReason.name() : "<null>";
                terminalReasons.merge(terminalReasonName, 1L, Long::sum);
            }
            return new FinalTaskStats(terminalTasks, terminalReasons);
        }

        private FinalMessageStats collectFinalMessageStats(MassSdkApplication app, List<String> taskIds) {
            long total = 0;
            long success = 0;
            long failed = 0;
            long expired = 0;
            for (String taskId : taskIds) {
                List<TaskMsg> messages = app.getTaskMessages(taskId);
                total += messages.size();
                for (TaskMsg message : messages) {
                    if (message.getStatus() == TaskMsgStatus.SUCCESS) {
                        success++;
                    } else if (message.getStatus() == TaskMsgStatus.FAILED) {
                        failed++;
                    } else if (message.getStatus() == TaskMsgStatus.EXPIRED) {
                        expired++;
                    }
                }
            }
            require(total == (long) config.taskCount() * config.messagesPerTask(),
                    "unexpected logical message count");
            if (config.retryFailureEveryNth() > 0) {
                require(success == total,
                        "retry-enabled SDK load model should converge to success for all logical messages");
            }
            return new FinalMessageStats(total, success, failed, expired);
        }

        private static Path writeReport(LoadConfig config,
                                        FinalTaskStats finalTaskStats,
                                        FinalMessageStats finalMessageStats,
                                        long wallNanos,
                                        RuntimeMetricsSnapshot metrics) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("config", config.toMap());
            report.put("wallClock", Map.of("totalMillis", nanosToMillis(wallNanos)));
            report.put("tasks", finalTaskStats.toMap());
            report.put("messages", finalMessageStats.toMap());
            report.put("runtime", metrics.toMap());

            Path reportDir = Path.of("xa-mass-testing", "target", "concurrency-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("sdk-polling-worker-load-" + timestamp + ".json");
            Files.writeString(reportPath, GSON.toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private static final class PollWorker implements AutoCloseable {
        private final String workerId;
        private final PullWorkerSession session;
        private final LoadConfig config;
        private final RuntimeMetrics metrics;
        private final AtomicBoolean stopRequested;
        private final Map<String, AtomicInteger> deliveryAttempts;
        private final ExecutorService processingExecutor;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private Thread pollThread;

        private PollWorker(String workerId,
                           PullWorkerSession session,
                           LoadConfig config,
                           RuntimeMetrics metrics,
                           AtomicBoolean stopRequested,
                           Map<String, AtomicInteger> deliveryAttempts) {
            this.workerId = workerId;
            this.session = session;
            this.config = config;
            this.metrics = metrics;
            this.stopRequested = stopRequested;
            this.deliveryAttempts = deliveryAttempts;
            this.processingExecutor = Executors.newFixedThreadPool(config.workerProcessingThreads(), r -> {
                Thread thread = new Thread(r, "SdkPollWorker-" + workerId + "-processor");
                thread.setDaemon(true);
                return thread;
            });
        }

        private void start() {
            session.connect("sdk-load-start");
            pollThread = new Thread(this::runLoop, "SdkPollWorker-" + workerId + "-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }

        private void runLoop() {
            try {
                while (running.get()) {
                    List<TaskDispatchItem> items = session.poll(config.pollBatchSize());
                    metrics.recordPoll(items);
                    if (items.isEmpty()) {
                        if (stopRequested.get()) {
                            break;
                        }
                        Thread.sleep(20L);
                        continue;
                    }
                    for (TaskDispatchItem item : items) {
                        processingExecutor.submit(() -> processItem(item));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        }

        private void processItem(TaskDispatchItem item) {
            int concurrent = metrics.onProcessingStart();
            try {
                if (config.processingDelayMillis() > 0) {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(config.processingDelayMillis() + 1));
                }
                String messageId = item.getMessageId();
                int attemptNo = deliveryAttempts
                        .computeIfAbsent(messageId, ignored -> new AtomicInteger())
                        .incrementAndGet();
                int seq = readSeq(item);
                boolean syntheticRetry = config.retryFailureEveryNth() > 0
                        && seq > 0
                        && seq % config.retryFailureEveryNth() == 0
                        && attemptNo == 1;
                if (syntheticRetry) {
                    metrics.syntheticRetryFailures.increment();
                }
                long startNanos = System.nanoTime();
                boolean submitted = session.submitResult(
                        item,
                        !syntheticRetry,
                        syntheticRetry ? "synthetic retryable failure" : "ok",
                        Map.of(
                                "workerId", workerId,
                                "logicalAttempt", attemptNo,
                                "seq", seq
                        )
                );
                metrics.onProcessingComplete(System.nanoTime() - startNanos, concurrent, submitted);
                require(submitted, "SDK pull worker result submission should succeed for " + messageId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                metrics.onProcessingFinish();
            }
        }

        private int readSeq(TaskDispatchItem item) {
            Object seq = item.getInput().get("seq");
            if (seq instanceof Number number) {
                return number.intValue();
            }
            return -1;
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            processingExecutor.shutdown();
            try {
                stopped.await(5, TimeUnit.SECONDS);
            } finally {
                session.disconnect("sdk-load-stop");
            }
            if (!processingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
            }
        }
    }

    private static final class RuntimeMetrics {
        private final LongAdder pollCalls = new LongAdder();
        private final LongAdder emptyPollCalls = new LongAdder();
        private final LongAdder dispatchedItems = new LongAdder();
        private final LongAdder resultSubmissions = new LongAdder();
        private final LongAdder syntheticRetryFailures = new LongAdder();
        private final LongAdder totalProcessingNanos = new LongAdder();
        private final AtomicInteger concurrentProcessing = new AtomicInteger();
        private final AtomicInteger maxConcurrentProcessing = new AtomicInteger();
        private final LongAccumulator maxPolledBatchSize = new LongAccumulator(Long::max, 0);

        private void recordPoll(List<TaskDispatchItem> items) {
            pollCalls.increment();
            if (items == null || items.isEmpty()) {
                emptyPollCalls.increment();
                return;
            }
            dispatchedItems.add(items.size());
            maxPolledBatchSize.accumulate(items.size());
        }

        private int onProcessingStart() {
            int active = concurrentProcessing.incrementAndGet();
            maxConcurrentProcessing.updateAndGet(current -> Math.max(current, active));
            return active;
        }

        private void onProcessingComplete(long durationNanos, int concurrent, boolean submitted) {
            totalProcessingNanos.add(durationNanos);
            if (submitted) {
                resultSubmissions.increment();
            }
            maxConcurrentProcessing.updateAndGet(current -> Math.max(current, concurrent));
        }

        private void onProcessingFinish() {
            concurrentProcessing.updateAndGet(current -> current > 0 ? current - 1 : 0);
        }

        private RuntimeMetricsSnapshot snapshot() {
            return new RuntimeMetricsSnapshot(
                    pollCalls.sum(),
                    emptyPollCalls.sum(),
                    dispatchedItems.sum(),
                    resultSubmissions.sum(),
                    syntheticRetryFailures.sum(),
                    nanosToMillis(totalProcessingNanos.sum()),
                    maxPolledBatchSize.get(),
                    maxConcurrentProcessing.get()
            );
        }
    }

    private record RuntimeMetricsSnapshot(long pollCalls,
                                          long emptyPollCalls,
                                          long dispatchedItems,
                                          long resultSubmissions,
                                          long syntheticRetryFailures,
                                          double totalProcessingMillis,
                                          long maxPolledBatchSize,
                                          int maxConcurrentProcessing) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "pollCalls", pollCalls,
                    "emptyPollCalls", emptyPollCalls,
                    "dispatchedItems", dispatchedItems,
                    "resultSubmissions", resultSubmissions,
                    "syntheticRetryFailures", syntheticRetryFailures,
                    "totalProcessingMillis", totalProcessingMillis,
                    "maxPolledBatchSize", maxPolledBatchSize,
                    "maxConcurrentProcessing", maxConcurrentProcessing
            );
        }
    }

    private record FinalTaskStats(int terminalTasks, Map<String, Long> terminalReasons) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "terminalTasks", terminalTasks,
                    "terminalReasons", terminalReasons
            );
        }
    }

    private record FinalMessageStats(long totalMessages,
                                     long successMessages,
                                     long failedMessages,
                                     long expiredMessages) {
        private Map<String, Object> toMap() {
            return Map.of(
                    "totalMessages", totalMessages,
                    "successMessages", successMessages,
                    "failedMessages", failedMessages,
                    "expiredMessages", expiredMessages
            );
        }
    }

    private record LoadConfig(int taskCount,
                              int messagesPerTask,
                              int workerCount,
                              int batchSize,
                              int pollBatchSize,
                              int workerProcessingThreads,
                              int processingDelayMillis,
                              int retryFailureEveryNth,
                              int maxRetryCount,
                              int timeoutSeconds) {
        private static LoadConfig fromSystemProperties() {
            LoadConfig config = new LoadConfig(
                    intProperty("mass.sdk.load.tasks", 16),
                    intProperty("mass.sdk.load.messagesPerTask", 32),
                    intProperty("mass.sdk.load.workers", 8),
                    intProperty("mass.sdk.load.batchSize", 4),
                    intProperty("mass.sdk.load.pollBatchSize", 4),
                    intProperty("mass.sdk.load.workerProcessingThreads", 2),
                    intProperty("mass.sdk.load.processingDelayMillis", 5),
                    intProperty("mass.sdk.load.retryFailureEveryNth", 0),
                    intProperty("mass.sdk.load.maxRetryCount", 2),
                    intProperty("mass.sdk.load.timeoutSeconds", 60)
            );
            require(config.taskCount > 0, "taskCount must be positive");
            require(config.messagesPerTask > 0, "messagesPerTask must be positive");
            require(config.workerCount > 0, "workerCount must be positive");
            require(config.batchSize > 0, "batchSize must be positive");
            require(config.pollBatchSize > 0, "pollBatchSize must be positive");
            require(config.workerProcessingThreads > 0, "workerProcessingThreads must be positive");
            require(config.processingDelayMillis >= 0, "processingDelayMillis must not be negative");
            require(config.timeoutSeconds > 0, "timeoutSeconds must be positive");
            if (config.retryFailureEveryNth > 0) {
                require(config.maxRetryCount > 0,
                        "maxRetryCount must be positive when retryFailureEveryNth is enabled");
            }
            return config;
        }

        private Map<String, Object> toMap() {
            return Map.of(
                    "taskCount", taskCount,
                    "messagesPerTask", messagesPerTask,
                    "workerCount", workerCount,
                    "batchSize", batchSize,
                    "pollBatchSize", pollBatchSize,
                    "workerProcessingThreads", workerProcessingThreads,
                    "processingDelayMillis", processingDelayMillis,
                    "retryFailureEveryNth", retryFailureEveryNth,
                    "maxRetryCount", maxRetryCount,
                    "timeoutSeconds", timeoutSeconds
            );
        }
    }

    private record LoadReport(LoadConfig config,
                              int createdTasks,
                              int terminalTasks,
                              Map<String, Long> terminalReasons,
                              long totalMessages,
                              long successMessages,
                              long failedMessages,
                              long expiredMessages,
                              double wallClockMillis,
                              long pollCalls,
                              long emptyPollCalls,
                              long dispatchedItems,
                              long resultSubmissions,
                              long syntheticRetryFailures,
                              long maxPolledBatchSize,
                              int maxConcurrentProcessing,
                              double totalProcessingMillis,
                              Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "SdkPollingLoad tasks=%d terminal=%d reasons=%s messages=%d success=%d failed=%d expired=%d "
                            + "wall=%.3fms polls=%d emptyPolls=%d dispatched=%d results=%d syntheticRetries=%d "
                            + "maxPollBatch=%d maxConcurrentProcessing=%d report=%s",
                    createdTasks,
                    terminalTasks,
                    terminalReasons,
                    totalMessages,
                    successMessages,
                    failedMessages,
                    expiredMessages,
                    wallClockMillis,
                    pollCalls,
                    emptyPollCalls,
                    dispatchedItems,
                    resultSubmissions,
                    syntheticRetryFailures,
                    maxPolledBatchSize,
                    maxConcurrentProcessing,
                    reportPath
            );
        }
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
