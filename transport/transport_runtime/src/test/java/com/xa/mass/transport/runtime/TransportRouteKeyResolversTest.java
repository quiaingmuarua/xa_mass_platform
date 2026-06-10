package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.CanonicalWorkerRouteKeyCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportRouteKeyResolversTest {

    @Test
    void canonicalWorkerSubjectResolverUsesWorkerGroupAndWorkerEvidence() {
        TaskDispatchBinding binding = binding("phone-device-probe", "poll-sg-002");
        TransportDispatchRouteContext context = TransportDispatchRouteContext.from(context(), binding);

        String routeKey = TransportRouteKeyResolvers.canonicalWorkerSubject()
                .resolveRouteKey(binding, context);

        assertEquals(
                CanonicalWorkerRouteKeyCodec.encode("phone-device-probe", "poll-sg-002"),
                routeKey
        );
    }

    @Test
    void canonicalWorkerSubjectResolverRejectsBindingsWithoutWorkerGroupEvidence() {
        TaskDispatchBinding binding = TaskDispatchBinding.workerLevel(
                "task-1",
                "msg-1",
                "event.probe",
                Map.of(),
                null,
                0,
                "attempt-1",
                1,
                "lease-1",
                "poll-sg-002",
                "batch-1"
        );
        TransportDispatchRouteContext context = TransportDispatchRouteContext.from(context(), binding);

        assertThrows(IllegalArgumentException.class,
                () -> TransportRouteKeyResolvers.canonicalWorkerSubject()
                        .resolveRouteKey(binding, context));
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext(
                "task-1",
                "task-name",
                "project",
                "user-1",
                "event.probe",
                Map.of()
        );
    }

    private static TaskDispatchBinding binding(String workerGroupId, String workerId) {
        return TaskDispatchBinding.workerLevelWithEvidence(
                "task-1",
                "msg-1",
                "event.probe",
                Map.of(),
                null,
                0,
                "attempt-1",
                1,
                "lease-1",
                workerId,
                "batch-1",
                workerGroupId,
                "node-1",
                "event-binding",
                "candidate-source"
        );
    }
}
