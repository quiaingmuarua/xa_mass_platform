package com.xa.mass.engine.command;

import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCommandDeliveryCoordinatorTest {

    @Test
    void acceptedDeliveryMovesCommandToDeliveryAcceptedAndEmitsTrace() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        RecordingSink sink = new RecordingSink();
        AtomicReference<WorkerCommandRecord> delivered = new AtomicReference<>();
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> {
                    delivered.set(command);
                    return WorkerCommandDeliveryResult.accepted("sent to worker command channel");
                },
                new TraceEventLogger(sink)
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("cmd-1");

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, result.code());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, result.currentStatus());
        assertEquals("cmd-1", delivered.get().commandId());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, owner.command("cmd-1").orElseThrow().status());
        assertTrue(sink.events.stream().anyMatch(event ->
                event.getEventType() == ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION
                        && "cmd-1".equals(event.getAttrs().get("commandId"))
                        && "DELIVERY_ACCEPTED".equals(event.getAttrs().get("commandStatus"))
                        && "ACCEPTED".equals(event.getAttrs().get("result"))));
    }

    @Test
    void failedDeliveryClosesCommandAsFailedBecauseRetryOwnerIsNotInThisSlice() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> WorkerCommandDeliveryResult.workerUnavailable("worker route unavailable"),
                TraceEventLogger.noop()
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("cmd-1");

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, result.code());
        assertEquals(WorkerCommandStatus.FAILED, result.currentStatus());
        assertEquals(WorkerCommandStatus.FAILED, owner.command("cmd-1").orElseThrow().status());
        assertEquals("worker route unavailable", owner.command("cmd-1").orElseThrow().statusReason());
    }

    @Test
    void deliveryPortExceptionClosesCommandAsFailed() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> {
                    throw new IllegalStateException("adapter unavailable");
                },
                TraceEventLogger.noop()
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("cmd-1");

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, result.code());
        assertEquals(WorkerCommandStatus.FAILED, result.currentStatus());
        assertEquals("command delivery failed: adapter unavailable",
                owner.command("cmd-1").orElseThrow().statusReason());
    }

    @Test
    void repeatedDeliveryDoesNotCallPortAfterDeliveryAlreadyAccepted() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        owner.markDeliveryAccepted("cmd-1", "already delivered");
        AtomicReference<WorkerCommandRecord> delivered = new AtomicReference<>();
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> {
                    delivered.set(command);
                    return WorkerCommandDeliveryResult.accepted("should not happen");
                },
                TraceEventLogger.noop()
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("cmd-1");

        assertEquals(WorkerCommandLifecycleResultCode.IDEMPOTENT, result.code());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, result.currentStatus());
        assertNull(delivered.get());
    }

    @Test
    void missingCommandDoesNotCallDeliveryPort() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        AtomicReference<WorkerCommandRecord> delivered = new AtomicReference<>();
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> {
                    delivered.set(command);
                    return WorkerCommandDeliveryResult.accepted("should not happen");
                },
                TraceEventLogger.noop()
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("missing");

        assertEquals(WorkerCommandLifecycleResultCode.NOT_FOUND, result.code());
        assertNull(delivered.get());
    }

    private static WorkerCommandRequest request(String commandId, String workerId, String commandType) {
        return WorkerCommandRequest.builder(commandId, workerId, commandType)
                .requester("operator-a")
                .reason("test")
                .idempotencyKey("idem-" + commandId)
                .deadlineEpochMillis(1_779_000_000_000L)
                .payload(Map.of("mode", "safe"))
                .build();
    }

    private static final class RecordingSink implements ExecutionEventSink {
        private final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public void emit(ExecutionEvent event) {
            events.add(event);
        }
    }
}
