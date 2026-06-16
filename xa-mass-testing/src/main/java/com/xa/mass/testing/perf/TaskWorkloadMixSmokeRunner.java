package com.xa.mass.testing.perf;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskDispatchWakeupPort;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.storage.memory.InMemoryWorkerDeclarationStore;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.watchdog.RuntimeReadyDispatchPump;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceDeclarationRuntime;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceQueryRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import com.xa.mass.worker.runtime.admission.WorkerWarmHintRuntime;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.testing.support.TestingPaths;
import com.xa.mass.testing.workerfault.WorkerFaultReportMetadata;
import com.xa.mass.testing.workerfault.WorkerFaultScenarioIndex;

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

    private static final String PROJECT_CODE = "demoApp";
    private static final String WORKER_GROUP_ID = "workload-smoke-workers";

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
            InMemoryTaskShellStore taskStorage = new InMemoryTaskShellStore();
            EngineConfig engineConfig = buildEngineConfig(taskStorage, new InMemoryTaskWorkRuntime());
            TaskCommandService taskCommands = engineConfig.getTaskCommandService();
            TaskQueryService taskQueries = engineConfig.getTaskQueryService();
            TaskEventService taskEvents = engineConfig.getTaskEventService();
            TaskResultIngestFacade taskResultIngestFacade = engineConfig.getTaskResultIngestFacade();
            TaskAssignmentRuntimePort assignmentRuntimePort = engineConfig.getTaskAssignmentRuntimePort();
            TaskLeaseMaintenancePort leaseMaintenancePort = engineConfig.getTaskLeaseMaintenancePort();
            TaskDispatchWakeupPort dispatchWakeupPort = engineConfig.getTaskDispatchWakeupPort();
            TaskRuntimeRecoveryPort recoveryPort = engineConfig.getTaskRuntimeRecoveryPort();
            WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());
            WorkerResourceDeclarationRuntime workerDeclarationRuntime = workerManager;
            WorkerResourceQueryRuntime workerResourceQueryRuntime = workerManager;
            WorkerAdmissionRuntime workerAdmissionRuntime = workerManager;
            WorkerSchedulingViewRuntime workerSchedulingViewRuntime = workerManager;
            WorkerWarmHintRuntime workerWarmHintRuntime = workerManager;
            AssignmentRecordService recordService = new AssignmentRecordService();
            WorkloadTiming timing = new WorkloadTiming();
            ExecutorService callbackExecutor = Executors.newFixedThreadPool(config.callbackThreads(), r -> {
                Thread thread = new Thread(r, "TaskWorkloadMix-callback");
                thread.setDaemon(true);
                return thread;
            });
            CountDownLatch bulkTerminalLatch = new CountDownLatch(1);
            CountDownLatch interactiveTerminalLatch = new CountDownLatch(1);
            Map<String, TaskWorkloadClass> workloadByTaskId = new ConcurrentHashMap<>();

            TaskDispatchBatchListener dispatchListener = (task, dispatchBindings) -> {
                TaskWorkloadClass workloadClass = workloadByTaskId.get(task.taskId());
                timing.onDispatch(task.taskId(), workloadClass, dispatchBindings.size());
                for (TaskDispatchBinding binding : dispatchBindings) {
                    callbackExecutor.submit(() -> handleBinding(
                            taskResultIngestFacade,
                            timing,
                            workloadByTaskId,
                            task,
                            binding
                    ));
                }
            };

            TaskWorkerMatchingStrategy matchingStrategy =
                    new LaneAwareMatchingStrategy(
                            workerResourceQueryRuntime,
                            workerAdmissionRuntime,
                            workerSchedulingViewRuntime,
                            config.reservedInteractiveWorkers());
            LaneAwareMatchingStrategy laneAwareMatchingStrategy = (LaneAwareMatchingStrategy) matchingStrategy;
            SimpleTaskDispatchBinder dispatchBinder =
                    new SimpleTaskDispatchBinder(
                            assignmentRuntimePort,
                            workerAdmissionRuntime,
                            recordService,
                            dispatchListener
                    );
            TaskWorkerAssignListener workerAssignListener =
                    new TaskWorkerAssignListener(
                            matchingStrategy,
                            workerAdmissionRuntime,
                            workerWarmHintRuntime,
                            dispatchBinder,
                            assignmentRuntimePort,
                            taskEvents
                    );
            RuntimeReadyDispatchPump runtimeReadyDispatchPump =
                    new RuntimeReadyDispatchPump(recoveryPort, workerAssignListener::onTaskAssign, 50L, 64);
            TaskResourceReleaseListener releaseListener =
                    new TaskResourceReleaseListener(leaseMaintenancePort, dispatchWakeupPort, workerAdmissionRuntime);

            try {
                registerWorkers(workerDeclarationRuntime, config.workerCount());
                taskEvents.addTaskWorkAttemptClosedListener(releaseListener::onTaskWorkAttemptClosed);
                taskEvents.addTaskTerminalListener(releaseListener::onTaskTerminal);
                taskEvents.addTaskTerminalListener(task -> {
                    if (TaskWorkloadClass.BULK == task.getExecutionSpec().getWorkloadClass()) {
                        timing.onTerminal(task);
                        bulkTerminalLatch.countDown();
                    } else if (TaskWorkloadClass.INTERACTIVE == task.getExecutionSpec().getWorkloadClass()) {
                        timing.onTerminal(task);
                        interactiveTerminalLatch.countDown();
                    }
                });
                runtimeReadyDispatchPump.start();

                Task bulkTask = materializeTask(taskCommands, buildBulkRequest(config));
                workloadByTaskId.put(bulkTask.getTid(), bulkTask.getExecutionSpec().getWorkloadClass());
                timing.onCreated(bulkTask);
                require(taskCommands.approveTask(bulkTask.getTid()), "bulk task should approve");
                timing.onApproved(bulkTask);
                require(assignmentRuntimePort.countDispatchReadyWork(bulkTask.getTid()) > 0,
                        "bulk task should have runtime-ready work after approval");
                require(workerAssignListener.onTaskAssign(taskQueries.getTask(bulkTask.getTid())),
                        "bulk task should dispatch from explicit assignment wake: "
                                + laneAwareMatchingStrategy.diagnosticSnapshot(bulkTask));
                require(timing.awaitBulkFirstDispatch(config.awaitSeconds(), TimeUnit.SECONDS),
                        "bulk task should start dispatching before interactive submission");

                Thread.sleep(config.interactiveSubmitDelayMillis());

                Task interactiveTask = materializeTask(taskCommands, buildInteractiveRequest(config));
                workloadByTaskId.put(interactiveTask.getTid(), interactiveTask.getExecutionSpec().getWorkloadClass());
                timing.onCreated(interactiveTask);
                require(taskCommands.approveTask(interactiveTask.getTid()), "interactive task should approve");
                timing.onApproved(interactiveTask);
                require(assignmentRuntimePort.countDispatchReadyWork(interactiveTask.getTid()) > 0,
                        "interactive task should have runtime-ready work after approval");
                require(workerAssignListener.onTaskAssign(taskQueries.getTask(interactiveTask.getTid())),
                        "interactive task should dispatch from explicit assignment wake: "
                                + laneAwareMatchingStrategy.diagnosticSnapshot(interactiveTask));

                require(interactiveTerminalLatch.await(config.awaitSeconds(), TimeUnit.SECONDS),
                        "interactive task should converge");
                require(bulkTerminalLatch.await(config.awaitSeconds(), TimeUnit.SECONDS),
                        "bulk task should converge");

                callbackExecutor.shutdown();
                require(callbackExecutor.awaitTermination(15, TimeUnit.SECONDS),
                        "callback executor did not terminate");

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
                runtimeReadyDispatchPump.stop();
                callbackExecutor.shutdownNow();
            }
        }

        private void handleBinding(TaskResultIngestFacade taskResultIngestFacade,
                                   WorkloadTiming timing,
                                   Map<String, TaskWorkloadClass> workloadByTaskId,
                                   TaskDispatchContext task,
                                   TaskDispatchBinding binding) {
            TaskWorkloadClass workloadClass = workloadByTaskId.get(task.taskId());
            int delayMillis = workloadClass == TaskWorkloadClass.INTERACTIVE
                    ? config.interactiveProcessingDelayMillis()
                    : config.bulkProcessingDelayMillis();
            timing.onCallbackStart(task.taskId(), workloadClass);
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                boolean accepted = taskResultIngestFacade.ingestTaskResult(
                        task.taskId(),
                        binding.messageId(),
                        true,
                        "ok",
                        null,
                        Map.of("runner", "TaskWorkloadMixSmokeRunner")
                );
                require(accepted, "result callback should be accepted for " + binding.messageId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("callback interrupted", e);
            } finally {
                timing.onCallbackFinish(task.taskId(), workloadClass);
            }
        }

        private static TaskCreatePlan buildBulkRequest(SmokeConfig config) {
            TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
            shell.setSourceRef("bulk-workload-smoke");
            shell.setProject(PROJECT_CODE);
            shell.setUserId("workload-smoke");
            shell.setContract(TaskContract.BATCH);
            shell.setExecutionSpec(taskExecutionSpec(TaskWorkloadClass.BULK, config.bulkBatchSize(), 3));
            shell.setSharedConfig(Map.of(
                    "source", "TaskWorkloadMixSmokeRunner",
                    "workload", "bulk",
                    TaskSharedConfig.WORKER_GROUP_ID, WORKER_GROUP_ID
            ));
            return new TaskCreatePlan(shell, buildInputs("bulk", config.bulkMessages()), false);
        }

        private static EngineConfig buildEngineConfig(InMemoryTaskShellStore taskStorage,
                                                      InMemoryTaskWorkRuntime taskWorkRuntime) {
            EngineConfig engineConfig = new EngineConfig();
            engineConfig.setTaskShellStore(taskStorage);
            engineConfig.setTaskWorkRuntime(taskWorkRuntime);
            return engineConfig;
        }

        private static TaskCreatePlan buildInteractiveRequest(SmokeConfig config) {
            TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
            shell.setSourceRef("interactive-workload-smoke");
            shell.setProject(PROJECT_CODE);
            shell.setUserId("workload-smoke");
            shell.setContract(TaskContract.BATCH);
            shell.setExecutionSpec(taskExecutionSpec(TaskWorkloadClass.INTERACTIVE, config.interactiveBatchSize(), 3));
            shell.setSharedConfig(Map.of(
                    "source", "TaskWorkloadMixSmokeRunner",
                    "workload", "interactive",
                    TaskSharedConfig.WORKER_GROUP_ID, WORKER_GROUP_ID
            ));
            return new TaskCreatePlan(shell, buildInputs("interactive", config.interactiveMessages()), false);
        }

        private static Task materializeTask(TaskCommandService taskCommands, TaskCreatePlan request) {
            Task task = taskCommands.createTaskShell(request.shell());
            if (!request.inputs().isEmpty()) {
                taskCommands.appendTaskItems(task.getTid(), request.inputs());
            }
            if (!request.keepIntakeOpen()) {
                require(taskCommands.sealTask(task.getTid()), "task should seal after ingest");
            }
            return task;
        }

        private record TaskCreatePlan(TaskShellCreateRequestDto shell,
                                      List<Map<String, Object>> inputs,
                                      boolean keepIntakeOpen) {
        }

        private static TaskExecutionSpec taskExecutionSpec(TaskWorkloadClass workloadClass,
                                                           int batchSize,
                                                           int defaultMaxRetryCount) {
            TaskExecutionSpec spec = new TaskExecutionSpec();
            spec.setWorkloadClass(workloadClass);
            spec.setBatchSize(batchSize);
            spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
            return spec;
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

        private static void registerWorkers(WorkerResourceDeclarationRuntime workerDeclarationRuntime, int workerCount) {
            workerDeclarationRuntime.upsertWorkerGroup(WorkerGroupRecord.builder(WORKER_GROUP_ID)
                    .projectCodes(List.of(PROJECT_CODE))
                    .build());
            for (int i = 0; i < workerCount; i++) {
                workerDeclarationRuntime.addWorker(new WorkerDeclarationRecord(
                        "workload-smoke-worker-" + i,
                        WORKER_GROUP_ID,
                        null,
                        "workload-smoke",
                        1,
                        Map.of()
                ));
            }
        }

        private static Path writeReport(SmokeConfig config, SmokeObservation observation) throws Exception {
            Map<String, Object> report = new LinkedHashMap<>(WorkerFaultReportMetadata.topLevel(config.scenario()));
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

    private static final class LaneAwareMatchingStrategy implements TaskWorkerMatchingStrategy {
        private final WorkerResourceQueryRuntime workerResourceQueryRuntime;
        private final WorkerAdmissionRuntime workerAdmissionRuntime;
        private final WorkerSchedulingViewRuntime workerSchedulingViewRuntime;
        private final int reservedInteractiveWorkers;

        private LaneAwareMatchingStrategy(WorkerResourceQueryRuntime workerResourceQueryRuntime,
                                          WorkerAdmissionRuntime workerAdmissionRuntime,
                                          WorkerSchedulingViewRuntime workerSchedulingViewRuntime,
                                          int reservedInteractiveWorkers) {
            this.workerResourceQueryRuntime = Objects.requireNonNull(workerResourceQueryRuntime, "workerResourceQueryRuntime");
            this.workerAdmissionRuntime = Objects.requireNonNull(workerAdmissionRuntime, "workerAdmissionRuntime");
            this.workerSchedulingViewRuntime = Objects.requireNonNull(workerSchedulingViewRuntime,
                    "workerSchedulingViewRuntime");
            this.reservedInteractiveWorkers = Math.max(reservedInteractiveWorkers, 0);
        }

        @Override
        public List<WorkerSchedulingCandidate> matchWorkers(Task task, int maxWorkerCount) {
            List<String> workerGroupSelector = TaskSharedConfig.workerGroupSelector(task);
            if (workerGroupSelector.isEmpty()) {
                return List.of();
            }
            List<WorkerSchedulingCandidate> matched = new ArrayList<>();
            for (WorkerResourceRecord worker : workerResourceQueryRuntime.workers()) {
                if (matched.size() >= maxWorkerCount) {
                    break;
                }
                if (task.getExecutionSpec().getWorkloadClass() == TaskWorkloadClass.BULK
                        && isReservedInteractiveWorker(worker)) {
                    continue;
                }
                if (!PerfWorkerMatchingSupport.workerAvailable(workerSchedulingViewRuntime, worker)
                        || !workerGroupSelector.contains(worker.workerGroupId())
                        || !PerfWorkerMatchingSupport.supportsProject(
                                workerSchedulingViewRuntime,
                                worker,
                                task.getProject())) {
                    continue;
                }
                WorkerSchedulingCandidate candidate =
                        PerfWorkerMatchingSupport.tryReserveCandidate(
                                workerAdmissionRuntime,
                                workerSchedulingViewRuntime,
                                task,
                                worker);
                if (candidate != null) {
                    matched.add(candidate);
                }
            }
            return matched;
        }

        private boolean isReservedInteractiveWorker(WorkerResourceRecord worker) {
            if (reservedInteractiveWorkers <= 0 || worker == null || worker.workerId() == null) {
                return false;
            }
            String workerId = worker.workerId();
            int dash = workerId.lastIndexOf('-');
            if (dash < 0 || dash == workerId.length() - 1) {
                return false;
            }
            try {
                int workerIndex = Integer.parseInt(workerId.substring(dash + 1));
                int totalWorkers = workerResourceQueryRuntime.workers().size();
                return workerIndex >= Math.max(totalWorkers - reservedInteractiveWorkers, 0);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private String diagnosticSnapshot(Task task) {
            List<String> workerGroupSelector = TaskSharedConfig.workerGroupSelector(task);
            long available = 0L;
            long groupMatched = 0L;
            long projectMatched = 0L;
            List<String> loads = new ArrayList<>();
            for (WorkerResourceRecord worker : workerResourceQueryRuntime.workers()) {
                boolean workerAvailable = PerfWorkerMatchingSupport.workerAvailable(workerSchedulingViewRuntime, worker);
                boolean groupMatch = workerGroupSelector.contains(worker.workerGroupId());
                boolean projectMatch = PerfWorkerMatchingSupport.supportsProject(
                        workerSchedulingViewRuntime,
                        worker,
                        task.getProject());
                boolean reserved = task.getExecutionSpec().getWorkloadClass() == TaskWorkloadClass.BULK
                        && isReservedInteractiveWorker(worker);
                if (workerAvailable) {
                    available++;
                }
                if (workerAvailable && groupMatch) {
                    groupMatched++;
                }
                if (workerAvailable && groupMatch && projectMatch && !reserved) {
                    projectMatched++;
                }
                var load = workerSchedulingViewRuntime.getWorkerLoad(worker.workerId());
                loads.add(worker.workerId() + "{available=" + workerAvailable
                        + ",group=" + groupMatch
                        + ",project=" + projectMatch
                        + ",reservedInteractive=" + reserved
                        + ",active=" + load.activeLeaseCount()
                        + ",reservedCount=" + load.reservedCount()
                        + ",capacity=" + load.declaredCapacity()
                        + "}");
            }
            return "selector=" + workerGroupSelector
                    + ", available=" + available
                    + ", groupMatched=" + groupMatched
                    + ", eligible=" + projectMatched
                    + ", workers=" + loads;
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
            workloadByTaskId.put(task.getTid(), task.getExecutionSpec().getWorkloadClass());
            if (task.getExecutionSpec().getWorkloadClass() == TaskWorkloadClass.BULK) {
                bulkTaskId = task.getTid();
            } else if (task.getExecutionSpec().getWorkloadClass() == TaskWorkloadClass.INTERACTIVE) {
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
            firstDispatchAtNanos.putIfAbsent(taskId, now);
            if (workloadClass == TaskWorkloadClass.BULK) {
                bulkFirstDispatchLatch.countDown();
            } else if (workloadClass == TaskWorkloadClass.INTERACTIVE) {
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

        private void onTerminal(Task task) {
            terminalAtNanos.putIfAbsent(task.getTid(), System.nanoTime());
            terminalReasonByTaskId.put(task.getTid(), task.getTerminalReason());
        }

        private SmokeObservation snapshot(SmokeConfig config) {
            return new SmokeObservation(
                    config.workerCount(),
                    config.reservedInteractiveWorkers(),
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

    private record SmokeConfig(WorkerFaultScenarioIndex.Scenario scenario,
                               int workerCount,
                               int reservedInteractiveWorkers,
                               int bulkMessages,
                               int bulkBatchSize,
                               int interactiveMessages,
                               int interactiveBatchSize,
                               int bulkProcessingDelayMillis,
                               int interactiveProcessingDelayMillis,
                               int callbackThreads,
                               long interactiveSubmitDelayMillis,
                               long interactiveFirstDispatchWarnMillis,
                               long awaitSeconds) {
        private static SmokeConfig fromSystemProperties() {
            WorkerFaultScenarioIndex.Scenario scenario = scenarioFromSystemProperties();
            ScenarioDefaults defaults = ScenarioDefaults.forScenario(scenario);
            int workerCount = intProperty("mass.workload.smoke.workers", defaults.workerCount());
            int bulkMessages = intProperty("mass.workload.smoke.bulkMessages", defaults.bulkMessages());
            int reservedInteractiveWorkers = intProperty(
                    "mass.workload.smoke.reservedInteractiveWorkers",
                    defaults.reservedInteractiveWorkers());
            int bulkWorkersTarget = Math.max(workerCount - reservedInteractiveWorkers, 1);
            int defaultBulkBatchSize = Math.max((int) Math.ceil((double) bulkMessages / bulkWorkersTarget), 1);
            return new SmokeConfig(
                    scenario,
                    workerCount,
                    reservedInteractiveWorkers,
                    bulkMessages,
                    intProperty("mass.workload.smoke.bulkBatchSize", defaultBulkBatchSize),
                    intProperty("mass.workload.smoke.interactiveMessages", defaults.interactiveMessages()),
                    intProperty("mass.workload.smoke.interactiveBatchSize", defaults.interactiveBatchSize()),
                    intProperty("mass.workload.smoke.bulkProcessingDelayMillis", defaults.bulkProcessingDelayMillis()),
                    intProperty(
                            "mass.workload.smoke.interactiveProcessingDelayMillis",
                            defaults.interactiveProcessingDelayMillis()),
                    intProperty("mass.workload.smoke.callbackThreads", Math.max(workerCount, 8)),
                    longProperty(
                            "mass.workload.smoke.interactiveSubmitDelayMillis",
                            defaults.interactiveSubmitDelayMillis()),
                    longProperty(
                            "mass.workload.smoke.interactiveFirstDispatchWarnMillis",
                            defaults.interactiveFirstDispatchWarnMillis()),
                    longProperty("mass.workload.smoke.awaitSeconds", defaults.awaitSeconds())
            );
        }

        private Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("scenarioId", scenario.scenarioId());
            values.put("workerCount", workerCount);
            values.put("reservedInteractiveWorkers", reservedInteractiveWorkers);
            values.put("bulkMessages", bulkMessages);
            values.put("bulkBatchSize", bulkBatchSize);
            values.put("interactiveMessages", interactiveMessages);
            values.put("interactiveBatchSize", interactiveBatchSize);
            values.put("bulkProcessingDelayMillis", bulkProcessingDelayMillis);
            values.put("interactiveProcessingDelayMillis", interactiveProcessingDelayMillis);
            values.put("callbackThreads", callbackThreads);
            values.put("interactiveSubmitDelayMillis", interactiveSubmitDelayMillis);
            values.put("interactiveFirstDispatchWarnMillis", interactiveFirstDispatchWarnMillis);
            values.put("awaitSeconds", awaitSeconds);
            return values;
        }

        private static WorkerFaultScenarioIndex.Scenario scenarioFromSystemProperties() {
            String raw = System.getProperty("mass.workload.smoke.scenarioId");
            if (raw == null || raw.isBlank()) {
                return WorkerFaultScenarioIndex.Scenario.WORKLOAD_MIX_INTERACTIVE_UNDER_BULK;
            }
            WorkerFaultScenarioIndex.Scenario scenario = WorkerFaultScenarioIndex.scenarioForId(raw.trim())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown mass.workload.smoke.scenarioId: " + raw.trim()));
            if (scenario.runnerFamily() != WorkerFaultScenarioIndex.RunnerFamily.TASK_WORKLOAD_MIX_SMOKE) {
                throw new IllegalArgumentException("mass.workload.smoke.scenarioId must reference a workload mix "
                        + "scenario: " + scenario.scenarioId());
            }
            return scenario;
        }

    }

    private record ScenarioDefaults(int workerCount,
                                    int reservedInteractiveWorkers,
                                    int bulkMessages,
                                    int interactiveMessages,
                                    int interactiveBatchSize,
                                    int bulkProcessingDelayMillis,
                                    int interactiveProcessingDelayMillis,
                                    long interactiveSubmitDelayMillis,
                                    long interactiveFirstDispatchWarnMillis,
                                    long awaitSeconds) {
        private static ScenarioDefaults forScenario(WorkerFaultScenarioIndex.Scenario scenario) {
            if (scenario == WorkerFaultScenarioIndex.Scenario.WORKLOAD_MIX_SLOW_BULK_INTERACTIVE_ISOLATION) {
                return new ScenarioDefaults(
                        5,
                        1,
                        160,
                        1,
                        1,
                        120,
                        2,
                        75L,
                        2_500L,
                        60L
                );
            }
            return new ScenarioDefaults(
                    5,
                    1,
                    160,
                    1,
                    1,
                    40,
                    2,
                    150L,
                    2_000L,
                    60L
            );
        }
    }

    private record SmokeObservation(int workerCount,
                                    int reservedInteractiveWorkers,
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
            values.put("reservedInteractiveWorkers", reservedInteractiveWorkers);
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
                    "TaskWorkloadMixSmoke scenario=%s workers=%d bulkMessages=%d interactiveMessages=%d "
                            + "interactiveFirstDispatch=%dms bulkTerminal=%dms beforeBulkTerminal=%s "
                            + "bulkCallbacksAtInteractiveDispatch=%d report=%s",
                    config.scenario().scenarioId(),
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
