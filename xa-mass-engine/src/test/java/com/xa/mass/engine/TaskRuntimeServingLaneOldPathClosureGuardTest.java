package com.xa.mass.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TaskRuntimeServingLaneOldPathClosureGuardTest {

    @Test
    void migratedTaskManagerRuntimeEntrypointsRequireServingLaneAndHaveNoLegacyFallback() throws IOException {
        String source = Files.readString(taskManagerSource(), StandardCharsets.UTF_8);
        String declaration = source.lines()
                .filter(line -> line.contains("public class TaskManager implements"))
                .findFirst()
                .orElse("");
        List<String> forbiddenRuntimePorts = List.of(
                "TaskAssignmentRuntimePort",
                "TaskLeaseMaintenancePort",
                "TaskDispatchWakeupPort",
                "TaskRuntimeRecoveryPort",
                "TaskResultIngestPort");
        List<String> runtimePortLeaks = forbiddenRuntimePorts.stream()
                .filter(declaration::contains)
                .toList();
        assertTrue(runtimePortLeaks.isEmpty(),
                "TaskManager must not re-implement migrated task-runtime hot-path ports: " + runtimePortLeaks);
        List<String> forbiddenTaskManagerRuntimeMethods = List.of(
                "\n    List<Task> getRuntimeDispatchableTasks",
                "\n    boolean expireLeasedWork",
                "\n    int countDispatchReadyWork",
                "\n    int countActiveDispatchWorkers",
                "\n    boolean hasDispatchReadyWork",
                "\n    boolean hasActiveWorkForWorker",
                "\n    boolean ingestTaskResult",
                "\n    TaskResultCorrelation getResultCorrelation",
                "\n    ClaimReadyOutcome claimReady",
                "\n    FinalResultWindow readFinalResults",
                "\n    long countTaskResults",
                "\n    List<ActiveLeaseRepairCandidate> getActiveLeases",
                "\n    List<ActiveLeaseRepairCandidate> pollExpiredLeases",
                "\n    List<ActiveLeaseRepairCandidate> getActiveLeaseCandidates",
                "\n    List<ActiveLeaseRepairCandidate> pollExpiredLeaseCandidates",
                "\n    boolean compensateDispatchSubmitFailure",
                "\n    boolean compensateDispatchDeliveryFailure");
        List<String> publicRuntimeLeaks = forbiddenTaskManagerRuntimeMethods.stream()
                .filter(source::contains)
                .toList();
        assertTrue(publicRuntimeLeaks.isEmpty(),
                "Migrated TaskManager runtime delegate methods must not remain on TaskManager: "
                        + publicRuntimeLeaks);
        List<String> requiredOperations = List.of(
                "requireTaskRuntimeServingLane(\"addRuntimeIngressItems\")",
                "requireTaskRuntimeServingLane(\"validateRuntimeAppendAdmission\")",
                "requireTaskRuntimeServingLane(\"hasDispatchReadyWork\")",
                "requireTaskRuntimeServingLane(\"requestTaskDispatch\")",
                "requireTaskRuntimeServingLane(\"syncRuntimeSchedulerEligibility\")",
                "requireTaskRuntimeServingLane(\"getTaskRuntimeProgressSnapshot\")",
                "requireTaskRuntimeServingLane(\"discardTaskRuntime\")",
                "requireTaskRuntimeServingLane(\"discardTaskWork\")");
        List<String> missingOperations = requiredOperations.stream()
                .filter(operation -> !source.contains(operation))
                .toList();

        assertTrue(missingOperations.isEmpty(),
                "Migrated TaskManager runtime entrypoints must require TaskRuntimeServingLane: "
                        + missingOperations);
        List<String> forbiddenFallbacks = List.of(
                "addRuntimeIngressItemsToOldRuntime",
                "taskWorkRuntime.",
                "taskResultRuntime.",
                "resultService.",
                "getTaskWorkRuntime()",
                "getTaskResultRuntime()",
                "ensureLegacyTaskRuntimePath",
                "ensureLegacyResultRuntimePath",
                "TaskResultService.ResultMutationOutcome",
                "WorkEnqueueOutcome",
                "RuntimeResultApplyContext",
                "ResultApplyOutcome",
                "TaskWorkResult",
                "TaskWorkEnvelope");
        List<String> fallbackViolations = forbiddenFallbacks.stream()
                .filter(source::contains)
                .toList();

        assertTrue(fallbackViolations.isEmpty(),
                "TaskManager must not retain old TaskWorkRuntime/TaskResultRuntime fallback code: "
                        + fallbackViolations);
        assertTrue(!source.contains("RuntimeIngressWriter"),
                "append selected-path routing must stay explicit instead of hiding old/new runtime ownership behind a function pointer");
        assertTrue(!source.contains("void ingestRuntimeInput(")
                        && !source.contains("void ingestRuntimePayloadRef(")
                        && !source.contains("addRuntimeIngressItem("),
                "TaskManager must not expose single-item runtime ingress backdoors outside the append batch boundary");
        assertTrue(!source.contains("resultRepairPumpEnabled"),
                "TaskManager must not expose an old result repair pump lifecycle switch");
        assertTrue(!source.contains("disabledLegacyRuntime")
                        && !source.contains("Proxy.newProxyInstance")
                        && !source.contains("InvocationHandler")
                        && !source.contains("java.lang.reflect"),
                "TaskManager no-old-runtime constructors must not fabricate disabled legacy runtime sentinels");
        assertTrue(!source.contains("TaskWorkRuntime taskWorkRuntime,")
                        && !source.contains("TaskResultRuntime taskResultRuntime,")
                        && !source.contains("requireLegacyRuntime")
                        && !source.contains("new TaskRuntimeRetryPolicyResolver"),
                "TaskManager must not expose or internally route through legacy work/result runtime constructors");
    }

    @Test
    void dispatchWakeupEntrypointUsesServingLaneDelegatingPort() throws IOException {
        String source = Files.readString(taskManagerSource(), StandardCharsets.UTF_8);
        String installServingLane = MethodGuard.methodBody(source, "public void installTaskRuntimeServingLane");
        Pattern dispatchEntrypoint = Pattern.compile(
                "void\\s+requestTaskDispatch\\s*\\(\\s*Task\\s+task\\s*\\)\\s*\\{\\s*"
                        + "dispatchWakeupPort\\.requestTaskDispatch\\(task\\);\\s*\\}");

        assertTrue(installServingLane.contains("this.dispatchWakeupPort = servingLane;"),
                "installTaskRuntimeServingLane must route dispatch wakeup through the serving lane");
        assertTrue(dispatchEntrypoint.matcher(source).find(),
                "requestTaskDispatch must use the dispatch wakeup port selected by the active runtime owner");
    }

    @Test
    void lifecycleStatusTransitionsSynchronizeTaskRuntimeSchedulerGate() throws IOException {
        String lifecycleService = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLifecycleService.java"), StandardCharsets.UTF_8);
        String servingLane = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java"), StandardCharsets.UTF_8);
        String runtimeGate = MethodGuard.methodBody(servingLane, "private RuntimeGate runtimeGate");
        List<String> lifecycleBodies = List.of(
                MethodGuard.methodBody(lifecycleService, "boolean approveTask"),
                MethodGuard.methodBody(lifecycleService, "boolean rejectTask"),
                MethodGuard.methodBody(lifecycleService, "boolean blockTask"),
                MethodGuard.methodBody(lifecycleService, "boolean pauseTask"),
                MethodGuard.methodBody(lifecycleService, "TaskResumeResult resumeTaskDetailed"),
                MethodGuard.methodBody(lifecycleService, "private boolean doTerminateTask"));

        List<String> missingSync = lifecycleBodies.stream()
                .filter(body -> !body.contains("taskManager.syncRuntimeSchedulerEligibility(task)"))
                .toList();
        assertTrue(missingSync.isEmpty(),
                "task shell lifecycle transitions that affect dispatch must synchronize the task-runtime scheduler gate");
        assertTrue(runtimeGate.contains("case READY, RUNNING -> RuntimeGate.OPEN")
                        && runtimeGate.contains("case PAUSED -> RuntimeGate.PAUSED")
                        && runtimeGate.contains("case BLOCKED, NEW -> RuntimeGate.BLOCKED")
                        && runtimeGate.contains("RuntimeGate.TERMINAL"),
                "TaskRuntimeServingLane must map task shell lifecycle state into task-runtime scheduler gate");
    }

    @Test
    void assignmentRuntimePortDoesNotExposeClaimLeaseConfigurationGetter() throws IOException {
        String assignmentPort = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskAssignmentRuntimePort.java"), StandardCharsets.UTF_8);
        String dispatchBinder = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/listener/SimpleTaskDispatchBinder.java"),
                StandardCharsets.UTF_8);
        String runtimeKernel = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernel.java"), StandardCharsets.UTF_8);
        String runtimeKernelConfig = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernelConfig.java"), StandardCharsets.UTF_8);

        assertTrue(!assignmentPort.contains("getWorkLeaseSeconds"),
                "TaskAssignmentRuntimePort must stay a runtime hot-path port, not a claim-lease config getter surface");
        assertTrue(dispatchBinder.contains("LongSupplier workLeaseSecondsSupplier")
                        && dispatchBinder.contains("workLeaseSecondsSupplier.getAsLong()")
                        && !dispatchBinder.contains("assignmentRuntime.getWorkLeaseSeconds()"),
                "SimpleTaskDispatchBinder must receive claim lease config as explicit assembly input");
        assertTrue(runtimeKernel.contains("config::getTaskMessageLeaseSeconds")
                        && runtimeKernelConfig.contains("long getTaskMessageLeaseSeconds()"),
                "EngineRuntimeKernel must route claim lease config from kernel config, not through assignment runtime");
    }

    @Test
    void runtimeReadinessAndActiveLeaseHintsUseServingLaneOwnerNotOldRuntimeStats() throws IOException {
        String taskManager = Files.readString(taskManagerSource(), StandardCharsets.UTF_8);
        String servingLane = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java"), StandardCharsets.UTF_8);
        String laneReadyCount = MethodGuard.methodBody(servingLane, "public int countDispatchReadyWork");
        String laneActiveWorkerCount = MethodGuard.methodBody(servingLane, "public int countActiveDispatchWorkers");
        String laneReadyHint = MethodGuard.methodBody(servingLane, "public boolean hasDispatchReadyWork");
        String laneWorkerActiveHint = MethodGuard.methodBody(servingLane, "public boolean hasActiveWorkForWorker");
        List<String> deletedTaskManagerHints = List.of(
                "\n    int countDispatchReadyWork",
                "\n    int countActiveDispatchWorkers",
                "\n    boolean hasDispatchReadyWork",
                "\n    boolean hasActiveWorkForWorker");
        List<String> leakedTaskManagerHints = deletedTaskManagerHints.stream()
                .filter(taskManager::contains)
                .toList();
        assertTrue(leakedTaskManagerHints.isEmpty(),
                "TaskManager must not keep runtime readiness or active-lease hint delegate methods: "
                        + leakedTaskManagerHints);
        assertTrue(taskManager.contains("requireTaskRuntimeServingLane(\"hasDispatchReadyWork\").hasDispatchReadyWork(taskId)"),
                "TaskManager default dispatch wakeup hook must require the serving lane instead of owning ready-count truth");

        assertTrue(laneReadyCount.contains("readPort.progressSnapshot(taskId).readyCount()"),
                "serving-lane ready count must read task-runtime read/progress truth");
        assertTrue(laneActiveWorkerCount.contains("readPort.activeWorkForTask")
                        && laneActiveWorkerCount.contains("ActiveLeaseRepairCandidate::workerId"),
                "serving-lane active-worker count must read task-scoped active lease truth through the read surface");
        assertTrue(laneReadyHint.contains("countDispatchReadyWork(taskId) > 0"),
                "serving-lane ready boolean hint must derive from task-runtime ready count");
        assertTrue(laneWorkerActiveHint.contains("readPort.activeWorkForTask")
                        && laneWorkerActiveHint.contains("candidate -> workerId.equals(candidate.workerId())"),
                "serving-lane worker-active hint must read task-scoped active lease truth and filter by worker id");

        List<String> combinedBodies = List.of(
                laneReadyCount,
                laneActiveWorkerCount,
                laneReadyHint,
                laneWorkerActiveHint);
        List<String> forbiddenOldRuntimeTerms = List.of(
                "TaskWorkRuntime",
                "TaskResultRuntime",
                "TaskWorkRuntimeStats",
                "TaskWorkStats",
                "TaskWorkEnvelope",
                "taskWorkRuntime",
                "taskResultRuntime");
        List<String> violations = forbiddenOldRuntimeTerms.stream()
                .filter(term -> combinedBodies.stream().anyMatch(body -> body.contains(term)))
                .toList();

        assertTrue(violations.isEmpty(),
                "runtime readiness/active-lease hints must not read old runtime stats or DTOs: "
                        + violations);
    }

    @Test
    void resultCorrelationReadsUseTaskRuntimeReadSurface() throws IOException {
        String servingLane = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java"), StandardCharsets.UTF_8);
        List<String> correlationBodies = List.of(
                MethodGuard.methodBody(servingLane, "public boolean compensateDispatchDeliveryFailure"),
                MethodGuard.methodBody(servingLane, "public boolean ingestTaskResult"),
                MethodGuard.methodBody(servingLane, "public TaskResultCorrelation getResultCorrelation"));

        assertTrue(correlationBodies.stream().allMatch(body -> body.contains("readPort.resultCorrelation")),
                "serving-lane result correlation point reads must use TaskRuntimeReadPort");
        assertTrue(correlationBodies.stream().noneMatch(body -> body.contains("resultPort.getResultCorrelation")),
                "result correlation point reads must not keep the old result apply port as a read owner");
    }

    @Test
    void servingLaneConstructorOnlyAcceptsGroupedTaskRuntimePorts() throws IOException {
        String servingLane = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java"), StandardCharsets.UTF_8);
        List<String> forbiddenOldPortTokens = List.of(
                "TaskRuntimeAppendPort",
                "TaskRuntimeSchedulerPort",
                "TaskRuntimeClaimPort",
                "TaskRuntimeResultPort",
                "TaskRuntimeRepairPort",
                "TaskRuntimeProgressPort",
                "TaskRuntimeDiscardPort",
                "requireWorkPort",
                "requireScorePort",
                "requireConvergencePort",
                "requireReadPort",
                "requireDiscardPort");
        List<String> violations = forbiddenOldPortTokens.stream()
                .filter(servingLane::contains)
                .toList();

        assertTrue(violations.isEmpty(),
                "TaskRuntimeServingLane must not expose old task-runtime port constructor fallback: "
                        + violations);
        assertTrue(servingLane.contains("TaskRuntimeServingLane(TaskRuntimeWorkPort workPort")
                        && servingLane.contains("TaskRuntimeScorePort scorePort")
                        && servingLane.contains("TaskRuntimeConvergencePort convergencePort")
                        && servingLane.contains("TaskRuntimeReadPort readPort"),
                "TaskRuntimeServingLane constructor must take the grouped task-runtime ports directly");
    }

    @Test
    void orderedResultWindowIsSeparatedFromCoreRuntimeReadPort() throws IOException {
        String readPort = Files.readString(repositoryRoot().resolve(
                "xa-mass-task-runtime/src/main/java/com/xa/mass/task/runtime/TaskRuntimeReadPort.java"), StandardCharsets.UTF_8);
        String windowReadModel = Files.readString(repositoryRoot().resolve(
                "xa-mass-task-runtime/src/main/java/com/xa/mass/task/runtime/TaskRuntimeResultWindowReadModel.java"), StandardCharsets.UTF_8);
        String servingLane = Files.readString(repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java"), StandardCharsets.UTF_8);

        assertTrue(!readPort.contains("readFinalResults(")
                        && windowReadModel.contains("readFinalResults(FinalResultReadRequest request)"),
                "ordered final-result window must be re-owned by TaskRuntimeResultWindowReadModel, not TaskRuntimeReadPort");
        assertTrue(servingLane.contains("TaskRuntimeResultWindowReadModel resultWindowReadModel")
                        && servingLane.contains("resultWindowReadModel.readFinalResults"),
                "TaskRuntimeServingLane must route result-window reads through the separate read model");
    }

    @Test
    void engineServingResultPathUsesRuntimeResultFactNotOldResultCommand() throws IOException {
        Path servingLane = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java");
        Path mapper = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/runtime/TaskRuntimeResultFactMapper.java");
        String servingSource = Files.readString(servingLane, StandardCharsets.UTF_8);
        String mapperSource = Files.readString(mapper, StandardCharsets.UTF_8);
        List<String> violations = List.of(servingSource, mapperSource)
                .stream()
                .flatMap(source -> List.of(
                                "ResultApplyCommand",
                                "TaskRuntimeResultCommandMapper",
                                "RuntimeResultFact.from")
                        .stream()
                        .filter(source::contains))
                .distinct()
                .toList();

        assertTrue(violations.isEmpty(),
                "engine serving result path must use RuntimeResultFact and must not reintroduce old result command vocabulary: "
                        + violations);
        assertTrue(servingSource.contains("convergencePort.applyResult(fact)")
                        && mapperSource.contains("new RuntimeResultFact("),
                "engine serving result path must build RuntimeResultFact and apply it through the convergence port");
    }

    @Test
    void oldResultRuntimeHelpersAreDeletedFromTaskManager() throws IOException {
        String source = Files.readString(taskManagerSource(), StandardCharsets.UTF_8);
        List<String> deletedHelpers = List.of(
                "TaskWorkRuntime getTaskWorkRuntime",
                "TaskResultRuntime getTaskResultRuntime",
                "void requestTaskRetryDispatch",
                "java.util.Optional<ActiveLeaseRepairCandidate> getActiveLease",
                "java.util.Optional<TaskWorkEnvelope> getTaskWork",
                "java.util.Optional<RecentFinalWorkReceipt> getRecentFinalReceipt",
                "ResultApplyOutcome applyTaskWorkResult",
                "RuntimeResultApplyContext applyTaskWorkResultWithContext",
                "void applyTaskResultProgressOnce",
                "cleanupResultStageIfConverged");
        List<String> violations = deletedHelpers.stream()
                .filter(source::contains)
                .toList();

        assertTrue(violations.isEmpty(),
                "Old TaskResultService helper surface must be deleted from TaskManager: "
                        + violations);
    }

    @Test
    void oldTaskResultAndDelayedDispatchClassesAreDeletedFromEngine() {
        assertTrue(!Files.exists(taskResultServiceSource())
                        && !Files.exists(repositoryRoot().resolve(
                        "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultVisibleFinalCommitter.java"))
                        && !Files.exists(repositoryRoot().resolve(
                        "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskDispatchRequestService.java"))
                        && !Files.exists(repositoryRoot().resolve(
                        "xa-mass-engine/src/main/java/com/xa/mass/engine/DelayedDispatchSchedule.java"))
                        && !Files.exists(repositoryRoot().resolve(
                        "xa-mass-engine/src/main/java/com/xa/mass/engine/LocalDelayedDispatchSchedule.java")),
                "Old TaskResultService/visible-final and local delayed-dispatch classes must stay deleted");
    }

    @Test
    void oldAppendEnqueueOptionsResolverIsDeletedFromEngineMainline() throws IOException {
        Path engineMainSource = repositoryRoot().resolve("xa-mass-engine/src/main/java");

        assertTrue(!Files.exists(engineMainSource.resolve(
                        "com/xa/mass/engine/runtime/TaskRuntimeEnqueueOptionsResolver.java")),
                "old TaskRuntimeEnqueueOptionsResolver must stay deleted; append admission belongs to task-runtime commands");

        try (var paths = Files.walk(engineMainSource)) {
            List<Path> workEnqueueImports = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "com.xa.mass.runtime.api.WorkEnqueueOptions"))
                    .toList();
            assertTrue(workEnqueueImports.isEmpty(),
                    "engine main source must not import old WorkEnqueueOptions after append serving-lane cutover: "
                            + workEnqueueImports);
        }
    }

    @Test
    void engineServingLaneDoesNotImportOldTaskRuntimeCommandBuckets() throws IOException {
        Path engineMainSource = repositoryRoot().resolve("xa-mass-engine/src/main/java");
        List<String> oldRuntimeCommandBuckets = List.of(
                "com.xa.mass.task.runtime.AppendBatchCommand",
                "com.xa.mass.task.runtime.SchedulerDiscoveryCommand",
                "com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand",
                "com.xa.mass.task.runtime.ClaimReadyCommand",
                "com.xa.mass.task.runtime.ResultApplyCommand",
                "com.xa.mass.task.runtime.PollActiveLeaseRepairCommand");

        try (var paths = Files.walk(engineMainSource)) {
            List<Path> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> oldRuntimeCommandBuckets.stream().anyMatch(token -> contains(path, token)))
                    .toList();
            assertTrue(violations.isEmpty(),
                    "engine main source must use direct task-runtime ports/models, not old command buckets: "
                            + violations);
        }
    }

    @Test
    void oldClaimDtoSurfaceIsDeletedFromEngineClaimMainline() throws IOException {
        Path engineMainSource = repositoryRoot().resolve("xa-mass-engine/src/main/java");
        List<String> oldClaimDtos = List.of(
                "com.xa.mass.runtime.api.ClaimedTaskWork",
                "com.xa.mass.runtime.api.TaskWorkClaimOptions",
                "com.xa.mass.runtime.api.WorkerClaimTarget",
                "com.xa.mass.task.runtime.ClaimReadyCommand",
                "fromOldRuntimeClaim");

        try (var paths = Files.walk(engineMainSource)) {
            List<Path> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> oldClaimDtos.stream().anyMatch(token -> contains(path, token)))
                    .toList();
            assertTrue(violations.isEmpty(),
                    "engine claim mainline must use direct assignment claim parameters and ClaimedWorkItem, not old claim DTOs: "
                            + violations);
        }
    }

    @Test
    void activeLeaseMaintenancePortUsesTaskRuntimeRepairCandidatesNotOldLeaseRecords() throws IOException {
        Path leaseMaintenancePort = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskLeaseMaintenancePort.java");
        Path leaseWatchdog = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/watchdog/LeaseExpireWatchdog.java");
        Path resourceReleaseListener = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/listener/TaskResourceReleaseListener.java");
        List<Path> maintenanceMainline = List.of(leaseMaintenancePort, leaseWatchdog, resourceReleaseListener);

        List<Path> oldLeaseDtoViolations = maintenanceMainline.stream()
                .filter(path -> contains(path, "ActiveLeaseRecord"))
                .toList();
        assertTrue(oldLeaseDtoViolations.isEmpty(),
                "active lease maintenance mainline must use task-runtime repair candidates, not old ActiveLeaseRecord DTOs: "
                        + oldLeaseDtoViolations);

        String portSource = Files.readString(leaseMaintenancePort, StandardCharsets.UTF_8);
        assertTrue(portSource.contains("List<ActiveLeaseRepairCandidate> getActiveLeaseCandidates")
                        && portSource.contains("List<ActiveLeaseRepairCandidate> pollExpiredLeaseCandidates"),
                "TaskLeaseMaintenancePort must expose task-runtime repair candidate methods for active lease repair");
    }

    @Test
    void leaseExpiryResultPathUsesServingLaneTimeoutApplyNotOldRuntimeResult() throws IOException {
        Path servingLane = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java");
        String source = Files.readString(servingLane, StandardCharsets.UTF_8);
        String body = MethodGuard.methodBody(source, "public boolean expireLeasedWork");

        assertTrue(body.contains("TaskRuntimeResultFactMapper.fromLeaseTimeout")
                        && body.contains("applyResult(task")
                        && body.contains("outcome.accepted()"),
                "lease expiry must apply task-runtime result/finality outcome through TaskRuntimeServingLane");
        assertTrue(!body.contains("TaskWorkResult")
                        && !body.contains("TaskResultRuntime")
                        && !body.contains("TaskResultService"),
                "lease expiry must not route through old work/result runtime helpers");
    }

    @Test
    void taskRuntimePollingLoopsAreStarterHostedAndNoLongerOwnEngineSchedulers() throws IOException {
        Path leaseWatchdog = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/watchdog/LeaseExpireWatchdog.java");
        Path runtimeReadyPump = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/watchdog/RuntimeReadyDispatchPump.java");
        Path engineRuntimeKernel = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/EngineRuntimeKernel.java");
        Path massEngine = repositoryRoot().resolve(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/MassEngine.java");
        Path engineConfig = repositoryRoot().resolve(
                "xa-mass-engine-starter/src/main/java/com/xa/mass/starter/config/EngineConfig.java");

        String watchdogSource = Files.readString(leaseWatchdog, StandardCharsets.UTF_8);
        assertTrue(watchdogSource.contains("implements EngineRuntimeLoop")
                        && watchdogSource.contains("public void runOnce()")
                        && !watchdogSource.contains("ScheduledExecutorService")
                        && !watchdogSource.contains("newSingleThreadScheduledExecutor")
                        && !watchdogSource.contains("scheduleAtFixedRate"),
                "LeaseExpireWatchdog must expose a starter-hosted tick and must not create its own scheduler");

        String runtimeReadyPumpSource = Files.readString(runtimeReadyPump, StandardCharsets.UTF_8);
        assertTrue(runtimeReadyPumpSource.contains("implements EngineRuntimeLoop")
                        && runtimeReadyPumpSource.contains("public void runOnce()")
                        && !runtimeReadyPumpSource.contains("ScheduledExecutorService")
                        && !runtimeReadyPumpSource.contains("newSingleThreadScheduledExecutor")
                        && !runtimeReadyPumpSource.contains("scheduleWithFixedDelay"),
                "RuntimeReadyDispatchPump must expose a starter-hosted tick and must not create its own scheduler");

        String kernelSource = Files.readString(engineRuntimeKernel, StandardCharsets.UTF_8);
        assertTrue(kernelSource.contains("taskRuntimeLoops = List.of(runtimeReadyDispatchPump, leaseWatchdog)")
                        && !kernelSource.contains("leaseWatchdog.start(")
                        && !kernelSource.contains("leaseWatchdog.stop(")
                        && !kernelSource.contains("runtimeReadyDispatchPump.start("),
                "EngineRuntimeKernel must contribute task-runtime polling loops instead of starting their schedulers");

        String massEngineSource = Files.readString(massEngine, StandardCharsets.UTF_8);
        String engineConfigSource = Files.readString(engineConfig, StandardCharsets.UTF_8);
        assertTrue(massEngineSource.contains(
                        "config.registerStarterOwnedTaskRuntimeLoops(toTaskRuntimeLoops(startedRuntime.taskRuntimeLoops()))")
                        && engineConfigSource.contains("ensureTaskRuntimeHandle().registerLoops(loops)"),
                "MassEngine must register kernel task-runtime loops with the starter-owned TaskRuntimeHandle");
    }

    @Test
    void engineResultReadMainlineUsesTaskRuntimeFinalResultContractNotOldRuntimeRows() {
        Path taskManager = taskManagerSource();
        Path servingLane = repositoryRoot().resolve(
                "xa-mass-engine/src/main/java/com/xa/mass/engine/TaskRuntimeServingLane.java");
        List<Path> resultReadMainline = List.of(taskManager, servingLane);

        List<Path> oldResultDtoViolations = resultReadMainline.stream()
                .filter(path -> contains(path, "TaskResultRuntimeRow") || contains(path, "TaskResultWindow"))
                .toList();
        assertTrue(oldResultDtoViolations.isEmpty(),
                "engine result read mainline must use task-runtime FinalResultRow/FinalResultWindow, not old result runtime DTOs: "
                        + oldResultDtoViolations);
    }

    @Test
    void engineProgressMainlineUsesTaskRuntimeProgressSnapshotNotOldWorkStats() throws IOException {
        Path engineMainSource = repositoryRoot().resolve("xa-mass-engine/src/main/java");

        try (var paths = Files.walk(engineMainSource)) {
            List<Path> oldProgressDtoViolations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "com.xa.mass.runtime.api.TaskWorkStats")
                            || contains(path, "TaskRuntimeProgressSnapshotMapper"))
                    .toList();
            assertTrue(oldProgressDtoViolations.isEmpty(),
                    "engine progress/terminal mainline must use task-runtime TaskRuntimeProgressSnapshot, not old TaskWorkStats DTOs: "
                            + oldProgressDtoViolations);
        }
    }

    @Test
    void oldRedisRuntimeTraceEngineBridgeIsDeletedFromPlatformInfra() throws IOException {
        Path runtimeRedisModule = repositoryRoot().resolve("platform_infra/mass-runtime-redis");
        String pom = Files.readString(runtimeRedisModule.resolve("pom.xml"), StandardCharsets.UTF_8);

        assertTrue(!Files.exists(runtimeRedisModule.resolve(
                        "src/test/java/com/xa/mass/runtime/redis/RedisRuntimeTraceIntegrationTest.java")),
                "old Redis runtime trace integration must not keep the old TaskManager runtime constructor alive");
        assertTrue(!pom.contains("<artifactId>xa-mass-engine</artifactId>")
                        && !pom.contains("<artifactId>mass-storage-memory</artifactId>"),
                "mass-runtime-redis must not depend on engine/storage-memory just to prove old runtime integration");

        try (var paths = Files.walk(runtimeRedisModule.resolve("src"))) {
            List<Path> engineImportFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "com.xa.mass.engine"))
                    .toList();
            assertTrue(engineImportFiles.isEmpty(),
                    "mass-runtime-redis must not import engine classes: " + engineImportFiles);
        }
    }

    @Test
    void currentEntryDocsDoNotDescribeDeletedRuntimeApisAsCurrentOwners() {
        List<Path> currentEntryDocs = List.of(
                repositoryRoot().resolve("AGENTS.md"),
                repositoryRoot().resolve("CLAUDE.md"),
                repositoryRoot().resolve("README.zh-CN.md"),
                repositoryRoot().resolve("architecture/README.zh-CN.md"),
                repositoryRoot().resolve("transport/TRANSPORT_BOUNDARY_BASELINE.md"),
                repositoryRoot().resolve("xa-mass-engine/doc/baseline/PLATFORM_SCHEDULING_PLANE_POLICY_PROOF_INVENTORY.md"));
        List<String> staleOwnerPhrases = List.of(
                "`TaskWorkRuntime` for ready/delayed/lease/counter truth",
                "`TaskResultRuntime` for stable-final result rows",
                "TaskWorkRuntime enqueue",
                "TaskResultRuntime visible final row",
                "ready/lease/retry | `TaskWorkRuntime`",
                "public result | `TaskResultRuntime`",
                "`TaskWorkRuntime` remains the only owner",
                "| `resultFinalityPolicy` | derived by task policy preset resolution | `ResolvedTaskSchedulingPolicy.resultFinalityPolicy` | `TaskResultService` |",
                "| `backpressureClass` | derived by task policy preset resolution | `ResolvedTaskSchedulingPolicy.backpressurePolicy` | `TaskRuntimeEnqueueOptionsResolver` via resolved backpressure policy |");

        List<String> violations = currentEntryDocs.stream()
                .flatMap(path -> staleOwnerPhrases.stream()
                        .filter(phrase -> containsAscii(path, phrase))
                        .map(phrase -> path + " contains stale task-runtime owner phrase: " + phrase))
                .toList();

        assertTrue(violations.isEmpty(),
                "Current entry docs must describe xa-mass-task-runtime/TaskRuntimeServingLane as the runtime owner, "
                        + "not deleted TaskWorkRuntime/TaskResultRuntime APIs: " + violations);
    }

    @Test
    void traceFixturesDoNotNameDeletedResultServiceAsRuntimeSource() throws IOException {
        Path traceTests = repositoryRoot().resolve("xa-mass-trace/src/test/java");

        try (var paths = Files.walk(traceTests)) {
            List<Path> violations = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "\"source\", \"TaskResultService\"")
                            || contains(path, "\"source\":\"TaskResultService\""))
                    .toList();
            assertTrue(violations.isEmpty(),
                    "Trace fixtures must not keep deleted TaskResultService as the runtime source: "
                            + violations);
        }
    }

    @Test
    void lifecycleProofDoesNotConstructLegacyTaskRuntimeAndOldExampleIsRemoved() throws IOException {
        String lifecycleTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskManagerLifecycleTest.java"),
                StandardCharsets.UTF_8);
        String servingLaneTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskRuntimeServingLaneTest.java"),
                StandardCharsets.UTF_8);
        String kernelLifecycleTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskKernelLifecycleTest.java"),
                StandardCharsets.UTF_8);
        String recoveryTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskRuntimeRecoveryPortTest.java"),
                StandardCharsets.UTF_8);
        String dispatchBinderTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/listener/SimpleTaskDispatchBinderTest.java"),
                StandardCharsets.UTF_8);
        String resultConvergenceTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskResultRuntimeConvergenceTest.java"),
                StandardCharsets.UTF_8);
        String resultConcurrencyTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskResultConcurrencyConvergenceTest.java"),
                StandardCharsets.UTF_8);
        String schedulingHarness = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskSchedulingTestHarness.java"),
                StandardCharsets.UTF_8);
        String redispatchCompetitionTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskRedispatchCompetitionTest.java"),
                StandardCharsets.UTF_8);
        String idleClosePolicyTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/TaskIdleClosePolicyBehaviorTest.java"),
                StandardCharsets.UTF_8);
        String resourceReleaseListenerTest = Files.readString(
                repositoryRoot().resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/listener/TaskResourceReleaseListenerTest.java"),
                StandardCharsets.UTF_8);

        assertTrue(List.of(
                        lifecycleTest,
                        servingLaneTest,
                        kernelLifecycleTest,
                        recoveryTest,
                        dispatchBinderTest,
                        resultConvergenceTest,
                        resultConcurrencyTest,
                        schedulingHarness,
                        idleClosePolicyTest).stream().noneMatch(source -> source.contains("disabledLegacyRuntime")),
                "Migrated serving-lane proofs must use TaskManager no-old-runtime constructor, not local disabled old-runtime helpers");
        assertTrue(!lifecycleTest.contains("com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime")
                        && !lifecycleTest.contains("com.xa.mass.runtime.memory.InMemoryTaskResultRuntime")
                        && !lifecycleTest.contains("new InMemoryTaskWorkRuntime")
                        && !lifecycleTest.contains("new InMemoryTaskResultRuntime"),
                "TaskManagerLifecycleTest must prove lifecycle behavior through TaskRuntimeServingLane, not runnable legacy runtime");
        assertTrue(!servingLaneTest.contains("com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime")
                        && !servingLaneTest.contains("com.xa.mass.runtime.memory.InMemoryTaskResultRuntime")
                        && !servingLaneTest.contains("new InMemoryTaskWorkRuntime")
                        && !servingLaneTest.contains("new InMemoryTaskResultRuntime"),
                "TaskRuntimeServingLaneTest must use the TaskManager no-old-runtime constructor, not legacy task runtime");
        assertTrue(!kernelLifecycleTest.contains("com.xa.mass.runtime.memory")
                        && !kernelLifecycleTest.contains("new InMemoryTaskWorkRuntime")
                        && !kernelLifecycleTest.contains("new InMemoryTaskResultRuntime")
                        && !kernelLifecycleTest.contains("getTaskWorkRuntime(")
                        && !kernelLifecycleTest.contains("getTaskResultRuntime("),
                "TaskKernelLifecycleTest must prove shell/intake/delete behavior through TaskRuntimeServingLane, not old runtime getters or memory runtimes");
        assertTrue(!recoveryTest.contains("com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime")
                        && !recoveryTest.contains("com.xa.mass.runtime.memory.InMemoryTaskResultRuntime")
                        && !recoveryTest.contains("ReadyTaskIdsOverrideRuntime")
                        && !recoveryTest.contains("readyTaskIds("),
                "TaskRuntimeRecoveryPortTest must prove recovery through TaskRuntimeServingLane scheduler discovery, not old readyTaskIds overrides");
        assertTrue(!dispatchBinderTest.contains("com.xa.mass.runtime.memory")
                        && !dispatchBinderTest.contains("new InMemoryTaskWorkRuntime")
                        && !dispatchBinderTest.contains("new InMemoryTaskResultRuntime")
                        && !dispatchBinderTest.contains("getTaskWorkRuntime(")
                        && !dispatchBinderTest.contains("getTaskResultRuntime("),
                "SimpleTaskDispatchBinderTest must prove assignment/dispatch through TaskRuntimeServingLane, not old runtime getters or memory runtimes");
        assertTrue(!resultConvergenceTest.contains("com.xa.mass.runtime.memory")
                        && !resultConvergenceTest.contains("new InMemoryTaskWorkRuntime")
                        && !resultConvergenceTest.contains("new InMemoryTaskResultRuntime")
                        && !resultConvergenceTest.contains("getTaskWorkRuntime(")
                        && !resultConvergenceTest.contains("getTaskResultRuntime(")
                        && !resultConvergenceTest.contains("scanRepairCandidates")
                        && !resultConvergenceTest.contains("TaskResultRepair")
                        && !resultConvergenceTest.contains("discardStaged"),
                "TaskResultRuntimeConvergenceTest must prove result convergence through TaskRuntimeServingLane, not old result repair or staged callback runtime");
        assertTrue(!resultConcurrencyTest.contains("com.xa.mass.runtime.memory")
                        && !resultConcurrencyTest.contains("new InMemoryTaskWorkRuntime")
                        && !resultConcurrencyTest.contains("new InMemoryTaskResultRuntime")
                        && !resultConcurrencyTest.contains("getTaskWorkRuntime(")
                        && !resultConcurrencyTest.contains("getTaskResultRuntime(")
                        && !resultConcurrencyTest.contains("scanRepairCandidates")
                        && !resultConcurrencyTest.contains("TaskResultRepair")
                        && !resultConcurrencyTest.contains("TaskWorkResult")
                        && !resultConcurrencyTest.contains("TaskWorkEnvelope")
                        && !resultConcurrencyTest.contains("TaskWorkRuntimeStats"),
                "TaskResultConcurrencyConvergenceTest must prove result concurrency through TaskRuntimeServingLane, not old work/result runtime DTOs");
        assertTrue(!schedulingHarness.contains("com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime")
                        && !schedulingHarness.contains("com.xa.mass.runtime.memory.InMemoryTaskResultRuntime")
                        && !schedulingHarness.contains("new InMemoryTaskWorkRuntime")
                        && !schedulingHarness.contains("new InMemoryTaskResultRuntime")
                        && !schedulingHarness.contains("getTaskWorkRuntime(")
                        && !schedulingHarness.contains("getTaskResultRuntime(")
                        && schedulingHarness.contains("new InMemoryTaskRuntime")
                        && schedulingHarness.contains("installTaskRuntimeServingLane"),
                "TaskSchedulingTestHarness must drive scheduling tests through TaskRuntimeServingLane and the no-old-runtime constructor, not runnable legacy task runtime");
        assertTrue(!redispatchCompetitionTest.contains("TaskWorkResult")
                        && !redispatchCompetitionTest.contains("applyTaskWorkResult(")
                        && redispatchCompetitionTest.contains("RuntimeResultFact"),
                "TaskRedispatchCompetitionTest must prove stale lease rejection through task-runtime RuntimeResultFact, not old TaskWorkResult DTOs");
        assertTrue(!idleClosePolicyTest.contains("com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime")
                        && !idleClosePolicyTest.contains("com.xa.mass.runtime.memory.InMemoryTaskResultRuntime")
                        && !idleClosePolicyTest.contains("new InMemoryTaskWorkRuntime")
                        && !idleClosePolicyTest.contains("new InMemoryTaskResultRuntime")
                        && !idleClosePolicyTest.contains("getTaskWorkRuntime(")
                        && !idleClosePolicyTest.contains("getTaskResultRuntime(")
                        && idleClosePolicyTest.contains("installTaskRuntimeServingLane"),
                "TaskIdleClosePolicyBehaviorTest must prove intake close behavior through TaskRuntimeServingLane, not old runtime getters or memory runtimes");
        assertTrue(!resourceReleaseListenerTest.contains("com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime")
                        && !resourceReleaseListenerTest.contains("new InMemoryTaskWorkRuntime")
                        && !resourceReleaseListenerTest.contains("TaskWorkEnvelope")
                        && !resourceReleaseListenerTest.contains("claimReady("),
                "TaskResourceReleaseListenerTest must not construct legacy runtime just to fabricate active lease evidence");
        assertTrue(!Files.exists(repositoryRoot()
                        .resolve("xa-mass-engine/src/test/java/com/xa/mass/engine/example/EngineExample.java")),
                "EngineExample old-runtime construction path must stay deleted; use starter-backed examples instead");
        assertTrue(!Files.exists(repositoryRoot()
                        .resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultRepairPump.java")),
                "TaskResultRepairPump old result-runtime background repair lifecycle must stay deleted");
    }

    private static Path taskManagerSource() {
        return repositoryRoot().resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java");
    }

    private static Path taskResultServiceSource() {
        return repositoryRoot().resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskResultService.java");
    }

    private static boolean contains(Path path, String needle) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static boolean containsAscii(Path path, String needle) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1).contains(needle);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml"))
                    && Files.exists(current.resolve("xa-mass-engine/src/main/java/com/xa/mass/engine/TaskManager.java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from " + System.getProperty("user.dir"));
    }

    private record MethodGuard(String name,
                               String methodMarker,
                               String servingLaneCall,
                               String legacyFallbackCall) {

        private boolean satisfiedBy(String source) {
            String body = methodBody(source, methodMarker);
            int servingLaneIndex = body.indexOf(servingLaneCall);
            int legacyFallbackIndex = body.indexOf(legacyFallbackCall);
            return servingLaneIndex >= 0 && legacyFallbackIndex >= 0 && servingLaneIndex < legacyFallbackIndex;
        }

        private static String methodBody(String source, String marker) {
            int markerIndex = source.indexOf(marker);
            if (markerIndex < 0) {
                return "";
            }
            int bodyStart = source.indexOf('{', markerIndex);
            if (bodyStart < 0) {
                return "";
            }
            int depth = 0;
            for (int i = bodyStart; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        return source.substring(bodyStart, i + 1);
                    }
                }
            }
            return "";
        }
    }
}
