package com.xa.mass.engine.command;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCommandLifecycleOwnerTest {

    @Test
    void recordsCommandRequestAndExposesReadView() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();

        WorkerCommandLifecycleResult result = owner.requestCommand(request("cmd-1", "worker-1", "RESTART"));

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, result.code());
        assertEquals(WorkerCommandStatus.REQUESTED, result.currentStatus());
        assertEquals("cmd-1", owner.command("cmd-1").orElseThrow().commandId());
        assertEquals("worker-1", owner.command("cmd-1").orElseThrow().workerId());
        assertEquals("RESTART", owner.command("cmd-1").orElseThrow().commandType());
        assertEquals(1, owner.commandsForWorker("worker-1").size());
    }

    @Test
    void duplicateSameRequestIsIdempotentButDifferentPayloadConflicts() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        WorkerCommandRequest request = request("cmd-1", "worker-1", "RESTART");

        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, owner.requestCommand(request).code());
        assertEquals(WorkerCommandLifecycleResultCode.IDEMPOTENT, owner.requestCommand(request).code());

        WorkerCommandRequest conflicting = WorkerCommandRequest.builder("cmd-1", "worker-1", "DRAIN")
                .idempotencyKey("idem-1")
                .payload(Map.of("mode", "fast"))
                .build();
        assertEquals(WorkerCommandLifecycleResultCode.CONFLICT, owner.requestCommand(conflicting).code());
        assertEquals("RESTART", owner.command("cmd-1").orElseThrow().commandType());
    }

    @Test
    void ownsCommandStatusTransitionsWithoutTaskResultRuntime() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();
        owner.requestCommand(request("cmd-1", "worker-1", "RESTART"));

        WorkerCommandLifecycleResult delivered = owner.transition(
                "cmd-1",
                WorkerCommandStatus.DELIVERY_ACCEPTED,
                "delivery accepted"
        );
        assertEquals(WorkerCommandLifecycleResultCode.ACCEPTED, delivered.code());
        assertEquals(WorkerCommandStatus.REQUESTED, delivered.previousStatus());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, delivered.currentStatus());

        WorkerCommandLifecycleResult terminal = owner.transition(
                "cmd-1",
                WorkerCommandStatus.SUCCEEDED,
                "worker command succeeded"
        );
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
    void missingCommandTransitionIsNotFound() {
        WorkerCommandLifecycleOwner owner = new WorkerCommandLifecycleOwner();

        WorkerCommandLifecycleResult result = owner.transition(
                "missing",
                WorkerCommandStatus.SUCCEEDED,
                "missing"
        );

        assertEquals(WorkerCommandLifecycleResultCode.NOT_FOUND, result.code());
        assertTrue(owner.command("missing").isEmpty());
    }

    private static WorkerCommandRequest request(String commandId, String workerId, String commandType) {
        return WorkerCommandRequest.builder(commandId, workerId, commandType)
                .requester("operator-a")
                .reason("test")
                .idempotencyKey("idem-1")
                .deadlineEpochMillis(1_779_000_000_000L)
                .payload(Map.of("mode", "safe"))
                .build();
    }
}
