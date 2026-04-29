package com.xa.mass.testing.perf;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskManagerAssignmentRuntimePort;
import com.xa.mass.engine.TaskManagerRuntimeMaintenancePort;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskDispatchBinding;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.testing.support.TestingPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Mixed-workload smoke runner focused on lane isolation:
 * bulk background pressure should not prevent an interactive task from being
 * assigned and dispatched while bulk work is still active.
 */
public final class TaskWorkloadMixSmokeRunner {

    private TaskWorkloadMixSmokeRunner() {
    }

    public static void main(String[] args) throws Exception {
        SmokeConfig config = SmokeConfig.fromSystemProperties();
        SmokeReport report = new ScenarioRunner(config).run();
        System.out.println(report.toConsoleSummary());
        System.out.println("Task workload mix smoke report written to: " + report.reportPath());
    }

    private static final class ScenarioRunner {
        private final SmokeConfig config;

        private ScenarioRunner(SmokeConfig config) {
            this.config = config;
        }

        private SmokeReport run() throws Exception {
            TaskManager taskManager = new TaskManager(
                    new NoOpTaskScheduler(),
                    new InMemoryTaskStorage(),
                    new InMemoryTaskWorkRuntime());
            TaskEventService taskEvents = new TaskEventService(taskManager);
            WorkerManager workerManager = new WorkerManager();
            AssignmentRecordService recordService = new AssignmentRecordService();
            WorkloadTiming timing = new WorkloadTiming();
            ExecutorService callbackExecutor = Executors.newFixedThreadPool(config.callbackThreads(), r -> {
                Thread thread = new Thread(r, "TaskWorkloadMix-callback");
                thread.setDaemon(true);
                return thread;
            });
            CountDownLatch bulkTerminalLatch = new CountDownLatch(1);
            CountDownLatch interactiveTerminalLatch = new CountDownLatch(1);

            TaskMsgDispatchListener dispatchListener = (task, dispatchBindings) -> {
                timing.onDispatch(task, dispatchBindings.size());
                for (TaskDispatchBinding binding : dispatchBindings) {
                    callbackExecutor.submit(() -> handleBinding(taskManager, timing, task, binding.taskMsg()));
                }
            };

            TaskWorkerMatchingStrategy matchingStrategy = new DeterministicMatchingStrategy(workerManager);
            TaskManagerAssignmentRuntimePort assignmentRuntimePort =
                    new TaskManagerAssignmentRuntimePort(taskManager);
            SimpleTaskMsgAssignListener msgAssignListener =
                    new SimpleTaskMsgAssignListener(
                            assignmentRuntimePort,
                            workerManager,
                            recordService,
                            dispatchListener
                    );
            TaskWorkerAssignListener workerAssignListener =
                    new TaskWorkerAssignListener(
                            matchingStrategy,
                            workerManager,
                            msgAssignListener,
                            assignmentRuntimePort,
                            taskEvents
                    );
            TaskAssignWorker assignWorker = new TaskAssignWorker(workerAssignListener, config.assignmentRetryDelayMillis());
            TaskResourceReleaseListener releaseListener =
                    new TaskResourceReleaseListener(new TaskManagerRuntimeMaintenancePort(taskManager), workerManager);

            try {
                registerWorkers(workerManager, config.workerCount());
                taskEvents.addTaskReadyListener(assignWorker::submit);
                taskEvents.addTaskDispatchListener(assignWorker::submit);
                taskEvents.addTaskMessageAttemptClosedListener(releaseListener::onTaskMessageAttemptClosed);
                taskEvents.addTaskTerminalListener(releaseListener::onTaskTerminal);
                taskEvents.addTaskTerminalListener(task -> {
                    if (TaskWorkloadClass.BULK == task.getWorkloadClass()) {
                        timing.onTerminal(task);
                        bulkTerminalLatch.countDown();
                    } else if (TaskWorkloadClass.INTERACTIVE == task.getWorkloadClass()) {
                        timing.onTerminal(task);
                        interactiveTerminalLatch.countDown();
                    }
                });
                assignWorker.start();

                Task bulkTask = taskManager.createTask(buildBulkRequest(config));
                timing.onCreated(bulkTask);
                require(taskManager.approveTask(bulkTask.getTid()), "bulk task should approve");
                timing.onApproved(bulkTask);
                require(timing.awaitBulkFirstDispatch(config.awaitSeconds(), TimeUnit.SECONDS),
                        "bulk task should start dispatching before interactive submission");

                Thread.sleep(config.interactiveSubmitDelayMillis());

                Task interactiveTask = taskManager.createTask(buildInteractiveRequest(config));
                timing.onCreated(interactiveTask);
                require(taskManager.approveTask(interactiveTask.getTid()), "interactive task should approve");
                timing.onApproved(interactiveTask);

                require(interactiveTerminalLatch.await(config.awaitSeconds(), TimeUnit.SECONDS),
                        "interactive task should converge");
                require(bulkTerminalLatch.await(config.awaitSeconds(), TimeUnit.SECONDS),
                        "bulk task should converge");

                callbackExecutor.shutdown();
                require(callbackExecutor.awaitTermination(15, TimeUnit.SECONDS),
                        "callback executor did not terminate");
                assignWorker.stop();

                SmokeObservation observation = timing.snapshot(config);
                require(observation.interactiveFirstDispatchMillis() >= 0, "interactive first dispatch timing missing");
                require(observation.bulkTerminalMillis() >= 0, "bulk terminal timing missing");
                require(observation.interactiveDispatchedBeforeBulkTerminal(),
                        "interactive dispatch should occur before bulk terminal under mixed workload smoke");
                require(observation.interactiveFirstDispatchMillis() <= config.interactiveFirstDispatchWarnMillis(),
                        "interactive dispatch latency exceeded smoke bound: " + observation.interactiveFirstDispatchMillis());

                Path reportPath = writeReport(config, observation);
                return new SmokeReport(config, observation, reportPath);
            } finally {
                assignWorker.stop();
                callbackExecutor.shutdownNow();
            }
        }

        private void handleBinding(TaskManager taskManager,
                                   WorkloadTiming timing,
                                   Task task,
                                   TaskMsg taskMsg) {
            int delayMillis = task.getWorkloadClass() == TaskWorkloadClass.INTERACTIVE
                    ? config.interactiveProcessingDelayMillis()
                    : config.bulkProcessingDelayMillis();
            timing.onCallbackStart(task);
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                boolean accepted = taskManager.handleTaskMessageResult(
                        task.getTid(),
                        taskMsg.getMessageId(),
                        true,
                        "ok",
                        null,
                        Map.of("runner", "TaskWorkloadMixSmokeRunner")
                );
                require(accepted, "result callback should be accepted for " + taskMsg.getMessageId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("callback interrupted", e);
            } finally {
                timing.onCallbackFinish(task);
            }
        }

        private static TaskCreateRequestDto buildBulkRequest(SmokeConfig config) {
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("bulk-workload-smoke");
            dto.setProject("demoApp");
            dto.setUserId("workload-smoke");
            dto.setWorkloadClass(TaskWorkloadClass.BULK);
            dto.setBatchSize(config.bulkBatchSize());
            dto.setInputs(buildInputs("bulk", config.bulkMessages()));
            dto.setSharedConfig(Map.of("source", "TaskWorkloadMixSmokeRunner", "workload", "bulk"));
            return dto;
        }

        private static TaskCreateRequestDto buildInteractiveRequest(SmokeConfig config) {
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("interactive-workload-smoke");
            dto.setProject("demoApp");
            dto.setUserId("workload-smoke");
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            dto.setBatchSize(config.interactiveBatchSize());
            dto.setInputs(buildInputs("interactive", config.interactiveMessages()));
            dto.setSharedConfig(Map.of("source", "TaskWorkloadMixSmokeRunner", "workload", "interactive"));
            return dto;
        }

        private static List<Map<String, Object>> buildInputs(String prefix, int count) {
            List<Map<String, Object>> inputs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("target", prefix + "-target-" + i);
                input.put("seq", i);
                inputs.add(input);
            }
            return inputs;
        }

        private static void registerWorkers(WorkerManager workerManager, int workerCount) {
            for (int i = 0; i < workerCount; i++) {
                Worker worker = new Worker();
                worker.setWorkerId("workload-smoke-worker-" + i);
                worker.setAgentVersion("workload-smoke");
                worker.setSupportedProjects(List.of("demoApp"));
                worker.setStatus(WorkerStatus.ONLINE);
                worker.setLastHeartbeat(LocalDateTime.now());
                workerManager.addWorker(worker);

                WorkerContext workerContext = new WorkerContext();
                workerContext.setWorkerContextId("workload-smoke-context-" + i);
                workerContext.setWorkerId(worker.getWorkerId());
                workerContext.setProject("demoApp");
                workerContext.setRoutingTags(Set.of("default"));
                workerManager.addWorkerContext(workerContext);
            }
        }

        private static Path writeReport(SmokeConfig config, SmokeObservation observation) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            report.put("config", config.toMap());
            report.put("observation", observation.toMap());
            Path reportDir = TestingPaths.reportDir("perf-reports");
            Files.createDirectories(reportDir);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path reportPath = reportDir.resolve("task-workload-mix-smoke-" + timestamp + ".json");
            Files.writeString(reportPath, toJson(report), StandardCharsets.UTF_8);
            return reportPath;
        }
    }

    private static final class DeterministicMatchingStrategy implements TaskWorkerMatchingStrategy {
        private final WorkerManager workerManager;

        private DeterministicMatchingStrategy(WorkerManager workerManager) {
            this.workerManager = workerManager;
        }

        @Override
        public List<MatchedWorkerContext> matchWorkers(Task task, int maxWorkerCount) {
            List<MatchedWorkerContext> matched = new ArrayList<>();
            for (Worker worker : workerManager.getAllWorkers()) {
                if (matched.size() >= maxWorkerCount) {
                    break;
                }
                if (!worker.isAvailable() || !worker.supportsProject(task.getProject())) {
                    continue;
                }
                if (!workerManager.tryLockWorker(worker.getWorkerId())) {
                    continue;
                }
                WorkerContext selectedContext = null;
                for (WorkerContext workerContext : workerManager.getWorkerContexts(worker.getWorkerId())) {
                    if (workerContext != null
                            && workerContext.isAllocatable()
                            && Objects.equals(task.getProject(), workerContext.getProject())) {
                        selectedContext = workerContext;
                        break;
                    }
                }
                if (selectedContext == null) {
                    workerManager.unlockWorker(worker.getWorkerId());
                    continue;
                }
                matched.add(new MatchedWorkerContext(worker, selectedContext));
            }
            return matched;
        }
    }

    private static final class WorkloadTiming {
        private final CountDownLatch bulkFirstDispatchLatch = new CountDownLatch(1);
        private final Map<String, TaskWorkloadClass> workloadByTaskId = new ConcurrentHashMap<>();
        private final Map<String, Long> approvedAtNanos = new ConcurrentHashMap<>();
        private final Map<String, Long> firstDispatchAtNanos = new ConcurrentHashMap<>();
        private final Map<String, Long> terminalAtNanos = new ConcurrentHashMap<>();
        private final Map<String, TaskTerminalReason> terminalReasonByTaskId = new ConcurrentHashMap<>();
        private final Map<TaskWorkloadClass, LongAdder> dispatchCyclesByWorkload = new ConcurrentHashMap<>();
        private final Map<TaskWorkloadClass, LongAdder> dispatchItemsByWorkload = new ConcurrentHashMap<>();
        private final AtomicInteger bulkCallbacksInFlight = new AtomicInteger();
        private final AtomicLong interactiveBulkCallbacksAtFirstDispatch = new AtomicLong(-1L);
        private final AtomicLong interactiveBulkTaskStillRunningAtFirstDispatch = new AtomicLong(0L);
        private volatile String bulkTaskId;
        private volatile String interactiveTaskId;

        private void onCreated(Task task) {
            workloadByTaskId.put(task.getTid(), task.getWorkloadClass());
            if (task.getWorkloadClass() == TaskWorkloadClass.BULK) {
                bulkTaskId = task.getTid();
            } else if (task.getWorkloadClass() == TaskWorkloadClass.INTERACTIVE) {
                interactiveTaskId = task.getTid();
            }
        }

        private void onApproved(Task task) {
            approvedAtNanos.put(task.getTid(), System.nanoTime());
        }

        private void onDispatch(Task task, int itemCount) {
            long now = System.nanoTime();
            dispatchCyclesByWorkload.computeIfAbsent(task.getWorkloadClass(), ignored -> new LongAdder()).increment();
            dispatchItemsByWorkload.computeIfAbsent(task.getWorkloadClass(), ignored -> new LongAdder()).add(itemCount);
            firstDispatchAtNanos.putIfAbsent(task.getTid(), now);
            if (task.getWorkloadClass() == TaskWorkloadClass.BULK) {
                bulkFirstDispatchLatch.countDown();
            } else if (task.getWorkloadClass() == TaskWorkloadClass.INTERACTIVE) {
                interactiveBulkCallbacksAtFirstDispatch.compareAndSet(-1L, bulkCallbacksInFlight.get());
                boolean bulkStillRunning = bulkTaskId != null && !terminalAtNanos.containsKey(bulkTaskId);
                if (bulkStillRunning) {
                    interactiveBulkTaskStillRunningAtFirstDispatch.compareAndSet(0L, 1L);
                }
            }
        }

        private boolean awaitBulkFirstDispatch(long timeout, TimeUnit unit) throws InterruptedException {
            return bulkFirstDispatchLatch.await(timeout, unit);
        }

        private void onCallbackStart(Task task) {
            if (task.getWorkloadClass() == TaskWorkloadClass.BULK) {
                bulkCallbacksInFlight.incrementAndGet();
            }
        }

        private void onCallbackFinish(Task task) {
            if (task.getWorkloadClass() == TaskWorkloadClass.BULK) {
                bulkCallbacksInFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
            }
        }

        private void onTerminal(Task task) {
            terminalAtNanos.putIfAbsent(task.getTid(), System.nanoTime());
            terminalReasonByTaskId.put(task.getTid(), task.getTerminalReason());
        }

        private SmokeObservation snapshot(SmokeConfig config) {
            return new SmokeObservation(
                    config.workerCount(),
                    config.bulkMessages(),
                    config.interactiveMessages(),
                    millisBetweenApprovedAndEvent(bulkTaskId, firstDispatchAtNanos),
                    millisBetweenApprovedAndEvent(interactiveTaskId, firstDispatchAtNanos),
                    millisBetweenApprovedAndEvent(bulkTaskId, terminalAtNanos),
                    millisBetweenApprovedAndEvent(interactiveTaskId, terminalAtNanos),
                    firstDispatchBeforeBulkTerminal(),
                    interactiveBulkCallbacksAtFirstDispatch.get(),
                    interactiveBulkTaskStillRunningAtFirstDispatch.get() > 0,
                    sum(dispatchCyclesByWorkload.get(TaskWorkloadClass.BULK)),
                    sum(dispatchCyclesByWorkload.get(TaskWorkloadClass.INTERACTIVE)),
                    sum(dispatchItemsByWorkload.get(TaskWorkloadClass.BULK)),
                    sum(dispatchItemsByWorkload.get(TaskWorkloadClass.INTERACTIVE)),
                    terminalReasonName(bulkTaskId),
                    terminalReasonName(interactiveTaskId)
            );
        }

        private long millisBetweenApprovedAndEvent(String taskId, Map<String, Long> eventTimes) {
            if (taskId == null) {
                return -1L;
            }
            Long approvedAt = approvedAtNanos.get(taskId);
            Long eventAt = eventTimes.get(taskId);
            if (approvedAt == null || eventAt == null || eventAt < approvedAt) {
                return -1L;
            }
            return TimeUnit.NANOSECONDS.toMillis(eventAt - approvedAt);
        }

        private boolean firstDispatchBeforeBulkTerminal() {
            if (interactiveTaskId == null || bulkTaskId == null) {
                return false;
            }
            Long interactiveDispatchAt = firstDispatchAtNanos.get(interactiveTaskId);
            Long bulkTerminalAt = terminalAtNanos.get(bulkTaskId);
            return interactiveDispatchAt != null && bulkTerminalAt != null && interactiveDispatchAt < bulkTerminalAt;
        }

        private String terminalReasonName(String taskId) {
            TaskTerminalReason reason = taskId != null ? terminalReasonByTaskId.get(taskId) : null;
            return reason != null ? reason.name() : null;
        }

        private long sum(LongAdder adder) {
            return adder != null ? adder.sum() : 0L;
        }
    }

    private record SmokeConfig(int workerCount,
                               int bulkMessages,
                               int bulkBatchSize,
                               int interactiveMessages,
                               int interactiveBatchSize,
                               int bulkProcessingDelayMillis,
                               int interactiveProcessingDelayMillis,
                               int callbackThreads,
                               long interactiveSubmitDelayMillis,
                               long interactiveFirstDispatchWarnMillis,
                               long assignmentRetryDelayMillis,
                               long awaitSeconds) {
        private static SmokeConfig fromSystemProperties() {
            int workerCount = intProperty("mass.workload.smoke.workers", 5);
            int bulkMessages = intProperty("mass.workload.smoke.bulkMessages", 160);
            int reservedInteractiveWorkers = 1;
            int bulkWorkersTarget = Math.max(workerCount - reservedInteractiveWorkers, 1);
            int defaultBulkBatchSize = Math.max((int) Math.ceil((double) bulkMessages / bulkWorkersTarget), 1);
            return new SmokeConfig(
                    workerCount,
                    bulkMessages,
                    intProperty("mass.workload.smoke.bulkBatchSize", defaultBulkBatchSize),
                    intProperty("mass.workload.smoke.interactiveMessages", 2),
                    intProperty("mass.workload.smoke.interactiveBatchSize", 1),
                    intProperty("mass.workload.smoke.bulkProcessingDelayMillis", 40),
                    intProperty("mass.workload.smoke.interactiveProcessingDelayMillis", 2),
                    intProperty("mass.workload.smoke.callbackThreads", Math.max(workerCount, 8)),
                    longProperty("mass.workload.smoke.interactiveSubmitDelayMillis", 150L),
                    longProperty("mass.workload.smoke.interactiveFirstDispatchWarnMillis", 2_000L),
                    longProperty("mass.workload.smoke.assignmentRetryDelayMillis", 25L),
                    longProperty("mass.workload.smoke.awaitSeconds", 60L)
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("workerCount", workerCount);
            values.put("bulkMessages", bulkMessages);
            values.put("bulkBatchSize", bulkBatchSize);
            values.put("interactiveMessages", interactiveMessages);
            values.put("interactiveBatchSize", interactiveBatchSize);
            values.put("bulkProcessingDelayMillis", bulkProcessingDelayMillis);
            values.put("interactiveProcessingDelayMillis", interactiveProcessingDelayMillis);
            values.put("callbackThreads", callbackThreads);
            values.put("interactiveSubmitDelayMillis", interactiveSubmitDelayMillis);
            values.put("interactiveFirstDispatchWarnMillis", interactiveFirstDispatchWarnMillis);
            values.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            values.put("awaitSeconds", awaitSeconds);
            return values;
        }
    }

    private record SmokeObservation(int workerCount,
                                    int bulkMessages,
                                    int interactiveMessages,
                                    long bulkFirstDispatchMillis,
                                    long interactiveFirstDispatchMillis,
                                    long bulkTerminalMillis,
                                    long interactiveTerminalMillis,
                                    boolean interactiveDispatchedBeforeBulkTerminal,
                                    long bulkCallbacksInFlightAtInteractiveFirstDispatch,
                                    boolean interactiveDispatchedWhileBulkTaskStillRunning,
                                    long bulkDispatchCycles,
                                    long interactiveDispatchCycles,
                                    long bulkDispatchItems,
                                    long interactiveDispatchItems,
                                    String bulkTerminalReason,
                                    String interactiveTerminalReason) {
        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("workerCount", workerCount);
            values.put("bulkMessages", bulkMessages);
            values.put("interactiveMessages", interactiveMessages);
            values.put("bulkFirstDispatchMillis", bulkFirstDispatchMillis);
            values.put("interactiveFirstDispatchMillis", interactiveFirstDispatchMillis);
            values.put("bulkTerminalMillis", bulkTerminalMillis);
            values.put("interactiveTerminalMillis", interactiveTerminalMillis);
            values.put("interactiveDispatchedBeforeBulkTerminal", interactiveDispatchedBeforeBulkTerminal);
            values.put("bulkCallbacksInFlightAtInteractiveFirstDispatch", bulkCallbacksInFlightAtInteractiveFirstDispatch);
            values.put("interactiveDispatchedWhileBulkTaskStillRunning", interactiveDispatchedWhileBulkTaskStillRunning);
            values.put("bulkDispatchCycles", bulkDispatchCycles);
            values.put("interactiveDispatchCycles", interactiveDispatchCycles);
            values.put("bulkDispatchItems", bulkDispatchItems);
            values.put("interactiveDispatchItems", interactiveDispatchItems);
            values.put("bulkTerminalReason", bulkTerminalReason);
            values.put("interactiveTerminalReason", interactiveTerminalReason);
            return values;
        }
    }

    private record SmokeReport(SmokeConfig config, SmokeObservation observation, Path reportPath) {
        private String toConsoleSummary() {
            return String.format(Locale.ROOT,
                    "TaskWorkloadMixSmoke workers=%d bulkMessages=%d interactiveMessages=%d "
                            + "interactiveFirstDispatch=%dms bulkTerminal=%dms beforeBulkTerminal=%s "
                            + "bulkCallbacksAtInteractiveDispatch=%d report=%s",
                    config.workerCount(),
                    config.bulkMessages(),
                    config.interactiveMessages(),
                    observation.interactiveFirstDispatchMillis(),
                    observation.bulkTerminalMillis(),
                    observation.interactiveDispatchedBeforeBulkTerminal(),
                    observation.bulkCallbacksInFlightAtInteractiveFirstDispatch(),
                    reportPath);
        }
    }

    private static final class NoOpTaskScheduler implements TaskScheduler {
        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success(List.of());
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean handleTaskMsgCompletion(TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean handleTaskMsgFailure(TaskMsg taskMsg, String errorMessage) {
            return true;
        }

        @Override
        public boolean retryTaskMsg(TaskMsg taskMsg) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            return true;
        }
    }

    private static int intProperty(String key, int defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(raw.trim());
    }

    private static long longProperty(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(raw.trim());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String stringValue) {
            return "\"" + escapeJson(stringValue) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Path path) {
            return toJson(path.toString());
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add("  " + toJson(String.valueOf(entry.getKey())) + ": "
                        + indentJson(toJson(entry.getValue())));
            }
            builder.append(String.join(",\n", entries));
            builder.append('\n').append('}');
            return builder.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(indentJson(toJson(item)));
            }
            return "[\n  " + String.join(",\n  ", items) + "\n]";
        }
        return toJson(String.valueOf(value));
    }

    private static String indentJson(String json) {
        return json.replace("\n", "\n  ");
    }

    private static String escapeJson(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
