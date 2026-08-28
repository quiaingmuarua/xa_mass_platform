package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
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
    private static final long BOUNDARY_RECOVERY_RETRY_MILLIS = 10;

    private final DispatchConvergenceApplication application;
    private final AssignmentDispatchConfig assignmentConfig;
    private final WorkerServiceabilityDispatchAssemblyConfig
            serviceabilityConfig;

    private DispatchConvergenceRuntime(
            DispatchConvergenceApplication application,
            AssignmentDispatchConfig assignmentConfig,
            WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig
    ) {
        this.application = Objects.requireNonNull(
                application,
                "application"
        );
        this.assignmentConfig = Objects.requireNonNull(
                assignmentConfig,
                "assignmentConfig"
        );
        this.serviceabilityConfig = Objects.requireNonNull(
                serviceabilityConfig,
                "serviceabilityConfig"
        );
    }

    public static DispatchConvergenceRuntime assemble(
            PolicyPreset preset,
            long hotEligibilityFloorMillis,
            TaskRuntime taskRuntime,
            TaskScoreBandCore taskScores,
            TaskItemScoreBandCore itemScores,
            TaskResourceCatalog taskCatalog,
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerServiceabilityRuntime serviceability,
            CandidateWorkerCache candidateCache
    ) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(taskRuntime, "taskRuntime");
        Objects.requireNonNull(taskScores, "taskScores");
        Objects.requireNonNull(itemScores, "itemScores");
        Objects.requireNonNull(taskCatalog, "taskCatalog");
        Objects.requireNonNull(workerScores, "workerScores");
        Objects.requireNonNull(workerCatalog, "workerCatalog");
        Objects.requireNonNull(workerCommands, "workerCommands");
        Objects.requireNonNull(serviceability, "serviceability");
        Objects.requireNonNull(candidateCache, "candidateCache");

        AssignmentDispatchConfig assignment = assignmentConfigForPreset(
                preset
        );
        WorkerServiceabilityDispatchAssemblyConfig serviceabilityConfig =
                serviceabilityConfigForPreset(
                        preset,
                        hotEligibilityFloorMillis
                );
        WorkerCandidateMatcher matcher = new WorkerCandidateMatcher(
                workerCatalog
        );
        WorkerCandidateAcquirer candidateAcquirer =
                new WorkerCandidateAcquirer(
                        candidateCache,
                        workerScores,
                        matcher,
                        AssignmentDispatchConfig.WORKER_SCAN_LIMIT,
                        serviceabilityConfig.enabled()
                                ? serviceabilityConfig
                                .hotEligibilityFloorMillis()
                                : null
                );
        TaskWorkerAllocationPolicy allocation =
                new TaskWorkerAllocationPolicy(
                        candidateAcquirer,
                        candidateCache
                );
        TaskInitializationPolicy initialization =
                new TaskInitializationPolicy(taskScores, itemScores);
        TaskItemDispatcher itemDispatcher = new TaskItemDispatcher(
                itemScores,
                taskRuntime,
                candidateAcquirer,
                new ResultContextCodec()
        );
        TaskDispatchPolicy dispatch = new TaskDispatchPolicy(
                taskScores,
                workerCommands,
                itemScores,
                itemDispatcher
        );
        WorkerServiceabilityDispatchPolicy serviceabilityDispatch =
                new WorkerServiceabilityDispatchPolicy(
                        workerScores,
                        workerCatalog,
                        serviceability
                );
        DispatchConvergenceApplication application =
                new DispatchConvergenceApplication(
                        new TaskSchedulingBatchSource(
                                taskScores,
                                taskCatalog
                        ),
                        initialization,
                        allocation,
                        dispatch,
                        serviceabilityDispatch
                );
        return new DispatchConvergenceRuntime(
                application,
                assignment,
                serviceabilityConfig
        );
    }

    public void start() {
        application.start(assignmentConfig, serviceabilityConfig);
    }

    public void stop(long timeoutMillis) {
        application.stop(timeoutMillis);
    }

    public boolean isRunning() {
        return application.isRunning();
    }

    public String state() {
        return application.state();
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

    static WorkerServiceabilityDispatchAssemblyConfig
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
                yield WorkerServiceabilityDispatchAssemblyConfig.disabled();
            }
            case SERVICEABILITY_DEFAULT, SCENARIO_LAB ->
                    new WorkerServiceabilityDispatchAssemblyConfig(
                            true,
                            hotEligibilityFloorMillis,
                            WorkerServiceabilityDispatchLaneConfig.defaults()
                    );
            case RUNTIME_BOUNDARY_PROOF ->
                    new WorkerServiceabilityDispatchAssemblyConfig(
                            true,
                            hotEligibilityFloorMillis,
                            new WorkerServiceabilityDispatchLaneConfig(
                                    WorkerServiceabilityDispatchLaneConfig
                                            .DEFAULT_INTERVAL_MILLIS,
                                    new WorkerServiceabilityDispatchConfig(
                                            BOUNDARY_RECOVERY_RETRY_MILLIS,
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_PROBE_SWEEP_RESTART_DELAY_MILLIS,
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_MAX_RECOVERY_ATTEMPTS,
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_HOT_SCAN_LIMIT,
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_RECOVERY_SCAN_LIMIT,
                                            WorkerServiceabilityDispatchConfig
                                                    .DEFAULT_PROBE_EXCLUDED_ENDPOINT_IDS
                                    )
                            )
                    );
        };
    }
}
