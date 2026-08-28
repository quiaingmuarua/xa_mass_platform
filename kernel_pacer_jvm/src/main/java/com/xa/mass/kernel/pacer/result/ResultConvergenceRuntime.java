package com.xa.mass.kernel.pacer.result;

import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.pacer.KernelPacerRuntime.PolicyPreset;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.task.TaskItemResultEvents;
import com.xa.mass.kernel.worker.WorkerExecutionResultEvents;
import com.xa.mass.kernel.worker.WorkerServiceabilityEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Module-internal lifecycle bridge for the Result Convergence package.
 *
 * <p>This type is public only so the parent package can assemble the fixed
 * runtime. Consumers outside {@code kernel_pacer_jvm} must use
 * {@code KernelPacerRuntime}.</p>
 */
public final class ResultConvergenceRuntime {

    private static final long LAB_INTERVAL_MILLIS = 20;
    private static final int BOUNDARY_RESULT_REPORT_LIMIT = 100;

    private final ResultConvergenceApplication application;

    private ResultConvergenceRuntime(
            ResultConvergenceApplication application
    ) {
        this.application = Objects.requireNonNull(
                application,
                "application"
        );
    }

    public static ResultConvergenceRuntime assemble(
            PolicyPreset preset,
            long hotEligibilityFloorMillis,
            TaskResultRuntime taskResults,
            TaskItemResultEvents taskItemEvents,
            WorkerExecutionResultEvents workerExecutionEvents,
            WorkerServiceabilityEvents workerServiceabilityEvents,
            WorkerServiceabilityRuntime serviceability
    ) {
        Objects.requireNonNull(preset, "preset");
        Objects.requireNonNull(taskResults, "taskResults");
        Objects.requireNonNull(taskItemEvents, "taskItemEvents");
        Objects.requireNonNull(
                workerExecutionEvents,
                "workerExecutionEvents"
        );
        Objects.requireNonNull(
                workerServiceabilityEvents,
                "workerServiceabilityEvents"
        );
        Objects.requireNonNull(serviceability, "serviceability");

        ResultConvergenceConfig convergence = configForPreset(preset);
        TaskResultBatchPolicy taskPolicy = new TaskResultBatchPolicy(
                taskItemEvents,
                workerExecutionEvents
        );
        List<ResultLane> lanes = new ArrayList<>();
        lanes.add(new ResultLane(
                ResultLaneId.TASK_SUCCESS,
                ResultConvergenceConfig.TASK_RESULT_BATCH_LIMIT,
                convergence.taskResultIdleIntervalMillis(),
                ResultConvergenceConfig.TASK_SUCCESS_TARGET_CONCURRENCY,
                ResultConvergenceConfig.TASK_SUCCESS_MAX_CONCURRENCY,
                limit -> taskResults.consumeTaskResults(
                        TaskResultClass.SUCCESS,
                        limit
                ),
                taskPolicy::handleSuccess
        ));
        lanes.add(new ResultLane(
                ResultLaneId.TASK_FAILURE,
                ResultConvergenceConfig.TASK_RESULT_BATCH_LIMIT,
                convergence.taskResultIdleIntervalMillis(),
                ResultConvergenceConfig.TASK_FAILURE_TARGET_CONCURRENCY,
                ResultConvergenceConfig.TASK_FAILURE_MAX_CONCURRENCY,
                limit -> taskResults.consumeTaskResults(
                        TaskResultClass.FAILURE,
                        limit
                ),
                taskPolicy::handleFailure
        ));
        if (serviceabilityEnabled(preset)) {
            WorkerServiceabilityResultConfig serviceabilityConfig =
                    serviceabilityConfigForPreset(preset);
            WorkerServiceabilityResultPolicy evidencePolicy =
                    new WorkerServiceabilityResultPolicy(
                            workerServiceabilityEvents,
                            serviceabilityConfig,
                            hotEligibilityFloorMillis
                    );
            lanes.add(new ResultLane(
                    ResultLaneId.ADAPTER_EVIDENCE,
                    serviceabilityConfig.resultReportLimit(),
                    convergence.adapterEvidenceIdleIntervalMillis(),
                    ResultConvergenceConfig
                            .ADAPTER_EVIDENCE_TARGET_CONCURRENCY,
                    ResultConvergenceConfig
                            .ADAPTER_EVIDENCE_MAX_CONCURRENCY,
                    serviceability::consumeAdapterEvidenceResults,
                    evidencePolicy::handle
            ));
        } else if (hotEligibilityFloorMillis != 0) {
            throw new IllegalArgumentException(
                    "disabled Serviceability must not carry a HOT floor"
            );
        }
        return new ResultConvergenceRuntime(
                new ResultConvergenceApplication(
                        lanes,
                        ResultConvergenceConfig.GLOBAL_MAX_CONCURRENCY
                )
        );
    }

    public void start() {
        application.start();
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

    static ResultConvergenceConfig configForPreset(PolicyPreset preset) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case DEFAULT, SERVICEABILITY_DEFAULT ->
                    ResultConvergenceConfig.defaults();
            case SCENARIO_LAB -> new ResultConvergenceConfig(
                    LAB_INTERVAL_MILLIS,
                    ResultConvergenceConfig.DEFAULT_IDLE_INTERVAL_MILLIS
            );
            case RUNTIME_BOUNDARY_PROOF -> new ResultConvergenceConfig(
                    ResultConvergenceConfig.DEFAULT_IDLE_INTERVAL_MILLIS,
                    LAB_INTERVAL_MILLIS
            );
        };
    }

    static WorkerServiceabilityResultConfig serviceabilityConfigForPreset(
            PolicyPreset preset
    ) {
        return switch (Objects.requireNonNull(preset, "preset")) {
            case DEFAULT, SERVICEABILITY_DEFAULT, SCENARIO_LAB ->
                    WorkerServiceabilityResultConfig.defaults();
            case RUNTIME_BOUNDARY_PROOF ->
                    new WorkerServiceabilityResultConfig(
                            WorkerServiceabilityResultConfig
                                    .DEFAULT_MAX_RECOVERY_ATTEMPTS,
                            BOUNDARY_RESULT_REPORT_LIMIT,
                            WorkerServiceabilityResultConfig
                                    .DEFAULT_EVIDENCE_MAX_AGE_MILLIS
                    );
        };
    }

    private static boolean serviceabilityEnabled(PolicyPreset preset) {
        return preset != PolicyPreset.DEFAULT;
    }
}
