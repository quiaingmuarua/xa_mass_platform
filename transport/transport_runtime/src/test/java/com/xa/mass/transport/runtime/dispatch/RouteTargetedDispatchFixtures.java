package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.WorkerTransportHints;

import java.util.List;
import java.util.Map;

public final class RouteTargetedDispatchFixtures {

    private RouteTargetedDispatchFixtures() {
    }

    public static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "demo.event", Map.of());
    }

    public static TaskDispatchBinding binding(String messageId, String workerId) {
        return TaskDispatchBinding.workerLevelWithTransportEvidence(
                "task-1",
                messageId,
                "demo.event",
                Map.of(),
                null,
                0,
                "attempt-" + messageId,
                1,
                "lease-" + messageId,
                workerId,
                "batch-1",
                "group-1",
                null,
                "websocket",
                WorkerTransportHints.REALTIME,
                null,
                "test-fixture"
        );
    }

    public static RouteTargetedTaskDispatchBinding delivery(String messageId, String workerId) {
        return new RouteTargetedTaskDispatchBinding("route-1", "websocket", binding(messageId, workerId));
    }

    public static RouteTargetedTaskDispatchBatch batch(String routeKey,
                                                       String targetTransportNodeId,
                                                       RouteTargetedTaskDispatchBinding... deliveries) {
        return new RouteTargetedTaskDispatchBatch(
                context(),
                routeKey,
                targetTransportNodeId,
                List.of(deliveries)
        );
    }

    public static List<String> messages(RouteTargetedTaskDispatchBatch batch) {
        return batch.deliveryBindings().stream()
                .map(RouteTargetedTaskDispatchBinding::dispatchBinding)
                .map(TaskDispatchBinding::messageId)
                .toList();
    }
}
