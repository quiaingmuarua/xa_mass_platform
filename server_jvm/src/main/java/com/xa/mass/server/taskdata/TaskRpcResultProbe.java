package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.server.error.ServerErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class TaskRpcResultProbe implements SmartLifecycle {

    private static final System.Logger LOGGER =
            System.getLogger(TaskRpcResultProbe.class.getName());

    private final TaskRuntime taskRuntime;
    private final TaskRpcWaitRegistry registry;
    private final int maxProbeItemsPerRound;
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
        this.maxProbeItemsPerRound =
                properties.maxProbeItemsPerRound();
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
            try {
                probe(registry.takeDueBatch(maxProbeItemsPerRound));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void probe(List<TaskRpcWaitRegistry.ProbeRequest> requests) {
        Map<String, List<TaskRpcWaitRegistry.ProbeRequest>> byTask =
                new LinkedHashMap<>();
        for (TaskRpcWaitRegistry.ProbeRequest request : requests) {
            byTask.computeIfAbsent(
                    request.taskId(),
                    ignored -> new ArrayList<>()
            ).add(request);
        }
        byTask.forEach(this::probeTask);
    }

    private void probeTask(
            String taskId,
            List<TaskRpcWaitRegistry.ProbeRequest> requests
    ) {
        try {
            List<String> messageIds = requests.stream()
                    .map(TaskRpcWaitRegistry.ProbeRequest::messageId)
                    .toList();
            Map<String, String> results =
                    taskRuntime.loadTaskItemSuccessResults(
                            taskId,
                            messageIds
                    );
            for (TaskRpcWaitRegistry.ProbeRequest request : requests) {
                String payload = results.get(request.messageId());
                if (payload != null) {
                    registry.completeSuccess(
                            taskId,
                            request.messageId(),
                            payload
                    );
                }
                registry.finishProbe(taskId, request.messageId(), 0);
            }
        } catch (RuntimeException error) {
            requests.forEach(request -> registry.finishProbe(
                    taskId,
                    request.messageId(),
                    failureRetryMillis
            ));
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0} operation=taskRpc.probeResults"
                            + " taskId={1} itemCount={2}",
                    ServerErrorCode.TASK_DATA_UNAVAILABLE.code(),
                    taskId,
                    requests.size()
            );
        }
    }
}
