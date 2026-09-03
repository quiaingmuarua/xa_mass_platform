package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.util.Objects;

/**
 * Module-internal lifecycle bridge for the Dispatch Convergence package.
 *
 * <p>This type is public only so the parent package can assemble the fixed
 * runtime. Consumers outside {@code kernel_pacer_jvm} must use
 * {@code KernelPacerRuntime}.</p>
 */
public final class DispatchConvergenceRuntime {

    private static final long LAB_INTERVAL_MILLIS = 20;
    private static final long BOUNDARY_PROBE_RETRY_MILLIS = 10;

    private final DispatchMainScheduler mainScheduler;
    private Thread schedulerThread;

    DispatchConvergenceRuntime(DispatchMainScheduler mainScheduler) {
        this.mainScheduler = Objects.requireNonNull(
                mainScheduler,
                "mainScheduler"
        );
    }

    public static DispatchConvergenceRuntime assemble(
            PolicyPreset preset,
            long hotEligibilityFloorMillis,
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog,
            CandidateWorkerCache candidateCache,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            TaskRuntime taskRuntime,
            WorkerCommandRuntime workerCommands,
            WorkerServiceabilityRuntime serviceability,
            WorkerMatchQueue workerMatchQueue,
            ResultContextCodec resultContextCodec
    ) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(
                resultContextCodec,
                "resultContextCodec"
        );

        AssignmentDispatchConfig assignment = assignmentConfigForPreset(
                preset
        );
        WorkerServiceabilityDispatchConfig serviceabilityConfig =
                serviceabilityConfigForPreset(
                        preset,
                        hotEligibilityFloorMillis
                );
        Long assignmentHotFloor = serviceabilityConfig == null
                ? null
                : serviceabilityConfig.hotEligibilityFloorMillis();
        TaskWorkerAllocationPolicy allocation =
                new TaskWorkerAllocationPolicy(
                        workerScores,
                        candidateCache,
                        workerMatchQueue,
                        assignmentHotFloor
                );
        WorkerCandidateSelectionPolicy candidateSelection =
                new WorkerCandidateSelectionPolicy(
                        workerScores,
                        candidateCache,
                        workerCatalog,
                        assignmentHotFloor
                );
        TaskInitializationPolicy initialization =
                new TaskInitializationPolicy(
                        itemScores,
                        taskScores
                );
        TaskAssignmentDispatcher assignmentDispatcher =
                new TaskAssignmentDispatcher(
                        itemScores,
                        workerScores,
                        workerCommands,
                        resultContextCodec
                );
        TaskIdleSettlement idleSettlement = new TaskIdleSettlement(
                taskScores,
                itemScores
        );
        TaskDispatchPolicy dispatch = new TaskDispatchPolicy(
                taskScores,
                itemScores,
                taskRuntime,
                assignmentDispatcher,
                idleSettlement,
                candidateSelection
        );
        WorkerServiceabilityDispatchPolicy serviceabilityDispatch =
                serviceabilityConfig == null
                        ? null
                        : new WorkerServiceabilityDispatchPolicy(
                                workerScores,
                                workerCatalog,
                                Objects.requireNonNull(
                                        serviceability,
                                        "serviceability"
                                )
                        );
        DispatchMainScheduler scheduler = new DispatchMainScheduler(
                taskScores,
                taskCatalog,
                initialization,
                allocation,
                dispatch,
                serviceabilityDispatch,
                assignment,
                serviceabilityConfig
        );
        return new DispatchConvergenceRuntime(scheduler);
    }

    public synchronized void start() {
        if (schedulerThread != null) {
            throw new IllegalStateException(
                    "Dispatch Convergence is already started"
            );
        }
        Thread started = Thread.ofPlatform()
                .name("dispatch-main-scheduler")
                .daemon(false)
                .unstarted(mainScheduler::run);
        schedulerThread = started;
        try {
            started.start();
        } catch (RuntimeException | Error failure) {
            schedulerThread = null;
            throw failure;
        }
    }

    public void stop(long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive"
            );
        }
        Thread current;
        synchronized (this) {
            current = schedulerThread;
            if (current == null) {
                return;
            }
            current.interrupt();
        }
        try {
            current.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Dispatch Convergence shutdown was interrupted",
                    interrupted
            );
        }
        if (current.isAlive()) {
            throw new IllegalStateException(
                    "Dispatch Main Scheduler did not stop within its budget"
            );
        }
        synchronized (this) {
            if (schedulerThread == current) {
                schedulerThread = null;
            }
        }
    }

    public synchronized boolean isRunning() {
        return schedulerThread != null && schedulerThread.isAlive();
    }

    public synchronized String state() {
        if (schedulerThread == null) {
            return "STOPPED";
        }
        return schedulerThread.isAlive() ? "RUNNING" : "FAILED";
    }

    static AssignmentDispatchConfig assignmentConfigForPreset(
            PolicyPreset preset
    ) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case SCENARIO_LAB -> AssignmentDispatchConfig.create(
                    LAB_INTERVAL_MILLIS,
                    LAB_INTERVAL_MILLIS,
                    LAB_INTERVAL_MILLIS
            );
            case DEFAULT, SERVICEABILITY_DEFAULT,
                    RUNTIME_BOUNDARY_PROOF ->
                    AssignmentDispatchConfig.defaults();
        };
    }

    static WorkerServiceabilityDispatchConfig
            serviceabilityConfigForPreset(
                    PolicyPreset preset,
                    long hotEligibilityFloorMillis
            ) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case DEFAULT -> {
                if (hotEligibilityFloorMillis != 0) {
                    throw new IllegalArgumentException(
                            "disabled Serviceability must not carry a HOT "
                                    + "floor"
                    );
                }
                yield null;
            }
            case SERVICEABILITY_DEFAULT, SCENARIO_LAB ->
                    WorkerServiceabilityDispatchConfig.defaults(
                            hotEligibilityFloorMillis
                    );
            case RUNTIME_BOUNDARY_PROOF ->
                    new WorkerServiceabilityDispatchConfig(
                            WorkerServiceabilityDispatchConfig
                                    .DEFAULT_INTERVAL_MILLIS,
                            hotEligibilityFloorMillis,
                            BOUNDARY_PROBE_RETRY_MILLIS,
                            WorkerServiceabilityDispatchConfig
                                    .DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS,
                            WorkerServiceabilityDispatchConfig
                                    .DEFAULT_MAX_RECOVERY_ATTEMPTS,
                            WorkerServiceabilityDispatchConfig
                                    .DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
                    );
        };
    }
}
