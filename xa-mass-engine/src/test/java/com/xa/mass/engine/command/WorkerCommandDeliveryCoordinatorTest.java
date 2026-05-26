package com.xa.mass.engine.command;

import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCommandDeliveryCoordinatorTest {

    @Test
    void acceptedDeliveryMovesCommandToDeliveryAcceptedAndEmitsTrace() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        RecordingEventSink sink = new RecordingEventSink();
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
        assertTrue(sink.events().stream().anyMatch(event ->
                event.getEventType() == ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION
                        && "cmd-1".equals(event.getAttrs().get("commandId"))
                        && "DELIVERY_ACCEPTED".equals(event.getAttrs().get("commandStatus"))
                        && "ACCEPTED".equals(event.getAttrs().get("result"))));
    }

    @Test
    void unavailableDeliveryDefersCommandForRetryOrAnotherCarrier() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> WorkerCommandDeliveryResult.workerUnavailable("worker route unavailable"),
                TraceEventLogger.noop()
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("cmd-1");

        assertEquals(WorkerCommandLifecycleResultCode.DEFERRED, result.code());
        assertEquals(WorkerCommandStatus.REQUESTED, result.currentStatus());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-1").orElseThrow().status());
        assertEquals(1, owner.command("cmd-1").orElseThrow().deliveryAttemptCount());
        assertEquals("worker route unavailable", owner.command("cmd-1").orElseThrow().statusReason());
    }

    @Test
    void deferredDeliveryLeavesCommandRequestedForAnotherCarrier() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "PING"));
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> WorkerCommandDeliveryResult.deferred("no realtime carrier"),
                TraceEventLogger.noop()
        );

        WorkerCommandLifecycleResult result = coordinator.deliver("cmd-1");

        assertEquals(WorkerCommandLifecycleResultCode.DEFERRED, result.code());
        assertEquals(WorkerCommandStatus.REQUESTED, result.currentStatus());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-1").orElseThrow().status());
        assertEquals(1, owner.command("cmd-1").orElseThrow().deliveryAttemptCount());
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
    void concurrentDeliveryAttemptDoesNotCallPortTwiceForSameRequestedCommand() throws Exception {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        CountDownLatch firstDeliveryEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
        AtomicInteger deliveryCalls = new AtomicInteger();
        WorkerCommandDeliveryCoordinator coordinator = new WorkerCommandDeliveryCoordinator(
                owner,
                command -> {
                    deliveryCalls.incrementAndGet();
                    firstDeliveryEntered.countDown();
                    try {
                        assertTrue(releaseFirstDelivery.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted waiting to release delivery", e);
                    }
                    return WorkerCommandDeliveryResult.accepted("sent to worker command channel");
                },
                TraceEventLogger.noop()
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkerCommandLifecycleResult> first = executor.submit(() -> coordinator.deliver("cmd-1"));
            assertTrue(firstDeliveryEntered.await(5, TimeUnit.SECONDS));

            WorkerCommandLifecycleResult raced = coordinator.deliver("cmd-1");
            releaseFirstDelivery.countDown();
            WorkerCommandLifecycleResult accepted = first.get(5, TimeUnit.SECONDS);

            assertEquals(WorkerCommandLifecycleResultCode.IDEMPOTENT, raced.code());
            assertEquals(WorkerCommandStatus.REQUESTED, raced.currentStatus());
            assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, accepted.code());
            assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, accepted.currentStatus());
            assertEquals(1, deliveryCalls.get());
            assertEquals(1, owner.command("cmd-1").orElseThrow().deliveryAttemptCount());
        } finally {
            releaseFirstDelivery.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void terminalCommandDeliveryAttemptDoesNotMutateLifecycle() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "DRAIN"));
        owner.markFailed("cmd-1", "delivery closed");
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
        assertEquals(WorkerCommandStatus.FAILED, result.currentStatus());
        assertEquals(WorkerCommandStatus.FAILED, owner.command("cmd-1").orElseThrow().status());
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
}
