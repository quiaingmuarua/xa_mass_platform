package com.xa.mass.testing.perf;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskManagerAssignmentRuntimePort;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskManagerRuntimeMaintenancePort;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.runtime.api.TaskWorkStats;
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
 * Mixed-workload smoke runner focused on delayed interactive retry wakeup.
 *
 * <p>The scenario keeps bulk work active in the background while one
 * interactive message fails once, becomes delayed in the runtime queue, and is
 * later redispatched. This validates that workload-aware retry visibility does
 * not silently fall back to an immediate empty assignment cycle and records
 * whether the retry redispatch actually overlaps active bulk pressure.
 */
public final class TaskInteractiveRetryWakeupSmokeRunner {

    private TaskInteractiveRetryWakeupSmokeRunner() {
    }

    public static void main(String[] args) throws Exception {
        SmokeConfig config = SmokeConfig.fromSystemProperties();
        SmokeReport report = new ScenarioRunner(config).run();
        System.out.println(report.toConsoleSummary());
        System.out.println("Interactive retry wakeup smoke report written to: " + report.reportPath());
    }

    private static final class ScenarioRunner {
        private final SmokeConfig config;

        private ScenarioRunner(SmokeConfig config) {
            this.config = config;
        }

        private SmokeReport run() throws Exception {
            InMemoryTaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
            InMemoryTaskStorage taskStorage = new InMemoryTaskStorage();
            TaskManager taskManager = new TaskManager(
                    new NoOpTaskScheduler(),
                    taskStorage,
                    taskStorage,
                    taskWorkRuntime);
            TaskCommandService taskCommands = new TaskCommandService(taskManager);
            TaskEventService taskEvents = new TaskEventService(taskManager);
            TaskResultIngestFacade taskResultIngestFacade = new TaskManagerResultIngestFacade(taskManager);
            WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
            AssignmentRecordService recordService = new AssignmentRecordService();
            RetryTiming timing = new RetryTiming();
            ExecutorService bulkCallbackExecutor = Executors.newFixedThreadPool(config.bulkCallbackThreads(), r -> {
                Thread thread = new Thread(r, "TaskInteractiveRetryWakeup-callback");
                thread.setDaemon(true);
                return thread;
            });
            ExecutorService interactiveCallbackExecutor = Executors.newFixedThreadPool(
                    config.interactiveCallbackThreads(), r -> {
                        Thread thread = new Thread(r, "TaskInteractiveRetryWakeup-interactive-callback");
                        thread.setDaemon(true);
                        return thread;
                    });
            CountDownLatch bulkTerminalLatch = new CountDownLatch(1);
            CountDownLatch interactiveTerminalLatch = new CountDownLatch(1);
            AtomicInteger callbackSequence = new AtomicInteger();
            Map<String, AtomicInteger> interactiveAttempts = new ConcurrentHashMap<>();
            Map<String, TaskWorkloadClass> workloadByTaskId = new ConcurrentHashMap<>();

            TaskDispatchBatchListener dispatchListener = (task, dispatchBindings) -> {
                TaskWorkloadClass workloadClass = workloadByTaskId.get(task.taskId());
                timing.onDispatch(task.taskId(), workloadClass, dispatchBindings.size());
                for (TaskDispatchBinding binding : dispatchBindings) {
                    ExecutorService callbackExecutor = workloadClass == TaskWorkloadClass.INTERACTIVE
                            ? interactiveCallbackExecutor
                            : bulkCallbackExecutor;
                    callbackExecutor.submit(() -> handleBinding(
                            taskResultIngestFacade,
                            taskWorkRuntime,
                            timing,
                            interactiveAttempts,
                            workloadByTaskId,
                            task,
                            binding.taskMsg(),
                            callbackSequence.incrementAndGet()
                    ));
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

                Task bulkTask = taskCommands.createTask(buildBulkRequest(config));
                workloadByTaskId.put(bulkTask.getTid(), bulkTask.getWorkloadClass());
                timing.onCreated(bulkTask);
                require(taskCommands.approveTask(bulkTask.getTid()), "bulk task should approve");
                timing.onApproved(bulkTask);
                require(timing.awaitBulkFirstDispatch(config.awaitSeconds(), TimeUnit.SECONDS),
                        "bulk task should start dispatching before interactive submission");

                Thread.sleep(config.interactiveSubmitDelayMillis());

                Task interactiveTask = taskCommands.createTask(buildInteractiveRequest(config));
                workloadByTaskId.put(interactiveTask.getTid(), interactiveTask.getWorkloadClass());
                timing.onCreated(interactiveTask);
                require(taskCommands.approveTask(interactiveTask.getTid()), "interactive task should approve");
                timing.onApproved(interactiveTask);

                require(timing.awaitInteractiveFailure(config.awaitSeconds(), TimeUnit.SECONDS),
                        "interactive first callback should fail once and trigger retry");

                require(timing.awaitInteractiveRetryDispatch(config.awaitSeconds(), TimeUnit.SECONDS),
                        "interactive retry dispatch should happen after delayed wakeup");
                require(interactiveTerminalLatch.await(config.awaitSeconds(), TimeUnit.SECONDS),
                        "interactive task should converge");
                require(bulkTerminalLatch.await(config.awaitSeconds(), TimeUnit.SECONDS),
                        "bulk task should converge");

                interactiveCallbackExecutor.shutdown();
                bulkCallbackExecutor.shutdown();
                require(interactiveCallbackExecutor.awaitTermination(15, TimeUnit.SECONDS),
                        "interactive callback executor did not terminate");
                require(bulkCallbackExecutor.awaitTermination(15, TimeUnit.SECONDS),
                        "bulk callback executor did not terminate");
                assignWorker.stop();

                SmokeObservation observation = timing.snapshot(config);
                require(observation.interactiveFirstDispatchMillis() >= 0, "interactive first dispatch timing missing");
                require(observation.interactiveRetryDispatchMillis() >= 0, "interactive retry dispatch timing missing");
                require(observation.interactiveDispatchCountBeforeWakeup() == 1,
                        "interactive retry became visible before the delayed wakeup window closed");
                require(observation.interactiveDelayedCountBeforeWakeup() >= 1,
                        "interactive retry should stay delayed before wakeup");
                require(observation.interactiveRetryDispatchDelayMillis() >= config.minRetryDispatchDelayMillis(),
                        "interactive retry dispatch happened too early: "
                                + observation.interactiveRetryDispatchDelayMillis() + "ms");
                require(observation.interactiveRetryDispatchedBeforeBulkTerminal(),
                        "interactive retry dispatch should happen before bulk terminal");
                require(observation.interactiveRetryDispatchedWhileBulkTaskStillRunning(),
                        "interactive retry dispatch should occur while bulk task is still running");
                require(observation.bulkCallbacksInFlightAtInteractiveRetryDispatch() > 0,
                        "interactive retry dispatch should overlap active bulk callbacks");

                Path reportPath = writeReport(config, observation);
                return new SmokeReport(config, observation, reportPath);
            } finally {
                assignWorker.stop();
                interactiveCallbackExecutor.shutdownNow();
                bulkCallbackExecutor.shutdownNow();
            }
        }

        private void handleBinding(TaskResultIngestFacade taskResultIngestFacade,
                                   InMemoryTaskWorkRuntime taskWorkRuntime,
                                   RetryTiming timing,
                                   Map<String, AtomicInteger> interactiveAttempts,
                                   Map<String, TaskWorkloadClass> workloadByTaskId,
                                   TaskDispatchContext task,
                                   TaskMsg taskMsg,
                                   int callbackSeq) {
            TaskWorkloadClass workloadClass = workloadByTaskId.get(task.taskId());
            boolean interactive = workloadClass == TaskWorkloadClass.INTERACTIVE;
            int attemptNo = interactive
                    ? interactiveAttempts.computeIfAbsent(taskMsg.getMessageId(), ignored -> new AtomicInteger())
                    .incrementAndGet()
                    : 1;
            int delayMillis = interactive
                    ? (attemptNo == 1
                    ? config.interactiveFailureProcessingDelayMillis()
                    : config.interactiveSuccessProcessingDelayMillis())
                    : config.bulkProcessingDelayMillis();
            timing.onCallbackStart(task.taskId(), workloadClass);
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                boolean success = !interactive || attemptNo > 1;
                String detail = success ? "ok" : "synthetic retryable failure";
                String errorCode = success ? null : "SYNTHETIC_RETRY";
                boolean accepted = taskResultIngestFacade.handleTaskMessageResult(
                        task.taskId(),
                        taskMsg.getMessageId(),
                        success,
                        detail,
                        errorCode,
                        Map.of(
                                "runner", "TaskInteractiveRetryWakeupSmokeRunner",
                                "callbackSeq", callbackSeq,
                                "attemptNo", attemptNo
                        )
                );
                require(accepted, "result callback should be accepted for " + taskMsg.getMessageId());
                if (interactive && attemptNo == 1) {
                    timing.onInteractiveFailure(task.taskId(), taskMsg, taskWorkRuntime.stats(task.taskId()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("callback interrupted", e);
            } finally {
                timing.onCallbackFinish(task.taskId(), workloadClass);
            }
        }

        private static TaskCreateRequestDto buildBulkRequest(SmokeConfig config) {
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("bulk-retry-wakeup-smoke");
            dto.setProject("demoApp");
            dto.setUserId("retry-wakeup-smoke");
            dto.setWorkloadClass(TaskWorkloadClass.BULK);
            dto.setBatchSize(config.bulkBatchSize());
            dto.setInputs(buildInputs("bulk", config.bulkMessages()));
            dto.setSharedConfig(Map.of("source", "TaskInteractiveRetryWakeupSmokeRunner", "workload", "bulk"));
            return dto;
        }

        private static TaskCreateRequestDto buildInteractiveRequest(SmokeConfig config) {
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("interactive-retry-wakeup-smoke");
            dto.setProject("demoApp");
            dto.setUserId("retry-wakeup-smoke");
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            dto.setBatchSize(1);
            dto.setDefaultMsgMaxRetryCount(1);
            dto.setInputs(buildInputs("interactive", 1));
            dto.setSharedConfig(Map.of("source", "TaskInteractiveRetryWakeupSmokeRunner", "workload", "interactive"));
            return dto;
        }

        private static List<Map<String, Object>> buildInputs(String prefix, int count) {
            List<Map<String, Object>> inputs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("target", prefix + "-target-" + i);
                input.put("seq", i);
                input.put("requestId", UUID.randomUUID().toString());
                inputs.add(input);
            }
            return inputs;
        }

        private static void registerWorkers(WorkerManager workerManager, int workerCount) {
            for (int i = 0; i < workerCount; i++) {
                Worker worker = new Worker();
                worker.setWorkerId("retry-wakeup-worker-" + i);
                worker.setAgentVersion("retry-wakeup-smoke");
                worker.setSupportedProjects(List.of("demoApp"));
                worker.setStatus(WorkerStatus.ONLINE);
                worker.setLastHeartbeat(LocalDateTime.now());
                workerManager.addWorker(worker);

                WorkerContext workerContext = new WorkerContext();
                workerContext.setWorkerContextId("retry-wakeup-context-" + i);
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
            Path reportPath = reportDir.resolve("task-interactive-retry-wakeup-smoke-" + timestamp + ".json");
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

    private static final class RetryTiming {
        private final CountDownLatch bulkFirstDispatchLatch = new CountDownLatch(1);
        private final CountDownLatch interactiveFailureLatch = new CountDownLatch(1);
        private final CountDownLatch interactiveRetryDispatchLatch = new CountDownLatch(1);
        private final Map<String, Long> approvedAtNanos = new ConcurrentHashMap<>();
        private final Map<String, Long> terminalAtNanos = new ConcurrentHashMap<>();
        private final Map<String, TaskTerminalReason> terminalReasonByTaskId = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> dispatchCountByTaskId = new ConcurrentHashMap<>();
        private final Map<TaskWorkloadClass, LongAdder> dispatchCyclesByWorkload = new ConcurrentHashMap<>();
        private final Map<TaskWorkloadClass, LongAdder> dispatchItemsByWorkload = new ConcurrentHashMap<>();
        private final AtomicInteger bulkCallbacksInFlight = new AtomicInteger();
        private final AtomicLong interactiveFirstDispatchAtNanos = new AtomicLong(-1L);
        private final AtomicLong interactiveFailureCompletedAtNanos = new AtomicLong(-1L);
        private final AtomicLong interactiveRetryDispatchAtNanos = new AtomicLong(-1L);
        private final AtomicLong interactiveDelayedCountBeforeWakeup = new AtomicLong(-1L);
        private final AtomicLong interactiveDispatchCountBeforeWakeup = new AtomicLong(-1L);
        private final AtomicLong bulkCallbacksAtInteractiveRetryDispatch = new AtomicLong(-1L);
        private final AtomicLong interactiveBulkTaskStillRunningAtRetryDispatch = new AtomicLong(0L);
        private volatile String bulkTaskId;
        private volatile String interactiveTaskId;

        private void onCreated(Task task) {
            if (task.getWorkloadClass() == TaskWorkloadClass.BULK) {
                bulkTaskId = task.getTid();
            } else if (task.getWorkloadClass() == TaskWorkloadClass.INTERACTIVE) {
                interactiveTaskId = task.getTid();
            }
        }

        private void onApproved(Task task) {
            approvedAtNanos.put(task.getTid(), System.nanoTime());
        }

        private void onDispatch(String taskId, TaskWorkloadClass workloadClass, int itemCount) {
            if (taskId == null || workloadClass == null) {
                return;
            }
            long now = System.nanoTime();
            dispatchCyclesByWorkload.computeIfAbsent(workloadClass, ignored -> new LongAdder()).increment();
            dispatchItemsByWorkload.computeIfAbsent(workloadClass, ignored -> new LongAdder()).add(itemCount);
            int dispatchCount = dispatchCountByTaskId
                    .computeIfAbsent(taskId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (workloadClass == TaskWorkloadClass.BULK) {
                bulkFirstDispatchLatch.countDown();
                return;
            }
            if (dispatchCount == 1) {
                interactiveFirstDispatchAtNanos.compareAndSet(-1L, now);
                return;
            }
            if (dispatchCount == 2) {
                interactiveRetryDispatchAtNanos.compareAndSet(-1L, now);
                bulkCallbacksAtInteractiveRetryDispatch.compareAndSet(-1L, bulkCallbacksInFlight.get());
                boolean bulkStillRunning = bulkTaskId != null && !terminalAtNanos.containsKey(bulkTaskId);
                if (bulkStillRunning) {
                    interactiveBulkTaskStillRunningAtRetryDispatch.compareAndSet(0L, 1L);
                }
                interactiveRetryDispatchLatch.countDown();
            }
        }

        private void onCallbackStart(String taskId, TaskWorkloadClass workloadClass) {
            if (taskId == null || workloadClass == null) {
                return;
            }
            if (workloadClass == TaskWorkloadClass.BULK) {
                bulkCallbacksInFlight.incrementAndGet();
            }
        }

        private void onCallbackFinish(String taskId, TaskWorkloadClass workloadClass) {
            if (taskId == null || workloadClass == null) {
                return;
            }
            if (workloadClass == TaskWorkloadClass.BULK) {
                bulkCallbacksInFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
            }
        }

        private void onInteractiveFailure(String taskId, TaskMsg taskMsg, TaskWorkStats statsAfterFailure) {
            if (interactiveTaskId != null
                    && interactiveTaskId.equals(taskId)
                    && "INIT".equals(taskMsg.getStatus().name())) {
                if (statsAfterFailure != null) {
                    interactiveDelayedCountBeforeWakeup.compareAndSet(-1L, statsAfterFailure.delayedCount());
                }
                AtomicInteger dispatchCount = dispatchCountByTaskId.get(taskId);
                interactiveDispatchCountBeforeWakeup.compareAndSet(-1L, dispatchCount != null ? dispatchCount.get() : 0L);
                interactiveFailureCompletedAtNanos.compareAndSet(-1L, System.nanoTime());
                interactiveFailureLatch.countDown();
            }
        }

        private boolean awaitBulkFirstDispatch(long timeout, TimeUnit unit) throws InterruptedException {
            return bulkFirstDispatchLatch.await(timeout, unit);
        }

        private boolean awaitInteractiveFailure(long timeout, TimeUnit unit) throws InterruptedException {
            return interactiveFailureLatch.await(timeout, unit);
        }

        private boolean awaitInteractiveRetryDispatch(long timeout, TimeUnit unit) throws InterruptedException {
            return interactiveRetryDispatchLatch.await(timeout, unit);
        }

        private void onTerminal(Task task) {
            terminalAtNanos.putIfAbsent(task.getTid(), System.nanoTime());
            terminalReasonByTaskId.put(task.getTid(), task.getTerminalReason());
        }

        private SmokeObservation snapshot(SmokeConfig config) {
            return new SmokeObservation(
                    config.workerCount(),
                    config.bulkMessages(),
                    millisBetweenApprovedAndEvent(interactiveTaskId, interactiveFirstDispatchAtNanos.get()),
                    millisBetweenApprovedAndEvent(interactiveTaskId, interactiveRetryDispatchAtNanos.get()),
                    millisBetweenApprovedAndEvent(bulkTaskId, terminalAtNanos.get(bulkTaskId)),
                    millisBetweenApprovedAndEvent(interactiveTaskId, terminalAtNanos.get(interactiveTaskId)),
                    millisBetweenEvent(interactiveFailureCompletedAtNanos.get(), interactiveRetryDispatchAtNanos.get()),
                    interactiveDelayedCountBeforeWakeup.get(),
                    interactiveDispatchCountBeforeWakeup.get(),
                    interactiveRetryBeforeBulkTerminal(),
                    interactiveBulkTaskStillRunningAtRetryDispatch.get() > 0,
                    bulkCallbacksAtInteractiveRetryDispatch.get(),
                    sum(dispatchCyclesByWorkload.get(TaskWorkloadClass.BULK)),
                    sum(dispatchCyclesByWorkload.get(TaskWorkloadClass.INTERACTIVE)),
                    sum(dispatchItemsByWorkload.get(TaskWorkloadClass.BULK)),
                    sum(dispatchItemsByWorkload.get(TaskWorkloadClass.INTERACTIVE)),
                    terminalReasonName(bulkTaskId),
                    terminalReasonName(interactiveTaskId)
            );
        }

        private long millisBetweenApprovedAndEvent(String taskId, long eventAtNanos) {
            if (taskId == null || eventAtNanos < 0L) {
                return -1L;
            }
            Long approvedAt = approvedAtNanos.get(taskId);
            if (approvedAt == null || eventAtNanos < approvedAt) {
                return -1L;
            }
            return TimeUnit.NANOSECONDS.toMillis(eventAtNanos - approvedAt);
        }

        private long millisBetweenApprovedAndEvent(String taskId, Long eventAtNanos) {
            return eventAtNanos == null ? -1L : millisBetweenApprovedAndEvent(taskId, eventAtNanos.longValue());
        }

        private long millisBetweenEvent(long fromNanos, long toNanos) {
            if (fromNanos < 0L || toNanos < 0L || toNanos < fromNanos) {
                return -1L;
            }
            return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
        }

        private boolean interactiveRetryBeforeBulkTerminal() {
            if (bulkTaskId == null) {
                return false;
            }
            Long bulkTerminalAt = terminalAtNanos.get(bulkTaskId);
            long interactiveRetryAt = interactiveRetryDispatchAtNanos.get();
            return bulkTerminalAt != null && interactiveRetryAt >= 0L && interactiveRetryAt < bulkTerminalAt;
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
                               int bulkProcessingDelayMillis,
                               int interactiveFailureProcessingDelayMillis,
                               int interactiveSuccessProcessingDelayMillis,
                               int bulkCallbackThreads,
                               int interactiveCallbackThreads,
                               long interactiveSubmitDelayMillis,
                               long minRetryDispatchDelayMillis,
                               long assignmentRetryDelayMillis,
                               long awaitSeconds) {
        private static SmokeConfig fromSystemProperties() {
            int workerCount = intProperty("mass.retrywakeup.smoke.workers", 5);
            int bulkMessages = intProperty("mass.retrywakeup.smoke.bulkMessages", 320);
            int bulkWorkersTarget = Math.max(workerCount - 1, 1);
            int defaultBulkBatchSize = Math.max((int) Math.ceil((double) bulkMessages / bulkWorkersTarget), 1);
            return new SmokeConfig(
                    workerCount,
                    bulkMessages,
                    intProperty("mass.retrywakeup.smoke.bulkBatchSize", defaultBulkBatchSize),
                    intProperty("mass.retrywakeup.smoke.bulkProcessingDelayMillis", 80),
                    intProperty("mass.retrywakeup.smoke.interactiveFailureProcessingDelayMillis", 5),
                    intProperty("mass.retrywakeup.smoke.interactiveSuccessProcessingDelayMillis", 2),
                    intProperty("mass.retrywakeup.smoke.bulkCallbackThreads", Math.max(workerCount, 8)),
                    intProperty("mass.retrywakeup.smoke.interactiveCallbackThreads", 1),
                    longProperty("mass.retrywakeup.smoke.interactiveSubmitDelayMillis", 20L),
                    longProperty("mass.retrywakeup.smoke.minRetryDispatchDelayMillis", 20L),
                    longProperty("mass.retrywakeup.smoke.assignmentRetryDelayMillis", 25L),
                    longProperty("mass.retrywakeup.smoke.awaitSeconds", 60L)
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("workerCount", workerCount);
            values.put("bulkMessages", bulkMessages);
            values.put("bulkBatchSize", bulkBatchSize);
            values.put("bulkProcessingDelayMillis", bulkProcessingDelayMillis);
            values.put("interactiveFailureProcessingDelayMillis", interactiveFailureProcessingDelayMillis);
            values.put("interactiveSuccessProcessingDelayMillis", interactiveSuccessProcessingDelayMillis);
            values.put("bulkCallbackThreads", bulkCallbackThreads);
            values.put("interactiveCallbackThreads", interactiveCallbackThreads);
            values.put("interactiveSubmitDelayMillis", interactiveSubmitDelayMillis);
            values.put("minRetryDispatchDelayMillis", minRetryDispatchDelayMillis);
            values.put("assignmentRetryDelayMillis", assignmentRetryDelayMillis);
            values.put("awaitSeconds", awaitSeconds);
            return values;
        }
    }

    private record SmokeObservation(int workerCount,
                                    int bulkMessages,
                                    long interactiveFirstDispatchMillis,
                                    long interactiveRetryDispatchMillis,
                                    long bulkTerminalMillis,
                                    long interactiveTerminalMillis,
                                    long interactiveRetryDispatchDelayMillis,
                                    long interactiveDelayedCountBeforeWakeup,
                                    long interactiveDispatchCountBeforeWakeup,
                                    boolean interactiveRetryDispatchedBeforeBulkTerminal,
                                    boolean interactiveRetryDispatchedWhileBulkTaskStillRunning,
                                    long bulkCallbacksInFlightAtInteractiveRetryDispatch,
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
            values.put("interactiveFirstDispatchMillis", interactiveFirstDispatchMillis);
            values.put("interactiveRetryDispatchMillis", interactiveRetryDispatchMillis);
            values.put("bulkTerminalMillis", bulkTerminalMillis);
            values.put("interactiveTerminalMillis", interactiveTerminalMillis);
            values.put("interactiveRetryDispatchDelayMillis", interactiveRetryDispatchDelayMillis);
            values.put("interactiveDelayedCountBeforeWakeup", interactiveDelayedCountBeforeWakeup);
            values.put("interactiveDispatchCountBeforeWakeup", interactiveDispatchCountBeforeWakeup);
            values.put("interactiveRetryDispatchedBeforeBulkTerminal", interactiveRetryDispatchedBeforeBulkTerminal);
            values.put("interactiveRetryDispatchedWhileBulkTaskStillRunning", interactiveRetryDispatchedWhileBulkTaskStillRunning);
            values.put("bulkCallbacksInFlightAtInteractiveRetryDispatch", bulkCallbacksInFlightAtInteractiveRetryDispatch);
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
                    "TaskInteractiveRetryWakeupSmoke workers=%d bulkMessages=%d "
                            + "interactiveRetryDispatchDelay=%dms delayedBeforeWakeup=%d beforeBulkTerminal=%s "
                            + "bulkCallbacksAtRetryDispatch=%d report=%s",
                    config.workerCount(),
                    config.bulkMessages(),
                    observation.interactiveRetryDispatchDelayMillis(),
                    observation.interactiveDelayedCountBeforeWakeup(),
                    observation.interactiveRetryDispatchedBeforeBulkTerminal(),
                    observation.bulkCallbacksInFlightAtInteractiveRetryDispatch(),
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

