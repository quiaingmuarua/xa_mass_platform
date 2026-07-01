package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.engine.assignment.DefaultAssignmentAllocationPolicy;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.DispatchCadence;
import com.xa.mass.engine.runtime.scheduling.SchedulingPlaneResolver;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.strategy.DefaultSchedulingPlaneResolver;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.runtime.TaskRuntimeRetryPolicyResolver;
import com.xa.mass.engine.watchdog.LeaseExpireWatchdog;
import com.xa.mass.engine.watchdog.RuntimeReadyDispatchPump;
import com.xa.mass.engine.watchdog.WorkerCommandMaintenanceWatchdog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Owns default engine runtime assembly and lifecycle wiring.
 *
 * <p>This is not a pass-through wrapper. It keeps the listener, watchdog,
 * matching, and dispatch binder implementation graph inside the engine module
 * while the SDK remains the embedding shell.</p>
 */
public class EngineRuntimeKernel {

    private static final Logger logger = LoggerFactory.getLogger(EngineRuntimeKernel.class);
    private static final int STARTUP_READY_TASK_SCAN_LIMIT =
            Integer.getInteger("xa.mass.engine.startupReadyTaskScanLimit", 10_000);

    private final EngineRuntimeKernelConfig config;

    private TaskCommandService taskCommands;
    private TaskRuntimeRecoveryPort runtimeRecoveryPort;
    private TaskLeaseMaintenancePort leaseMaintenancePort;
    private TaskDispatchWakeupPort dispatchWakeupPort;
    private TaskShellLifecycleMaintenancePort shellLifecycleMaintenancePort;
    private TaskEventListenerRegistrar eventListeners;
    private TaskEventService taskEvents;
    private TaskAssignWorker assignWorker;
    private LeaseExpireWatchdog leaseWatchdog;
    private WorkerCommandMaintenanceWatchdog workerCommandMaintenanceWatchdog;
    private RuntimeReadyDispatchPump runtimeReadyDispatchPump;
    private TaskResourceReleaseListener resourceReleaseListener;
    private List<EngineRuntimeLoop> taskRuntimeLoops = List.of();
    private Consumer<Task> taskReadyListener;
    private Consumer<Task> taskDispatchSignalListener;
    private Consumer<Task> taskTerminalListener;
    private TaskWorkAttemptClosedListener taskWorkAttemptClosedListener;
    private final SchedulingPlaneResolver schedulingPlaneResolver = new DefaultSchedulingPlaneResolver();
    private boolean running;

    public EngineRuntimeKernel(EngineRuntimeKernelConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public StartedRuntime start(TaskDispatchBatchListener dispatchBatchListener) {
        LogUtils.clearMdc();
        if (running) {
            logger.info("EngineRuntimeKernel is already running, skipping duplicate start");
            return startedRuntime();
        }
        try {
            taskCommands = config.getTaskCommandService();
            runtimeRecoveryPort = config.getTaskRuntimeRecoveryPort();
            leaseMaintenancePort = config.getTaskLeaseMaintenancePort();
            dispatchWakeupPort = config.getTaskDispatchWakeupPort();
            shellLifecycleMaintenancePort = config.getTaskShellLifecycleMaintenancePort();
            TaskAssignmentRuntimePort assignmentRuntimePort = config.getTaskAssignmentRuntimePort();
            taskEvents = config.getTaskEventService();
            eventListeners = taskEvents;
            var workerAvailabilityWakeupRuntime = config.getWorkerAvailabilityWakeupRuntime();
            var workerSelectionRuntime = config.getWorkerSelectionRuntime();
            AssignmentDiagnosticRecorder recordService = config.getRecordService();
            TraceEventLogger traceEventLogger = config.getTraceEventLogger();
            var resourcePolicy = new DefaultWorkerDispatchResourcePolicy(schedulingPlaneResolver);
            var resourceReleaser = new WorkerDispatchResourceReleaser(
                    workerSelectionRuntime,
                    resourcePolicy,
                    traceEventLogger
            );
            var dispatchBinder = new SimpleTaskDispatchBinder(
                    assignmentRuntimePort,
                    workerSelectionRuntime,
                    recordService,
                    dispatchBatchListener,
                    traceEventLogger,
                    resourcePolicy,
                    resourceReleaser,
                    schedulingPlaneResolver,
                    config::getTaskMessageLeaseSeconds);
            var workerAssignListener = new TaskWorkerAssignListener(
                    workerSelectionRuntime,
                    dispatchBinder,
                    assignmentRuntimePort,
                    taskEvents,
                    traceEventLogger,
                    recordService,
                    new DefaultAssignmentAllocationPolicy(null, schedulingPlaneResolver),
                    resourcePolicy,
                    resourceReleaser,
                    schedulingPlaneResolver);
            assignWorker = new TaskAssignWorker(
                    workerAssignListener,
                    config.getAssignmentRetryDelayMillis(),
                    schedulingPlaneResolver,
                    traceEventLogger);
            assignWorker.start();
            runtimeReadyDispatchPump = new RuntimeReadyDispatchPump(
                    runtimeRecoveryPort,
                    workerAssignListener::onTaskAssign,
                    config.getRuntimeReadyDispatchIntervalMillis(),
                    STARTUP_READY_TASK_SCAN_LIMIT,
                    config.getAssignmentRetryDelayMillis(),
                    config.getRuntimeReadyDispatchIdleBackoffMaxMillis(),
                    config.getRuntimeReadyDispatchIdleBackoffPolicy(),
                    schedulingPlaneResolver
            );
            TaskDispatchWakeupBridge dispatchWakeupBridge =
                    new TaskDispatchWakeupBridge(assignWorker, runtimeReadyDispatchPump);
            Runnable dispatchWakeupCallback = dispatchWakeupBridge.callback("worker availability changed");
            config.getWorkerControlRuntime().setDispatchWakeupCallback(dispatchWakeupCallback);
            workerAvailabilityWakeupRuntime.setDispatchWakeupCallback(dispatchWakeupCallback);

            resourceReleaseListener = new TaskResourceReleaseListener(
                    leaseMaintenancePort,
                    dispatchWakeupPort,
                    workerSelectionRuntime,
                    traceEventLogger,
                    null,
                    resourcePolicy,
                    resourceReleaser);
            taskReadyListener = task -> {
                if (usesSignalDrivenDelayedDispatch(task)) {
                    assignWorker.submit(task);
                }
            };
            taskDispatchSignalListener = task -> {
                if (usesSignalDrivenDelayedDispatch(task)) {
                    assignWorker.submit(task);
                }
            };
            taskWorkAttemptClosedListener = resourceReleaseListener::onTaskWorkAttemptClosed;
            taskTerminalListener = resourceReleaseListener::onTaskTerminal;
            eventListeners.addTaskReadyListener(taskReadyListener);
            eventListeners.addTaskDispatchListener(taskDispatchSignalListener);
            eventListeners.addTaskWorkAttemptClosedListener(taskWorkAttemptClosedListener);
            eventListeners.addTaskTerminalListener(taskTerminalListener);
            recoverRuntimeReadyTasks();

            leaseWatchdog = new LeaseExpireWatchdog(
                    leaseMaintenancePort,
                    shellLifecycleMaintenancePort,
                    config.getLeaseWatchdogIntervalSeconds());
            taskRuntimeLoops = List.of(runtimeReadyDispatchPump, leaseWatchdog);
            workerCommandMaintenanceWatchdog = new WorkerCommandMaintenanceWatchdog(
                    config.getWorkerControlRuntime(),
                    config.getWorkerCommandMaintenanceIntervalSeconds(),
                    config.getWorkerCommandMaintenanceScanLimit(),
                    config.getWorkerCommandDeliveryMaxAttempts()
            );
            workerCommandMaintenanceWatchdog.start();
            running = true;
            return startedRuntime(dispatchWakeupCallback);
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Failed to start engine runtime kernel", e);
            stop();
            throw new RuntimeException("Failed to start engine runtime kernel", e);
        }
    }

    public void stop() {
        LogUtils.clearMdc();
        if (!running && assignWorker == null && runtimeReadyDispatchPump == null && leaseWatchdog == null) {
            logger.info("EngineRuntimeKernel is not running, skipping stop");
            return;
        }
        try {
            if (eventListeners != null) {
                if (taskReadyListener != null) {
                    eventListeners.removeTaskReadyListener(taskReadyListener);
                }
                if (taskDispatchSignalListener != null) {
                    eventListeners.removeTaskDispatchListener(taskDispatchSignalListener);
                }
                if (taskWorkAttemptClosedListener != null) {
                    eventListeners.removeTaskWorkAttemptClosedListener(taskWorkAttemptClosedListener);
                }
                if (taskTerminalListener != null) {
                    eventListeners.removeTaskTerminalListener(taskTerminalListener);
                }
            }
            config.getWorkerControlRuntime().setDispatchWakeupCallback(null);
            config.getWorkerAvailabilityWakeupRuntime().setDispatchWakeupCallback(null);
            leaseWatchdog = null;
            taskRuntimeLoops = List.of();
            if (workerCommandMaintenanceWatchdog != null) {
                workerCommandMaintenanceWatchdog.stop();
                workerCommandMaintenanceWatchdog = null;
            }
            if (runtimeReadyDispatchPump != null) {
                runtimeReadyDispatchPump.stop();
                runtimeReadyDispatchPump = null;
            }
            if (assignWorker != null) {
                assignWorker.stop();
                assignWorker = null;
            }
            resourceReleaseListener = null;
            taskReadyListener = null;
            taskDispatchSignalListener = null;
            taskWorkAttemptClosedListener = null;
            taskTerminalListener = null;
            taskCommands = null;
            runtimeRecoveryPort = null;
            leaseMaintenancePort = null;
            dispatchWakeupPort = null;
            shellLifecycleMaintenancePort = null;
            eventListeners = null;
            taskEvents = null;
            running = false;
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Error stopping engine runtime kernel", e);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public TaskCommandService taskCommands() {
        return taskCommands;
    }

    private void recoverRuntimeReadyTasks() {
        if (runtimeRecoveryPort == null) {
            return;
        }
        for (Task task : runtimeRecoveryPort.getRuntimeDispatchableTasks(STARTUP_READY_TASK_SCAN_LIMIT)) {
            TaskStatus status = task.getStatus();
            if ((status == TaskStatus.READY || status == TaskStatus.RUNNING)
                    && usesSignalDrivenDelayedDispatch(task)) {
                assignWorker.submit(task);
            }
        }
    }

    private boolean usesSignalDrivenDelayedDispatch(Task task) {
        return task != null
                && schedulingPlaneResolver.resolve(task).taskSchedulingPolicy().dispatchCadence()
                == DispatchCadence.SIGNAL_DRIVEN_DELAYED;
    }

    private StartedRuntime startedRuntime() {
        return new StartedRuntime(
                eventListeners,
                null,
                taskRuntimeLoops
        );
    }

    private StartedRuntime startedRuntime(Runnable dispatchWakeupCallback) {
        return new StartedRuntime(
                eventListeners,
                dispatchWakeupCallback,
                taskRuntimeLoops
        );
    }

    public record StartedRuntime(TaskEventListenerRegistrar eventListeners,
                                 Runnable dispatchWakeupCallback,
                                 List<EngineRuntimeLoop> taskRuntimeLoops) {
        public StartedRuntime {
            taskRuntimeLoops = List.copyOf(taskRuntimeLoops == null ? List.of() : taskRuntimeLoops);
        }
    }
}
