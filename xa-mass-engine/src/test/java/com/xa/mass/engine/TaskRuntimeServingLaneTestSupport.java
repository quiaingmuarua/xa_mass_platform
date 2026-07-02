package com.xa.mass.engine;

import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeResultWindowReadModel;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;

import java.util.function.LongSupplier;

public final class TaskRuntimeServingLaneTestSupport {

    private TaskRuntimeServingLaneTestSupport() {
    }

    public static TaskRuntimeServingLane forTaskManager(TaskRuntimeWorkPort workPort,
                                                       TaskRuntimeScorePort scorePort,
                                                       TaskRuntimeConvergencePort convergencePort,
                                                       TaskRuntimeReadPort readPort,
                                                       TaskRuntimeResultWindowReadModel resultWindowReadModel,
                                                       TaskManager taskManager,
                                                       long workLeaseSeconds,
                                                       int maxAppendBatchSize,
                                                       long finalResultRetentionMillis) {
        return forTaskManager(
                workPort,
                scorePort,
                convergencePort,
                readPort,
                resultWindowReadModel,
                taskManager,
                new ContractAwareTaskTerminalPolicy(),
                new DefaultSchedulingPlaneResolver(),
                TraceEventLogger.noop(),
                workLeaseSeconds,
                maxAppendBatchSize,
                finalResultRetentionMillis,
                System::currentTimeMillis);
    }

    public static TaskRuntimeServingLane forTaskManager(TaskRuntimeWorkPort workPort,
                                                       TaskRuntimeScorePort scorePort,
                                                       TaskRuntimeConvergencePort convergencePort,
                                                       TaskRuntimeReadPort readPort,
                                                       TaskRuntimeResultWindowReadModel resultWindowReadModel,
                                                       TaskManager taskManager,
                                                       TaskTerminalPolicy terminalPolicy,
                                                       SchedulingPlaneResolver schedulingPlaneResolver,
                                                       TraceEventLogger traceEventLogger,
                                                       long workLeaseSeconds,
                                                       int maxAppendBatchSize,
                                                       long finalResultRetentionMillis) {
        return forTaskManager(
                workPort,
                scorePort,
                convergencePort,
                readPort,
                resultWindowReadModel,
                taskManager,
                terminalPolicy,
                schedulingPlaneResolver,
                traceEventLogger,
                workLeaseSeconds,
                maxAppendBatchSize,
                finalResultRetentionMillis,
                System::currentTimeMillis);
    }

    public static TaskRuntimeServingLane forTaskManager(TaskRuntimeWorkPort workPort,
                                                       TaskRuntimeScorePort scorePort,
                                                       TaskRuntimeConvergencePort convergencePort,
                                                       TaskRuntimeReadPort readPort,
                                                       TaskRuntimeResultWindowReadModel resultWindowReadModel,
                                                       TaskManager taskManager,
                                                       TaskTerminalPolicy terminalPolicy,
                                                       SchedulingPlaneResolver schedulingPlaneResolver,
                                                       TraceEventLogger traceEventLogger,
                                                       long workLeaseSeconds,
                                                       int maxAppendBatchSize,
                                                       long finalResultRetentionMillis,
                                                       LongSupplier clock) {
        return TaskRuntimeServingLane.forShellHooks(
                workPort,
                scorePort,
                convergencePort,
                readPort,
                resultWindowReadModel,
                taskManager::getTask,
                taskManager::persistTaskShell,
                taskManager::publishTaskDispatchRequested,
                taskManager::publishTaskTerminal,
                taskManager::publishTaskWorkAttemptClosed,
                taskManager::publishTaskWorkLogicallyFinal,
                terminalPolicy,
                schedulingPlaneResolver,
                traceEventLogger,
                workLeaseSeconds,
                maxAppendBatchSize,
                finalResultRetentionMillis,
                clock);
    }
}
