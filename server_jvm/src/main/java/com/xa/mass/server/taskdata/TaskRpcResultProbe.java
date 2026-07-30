package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.List;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class TaskRpcResultProbe implements SmartLifecycle {

    private static final System.Logger LOGGER =
            System.getLogger(TaskRpcResultProbe.class.getName());

    private final TaskRuntime taskRuntime;
    private final TaskRpcWaitRegistry registry;
    private final long failureRetryMillis;
    private volatile boolean running;
    private volatile Thread probeThread;

    public TaskRpcResultProbe(
            TaskRuntime taskRuntime,
            TaskRpcWaitRegistry registry,
            TaskRpcProperties properties
    ) {
        this.taskRuntime = taskRuntime;
        this.registry = registry;
        this.failureRetryMillis = properties.longProbeIntervalMillis();
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        probeThread = Thread.ofVirtual()
                .name("task-rpc-result-probe")
                .start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        registry.shutdown();
        Thread thread = probeThread;
        probeThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            TaskRpcWaitRegistry.ProbeRequest request = null;
            try {
                request = registry.takeDue();
                String payload = taskRuntime.loadTaskItemSuccessResults(
                        request.taskId(),
                        List.of(request.messageId())
                ).get(request.messageId());
                if (payload != null) {
                    registry.completeSuccess(
                            request.taskId(),
                            request.messageId(),
                            payload
                    );
                }
                registry.finishProbe(
                        request.taskId(),
                        request.messageId(),
                        0
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException error) {
                if (request != null) {
                    registry.finishProbe(
                            request.taskId(),
                            request.messageId(),
                            failureRetryMillis
                    );
                }
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "{0} operation=taskRpc.probeResult"
                                + " taskId={1} messageId={2}",
                        ServerErrorCode.TASK_DATA_UNAVAILABLE.code(),
                        request == null ? "unavailable" : request.taskId(),
                        request == null
                                ? "unavailable"
                                : request.messageId()
                );
            }
        }
    }
}
