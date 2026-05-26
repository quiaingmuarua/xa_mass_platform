package com.xa.mass.engine.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCommandLifecycleOwnerTest {

    @Test
    void recordsCommandRequestAndExposesReadView() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();

        WorkerCommandLifecycleResult result = owner.requestCommand(request("cmd-1", "worker-1", "PING"));

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, result.code());
        assertEquals(WorkerCommandStatus.REQUESTED, result.currentStatus());
        assertEquals("cmd-1", owner.command("cmd-1").orElseThrow().commandId());
        assertEquals("worker-1", owner.command("cmd-1").orElseThrow().workerId());
        assertEquals("PING", owner.command("cmd-1").orElseThrow().commandType());
        assertEquals(1, owner.commandsForWorker("worker-1").size());
    }

    @Test
    void rejectsUnknownCommandTypeBeforeRecordingTruth() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();

        WorkerCommandLifecycleResult result = owner.requestCommand(request("cmd-1", "worker-1", "RESTART"));

        assertEquals(WorkerCommandLifecycleResultCode.REJECTED, result.code());
        assertTrue(owner.command("cmd-1").isEmpty());
    }

    @Test
    void duplicateSameRequestIsIdempotentButDifferentPayloadConflicts() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        WorkerCommandRequest request = request("cmd-1", "worker-1", "PING");

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, owner.requestCommand(request).code());
        assertEquals(WorkerCommandLifecycleResultCode.IDEMPOTENT, owner.requestCommand(request).code());

        WorkerCommandRequest conflicting = WorkerCommandRequest.builder("cmd-1", "worker-1", "DRAIN")
                .idempotencyKey("idem-1")
                .payload(Map.of("mode", "fast"))
                .build();
        assertEquals(WorkerCommandLifecycleResultCode.CONFLICT, owner.requestCommand(conflicting).code());
        assertEquals("PING", owner.command("cmd-1").orElseThrow().commandType());
    }

    @Test
    void ownsCommandStatusTransitionsWithoutTaskResultConvergence() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "PING"));

        WorkerCommandLifecycleResult delivered = owner.markDeliveryAccepted("cmd-1", "delivery accepted");
        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, delivered.code());
        assertEquals(WorkerCommandStatus.REQUESTED, delivered.previousStatus());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, delivered.currentStatus());

        WorkerCommandLifecycleResult terminal = owner.markSucceeded("cmd-1", "worker command succeeded");
        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, terminal.code());
        assertEquals(WorkerCommandStatus.SUCCEEDED, owner.command("cmd-1").orElseThrow().status());

        WorkerCommandLifecycleResult lateFailure = owner.transition(
                "cmd-1",
                WorkerCommandStatus.FAILED,
                "late failure"
        );
        assertEquals(WorkerCommandLifecycleResultCode.INVALID_TRANSITION, lateFailure.code());
        assertEquals(WorkerCommandStatus.SUCCEEDED, owner.command("cmd-1").orElseThrow().status());
    }

    @Test
    void appliesOwnerLevelAcknowledgementsWithoutTaskResultRows() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "PING"));

        WorkerCommandLifecycleResult delivered = owner.applyAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-1", "delivery ack"));
        WorkerCommandLifecycleResult executionAccepted = owner.applyAcknowledgement(
                WorkerCommandAcknowledgement.executionAccepted("cmd-1", "execution ack"));
        WorkerCommandLifecycleResult failed = owner.applyAcknowledgement(
                WorkerCommandAcknowledgement.failed("cmd-1", "worker rejected command"));

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, delivered.code());
        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, executionAccepted.code());
        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, failed.code());
        assertEquals(WorkerCommandStatus.FAILED, owner.command("cmd-1").orElseThrow().status());
        assertEquals("worker rejected command", owner.command("cmd-1").orElseThrow().statusReason());
    }

    @Test
    void expiresDueNonTerminalCommandsWithBoundedScan() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "PING", 1_000L));
        owner.requestCommand(request("cmd-2", "worker-1", "DRAIN", 1_001L));
        owner.requestCommand(request("cmd-3", "worker-1", "PING", 10_000L));

        List<WorkerCommandLifecycleResult> firstScan = owner.expireDueCommands(Instant.ofEpochMilli(2_000L), 1);
        assertEquals(1, firstScan.size());
        assertEquals(WorkerCommandStatus.EXPIRED, firstScan.getFirst().currentStatus());

        List<WorkerCommandLifecycleResult> secondScan = owner.expireDueCommands(Instant.ofEpochMilli(2_000L), 10);
        assertEquals(1, secondScan.size());
        assertEquals("cmd-2", secondScan.getFirst().record().commandId());
        assertEquals(WorkerCommandStatus.EXPIRED, owner.command("cmd-2").orElseThrow().status());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-3").orElseThrow().status());
    }

    @Test
    void claimsPendingCommandsForWorkerByMovingThemToDeliveryAccepted() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "PING"));
        owner.requestCommand(request("cmd-2", "worker-1", "DRAIN"));
        owner.requestCommand(request("cmd-3", "worker-2", "PING"));

        List<WorkerCommandLifecycleResult> claimed =
                owner.claimPendingCommandsForWorker("worker-1", 1, "pulled");

        assertEquals(1, claimed.size());
        assertEquals("cmd-1", claimed.getFirst().record().commandId());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, claimed.getFirst().currentStatus());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-2").orElseThrow().status());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-3").orElseThrow().status());
    }

    @Test
    void claimPendingCommandsUsesRequestedIntersectionAndSkipsHistoricalTerminalCommands() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-terminal-1", "worker-1", "PING"));
        owner.requestCommand(request("cmd-terminal-2", "worker-1", "PING"));
        owner.markDeliveryAccepted("cmd-terminal-1", "delivered");
        owner.markSucceeded("cmd-terminal-1", "already done");
        owner.markFailed("cmd-terminal-2", "already closed");
        owner.requestCommand(request("cmd-requested", "worker-1", "DRAIN"));
        owner.requestCommand(request("cmd-other-worker", "worker-2", "DRAIN"));

        List<WorkerCommandLifecycleResult> claimed =
                owner.claimPendingCommandsForWorker("worker-1", 10, "pulled");

        assertEquals(1, claimed.size());
        assertEquals("cmd-requested", claimed.getFirst().record().commandId());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, owner.command("cmd-requested").orElseThrow().status());
        assertEquals(WorkerCommandStatus.REQUESTED, owner.command("cmd-other-worker").orElseThrow().status());
    }

    @Test
    void missingCommandTransitionIsNotFound() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();

        WorkerCommandLifecycleResult result = owner.markSucceeded("missing", "missing");

        assertEquals(WorkerCommandLifecycleResultCode.NOT_FOUND, result.code());
        assertTrue(owner.command("missing").isEmpty());
    }

    private static WorkerCommandRequest request(String commandId, String workerId, String commandType) {
        return request(commandId, workerId, commandType, 1_779_000_000_000L);
    }

    private static WorkerCommandRequest request(String commandId,
                                                String workerId,
                                                String commandType,
                                                long deadlineEpochMillis) {
        return WorkerCommandRequest.builder(commandId, workerId, commandType)
                .requester("operator-a")
                .reason("test")
                .idempotencyKey("idem-1")
                .deadlineEpochMillis(deadlineEpochMillis)
                .payload(Map.of("mode", "safe"))
                .build();
    }
}
