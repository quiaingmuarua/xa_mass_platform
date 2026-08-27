package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The finite production configuration for all Java Kernel Pacers.
 *
 * <p>The public Runtime selects one checked preset. Concrete policy values
 * remain package-private and cannot be assembled dynamically by Server.</p>
 */
record KernelPacerPolicyConfig(
        ResultRoutingApplicationConfig resultRouting,
        WorkerServiceabilityAssemblyConfig workerServiceability,
        AssignmentDispatchApplicationConfig assignmentDispatch
) {

    private static final long LAB_INTERVAL_MILLIS = 20;
    private static final long BOUNDARY_RECOVERY_RETRY_MILLIS = 10;
    private static final int BOUNDARY_RESULT_REPORT_LIMIT = 100;

    public KernelPacerPolicyConfig {
        Objects.requireNonNull(resultRouting, "resultRouting");
        Objects.requireNonNull(workerServiceability, "workerServiceability");
        Objects.requireNonNull(assignmentDispatch, "assignmentDispatch");
    }

    static KernelPacerPolicyConfig forPreset(PolicyPreset preset) {
        return forPreset(preset, System::currentTimeMillis);
    }

    static KernelPacerPolicyConfig forPreset(
            PolicyPreset preset,
            LongSupplier currentTimeMillis
    ) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(currentTimeMillis, "currentTimeMillis");
        return switch (preset) {
            case DEFAULT -> defaultPolicy();
            case SERVICEABILITY_DEFAULT -> serviceabilityDefaultPolicy(
                    currentTimeMillis
            );
            case SCENARIO_LAB -> scenarioLabPolicy(currentTimeMillis);
            case RUNTIME_BOUNDARY_PROOF -> runtimeBoundaryPolicy(
                    currentTimeMillis
            );
        };
    }

    private static KernelPacerPolicyConfig defaultPolicy() {
        return new KernelPacerPolicyConfig(
                new ResultRoutingApplicationConfig(
                        ResultRoutingApplicationConfig.DEFAULT_INTERVAL_MILLIS
                ),
                WorkerServiceabilityAssemblyConfig.disabled(),
                AssignmentDispatchApplicationConfig.defaults()
        );
    }

    private static KernelPacerPolicyConfig serviceabilityDefaultPolicy(
            LongSupplier currentTimeMillis
    ) {
        return new KernelPacerPolicyConfig(
                new ResultRoutingApplicationConfig(
                        ResultRoutingApplicationConfig.DEFAULT_INTERVAL_MILLIS
                ),
                enabledServiceability(
                        currentTimeMillis,
                        WorkerServiceabilityResultApplicationConfig.defaults(),
                        WorkerServiceabilityDispatchApplicationConfig.defaults()
                ),
                AssignmentDispatchApplicationConfig.defaults()
        );
    }

    private static KernelPacerPolicyConfig scenarioLabPolicy(
            LongSupplier currentTimeMillis
    ) {
        return new KernelPacerPolicyConfig(
                new ResultRoutingApplicationConfig(LAB_INTERVAL_MILLIS),
                enabledServiceability(
                        currentTimeMillis,
                        WorkerServiceabilityResultApplicationConfig.defaults(),
                        WorkerServiceabilityDispatchApplicationConfig.defaults()
                ),
                AssignmentDispatchApplicationConfig.create(
                        LAB_INTERVAL_MILLIS,
                        LAB_INTERVAL_MILLIS,
                        LAB_INTERVAL_MILLIS,
                        AssignmentDispatchApplicationConfig
                                .DEFAULT_RUNNING_TASK_SOFT_LIMIT
                )
        );
    }

    private static KernelPacerPolicyConfig runtimeBoundaryPolicy(
            LongSupplier currentTimeMillis
    ) {
        WorkerServiceabilityResultConfig result =
                new WorkerServiceabilityResultConfig(
                        WorkerServiceabilityResultConfig
                                .DEFAULT_MAX_RECOVERY_ATTEMPTS,
                        BOUNDARY_RESULT_REPORT_LIMIT,
                        WorkerServiceabilityResultConfig
                                .DEFAULT_EVIDENCE_MAX_AGE_MILLIS
                );
        WorkerServiceabilityDispatchConfig dispatch =
                new WorkerServiceabilityDispatchConfig(
                        WorkerServiceabilityDispatchConfig
                                .DEFAULT_TASK_SCAN_LIMIT,
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
                );
        return new KernelPacerPolicyConfig(
                new ResultRoutingApplicationConfig(
                        ResultRoutingApplicationConfig.DEFAULT_INTERVAL_MILLIS
                ),
                enabledServiceability(
                        currentTimeMillis,
                        new WorkerServiceabilityResultApplicationConfig(
                                LAB_INTERVAL_MILLIS,
                                result
                        ),
                        new WorkerServiceabilityDispatchApplicationConfig(
                                WorkerServiceabilityDispatchApplicationConfig
                                        .DEFAULT_INTERVAL_MILLIS,
                                dispatch
                        )
                ),
                AssignmentDispatchApplicationConfig.defaults()
        );
    }

    private static WorkerServiceabilityAssemblyConfig enabledServiceability(
            LongSupplier currentTimeMillis,
            WorkerServiceabilityResultApplicationConfig result,
            WorkerServiceabilityDispatchApplicationConfig dispatch
    ) {
        long current = currentTimeMillis.getAsLong();
        long floor = current / WorkerScoreCore.SLOT_MILLIS
                * WorkerScoreCore.SLOT_MILLIS;
        return new WorkerServiceabilityAssemblyConfig(
                true,
                floor,
                result,
                dispatch
        );
    }
}
