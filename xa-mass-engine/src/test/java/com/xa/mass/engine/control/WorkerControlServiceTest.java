package com.xa.mass.engine.control;

import com.xa.mass.engine.InMemoryWorkerDeclarationRuntimeStore;

import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.worker.runtime.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandDeliveryCoordinator;
import com.xa.mass.worker.runtime.command.WorkerCommandDeliveryResult;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.worker.runtime.command.WorkerCommandRequest;
import com.xa.mass.worker.runtime.command.WorkerCommandStatus;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.testutil.WorkerTestFixture;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.worker.runtime.control.WorkerDispatchEligibilityRuntime;
import com.xa.mass.worker.runtime.report.WorkerStateProjection;
import com.xa.mass.worker.runtime.report.WorkerStateReport;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_COMMAND;
import static com.xa.mass.engine.testutil.WorkerRegistrationTestSupport.registerWorker;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerControlServiceTest {

    @Test
    void appliesOwnerBackedCommandCapabilityAndStateEntriesWithReadViews() {
        InMemoryWorkerRegistry workerRegistry = new InMemoryWorkerRegistry();
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), workerRegistry);
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-1");
        worker.setWorkerGroupId("group-1");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        registerWorker(workerManager, worker);
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerStateProjectionOwner stateOwner = new WorkerStateProjectionOwner();
        RecordingEventSink sink = new RecordingEventSink();
        WorkerControlService service = workerControlService(
                workerManager,
                commandOwner,
                stateOwner,
                new TraceEventLogger(sink));

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-1", "worker-1", "DRAIN")
                .requester("operator")
                .build()).success());
        assertTrue(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-1", "handoff accepted")).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
        assertTrue(service.applyWorkerCapabilityReport(WorkerCapabilityReport.builder("worker-1", 1)
                .availableEventCodes(List.of("crawler.fetch", "not.approved"))
                .schedulingAttributes(Map.of("country", "us"))
                .build()).success());
        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 1, "DRAINING")
                .reason("maintenance")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 2, "AVAILABLE")
                .reason("resumed")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 3, "DEGRADED")
                .reason("slow")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED,
                service.workerCommand("cmd-1").orElseThrow().status());
        assertEquals(1, service.workerCommandsForWorker("worker-1").size());
        assertEquals("DEGRADED", service.workerStateProjection("worker-1").orElseThrow().state());
        assertEquals(1, service.workerStateProjections().size());
        assertEquals("us", workerRegistry.slotByWorkerId("worker-1")
                .orElseThrow()
                .meta()
                .attributes()
                .get("country"));
        sink.assertHasEvent(ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION, "commandId", "cmd-1");
        assertTrue(sink.eventsOfType(ExecutionEventType.WORKER_CAPABILITY_REPORT_APPLIED).stream()
                .anyMatch(event -> "worker-1".equals(event.getIdentity().workerId())));
        assertTrue(sink.eventsOfType(ExecutionEventType.WORKER_STATE_REPORT_APPLIED).stream()
                .anyMatch(event -> "worker-1".equals(event.getIdentity().workerId())));
    }

    @Test
    void drainCommandFailureDoesNotReenableDispatchWithoutExplicitAvailableState() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-2");
        worker.setWorkerGroupId("group-2");
        registerWorker(workerManager, worker);
        WorkerControlService service = workerControlService(
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-2", "worker-2", "DRAIN")
                .requester("operator")
                .build()).success());
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-2", "accepted")).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.failed("cmd-2", "worker-side failure")).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-2", 1, "DEGRADED")
                .reason("still draining")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-2", 2, "AVAILABLE")
                .reason("resume")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(workerManager.clearWorkerDispatchDisable(
                "worker-2",
                WORKER_COMMAND,
                "command cleared"
        ));
        assertTrue(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void dispatchWakeupFiresOnlyForSchedulingRecoveryEvidence() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-wakeup");
        worker.setWorkerGroupId("group-wakeup");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        registerWorker(workerManager, worker);
        WorkerControlService service = workerControlService(
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());
        AtomicInteger wakeups = new AtomicInteger();
        service.setDispatchWakeupCallback(wakeups::incrementAndGet);

        assertTrue(service.applyWorkerCapabilityReport(WorkerCapabilityReport.builder("worker-wakeup", 1)
                .availableEventCodes(List.of("crawler.fetch"))
                .build()).success());
        assertEquals(1, wakeups.get());

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-wakeup", 1, "DRAINING")
                .reason("maintenance")
                .build()).success());
        assertEquals(1, wakeups.get());

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-wakeup", 2, "AVAILABLE")
                .reason("resumed")
                .build()).success());
        assertEquals(2, wakeups.get());

        assertFalse(service.applyWorkerCapabilityReport(WorkerCapabilityReport.builder("worker-wakeup", 0)
                .availableEventCodes(List.of("crawler.fetch"))
                .build()).success());
        assertEquals(2, wakeups.get());

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-wakeup", 3, "DEGRADED")
                .reason("slow")
                .build()).success());
        assertEquals(2, wakeups.get());
    }

    @Test
    void dispatchEligibilityRuntimeIsPluggable() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-3");
        worker.setWorkerGroupId("group-3");
        registerWorker(workerManager, worker);
        AtomicInteger stateApplications = new AtomicInteger();
        AtomicInteger commandApplications = new AtomicInteger();
        WorkerDispatchEligibilityRuntime eligibilityRuntime = new WorkerDispatchEligibilityRuntime() {
            @Override
            public boolean isWorkerDispatchEnabled(String workerId) {
                return workerManager.isWorkerDispatchEnabled(workerId);
            }

            @Override
            public void applyWorkerStateProjection(WorkerStateProjection projection) {
                stateApplications.incrementAndGet();
                workerManager.disableWorkerDispatch(
                        projection.workerId(),
                        WORKER_STATE,
                        projection.reason()
                );
            }

            @Override
            public void applyWorkerCommandLifecycleResult(com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult result) {
                commandApplications.incrementAndGet();
                workerManager.clearWorkerDispatchDisable(
                        result.record().workerId(),
                        WORKER_STATE,
                        result.record().statusReason()
                );
            }
        };
        WorkerControlService service = workerControlService(
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                eligibilityRuntime,
                TraceEventLogger.noop());

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-3", 1, "AVAILABLE")
                .reason("custom-policy-disable")
                .build()).success());
        assertEquals(1, stateApplications.get());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-3", "worker-3", "DRAIN")
                .requester("operator")
                .build()).success());
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-3", "custom-policy-enable")).success());
        assertEquals(1, commandApplications.get());
        assertTrue(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void unknownCommandTypeIsRejectedAtWorkerControlBoundary() {
        WorkerControlService service = workerControlService(
                new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry()),
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());

        assertFalse(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-unknown", "worker-unknown", "RESTART")
                .requester("operator")
                .build()).success());
        assertTrue(service.workerCommand("cmd-unknown").isEmpty());
    }

    @Test
    void expiredDrainCommandDoesNotCreateOrClearCommandDispatchGate() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-expiry");
        worker.setWorkerGroupId("group-expiry");
        registerWorker(workerManager, worker);
        WorkerControlService service = workerControlService(
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-expired", "worker-expiry", "DRAIN")
                .deadlineEpochMillis(1_000L)
                .build()).success());

        assertEquals(1, service.expireDueWorkerCommands(Instant.ofEpochMilli(2_000L), 10).size());
        assertTrue(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-drain", "worker-expiry", "DRAIN")
                .deadlineEpochMillis(3_000L)
                .build()).success());
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-drain", "accepted")).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));

        assertEquals(1, service.expireDueWorkerCommands(Instant.ofEpochMilli(4_000L), 10).size());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void requestCommandCanHandoffToConfiguredDeliveryCoordinatorAfterRecordingTruth() {
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerControlService service = workerControlService(
                new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry()),
                commandOwner,
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());
        AtomicReference<String> deliveredCommandId = new AtomicReference<>();
        service.setCommandDeliveryCoordinator(new WorkerCommandDeliveryCoordinator(
                        commandOwner,
                        command -> {
                            deliveredCommandId.set(command.commandId());
                            return WorkerCommandDeliveryResult.accepted("queued");
                        },
                        TraceEventLogger.noop()),
                Runnable::run);

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-handoff", "worker-handoff", "PING")
                .build()).success());

        assertEquals("cmd-handoff", deliveredCommandId.get());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED,
                service.workerCommand("cmd-handoff").orElseThrow().status());
    }

    @Test
    void acceptedRealtimeDrainHandoffAppliesCommandGatePolicy() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-realtime-drain");
        worker.setWorkerGroupId("group-realtime-drain");
        registerWorker(workerManager, worker);
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerControlService service = workerControlService(
                workerManager,
                commandOwner,
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());
        service.setCommandDeliveryCoordinator(new WorkerCommandDeliveryCoordinator(
                        commandOwner,
                        command -> WorkerCommandDeliveryResult.accepted("queued"),
                        TraceEventLogger.noop()),
                Runnable::run);

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-realtime-drain", "worker-realtime-drain", "DRAIN")
                .build()).success());

        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED,
                service.workerCommand("cmd-realtime-drain").orElseThrow().status());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    @Test
    void maintenanceRetryAttemptsIndexedRequestedCommandsUntilDeliveryAccepted() {
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerControlService service = workerControlService(
                new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry()),
                commandOwner,
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());
        AtomicInteger attempts = new AtomicInteger();
        service.setCommandDeliveryCoordinator(new WorkerCommandDeliveryCoordinator(
                        commandOwner,
                        command -> attempts.incrementAndGet() == 1
                                ? WorkerCommandDeliveryResult.workerUnavailable("route unavailable")
                                : WorkerCommandDeliveryResult.accepted("route restored"),
                        TraceEventLogger.noop()),
                Runnable::run);

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-retry", "worker-retry", "PING")
                .build()).success());
        assertEquals(WorkerCommandStatus.REQUESTED,
                service.workerCommand("cmd-retry").orElseThrow().status());

        List<com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult> retryResults =
                service.retryPendingWorkerCommandDeliveries(10, 3);

        assertEquals(1, retryResults.size());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED,
                service.workerCommand("cmd-retry").orElseThrow().status());
        assertEquals(2, service.workerCommand("cmd-retry").orElseThrow().deliveryAttemptCount());
    }

    @Test
    void maintenanceRetryClosesRequestedCommandAfterConfiguredMaxAttempts() {
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerControlService service = workerControlService(
                new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry()),
                commandOwner,
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());
        service.setCommandDeliveryCoordinator(new WorkerCommandDeliveryCoordinator(
                        commandOwner,
                        command -> WorkerCommandDeliveryResult.workerUnavailable("route unavailable"),
                        TraceEventLogger.noop()),
                Runnable::run);

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-max-attempts", "worker-retry", "PING")
                .build()).success());

        List<com.xa.mass.worker.runtime.command.WorkerCommandLifecycleResult> retryResults =
                service.retryPendingWorkerCommandDeliveries(10, 1);

        assertEquals(1, retryResults.size());
        assertEquals(WorkerCommandStatus.FAILED,
                service.workerCommand("cmd-max-attempts").orElseThrow().status());
        assertEquals("worker command delivery attempts exhausted",
                service.workerCommand("cmd-max-attempts").orElseThrow().statusReason());
    }

    @Test
    void pollingClaimMarksDrainDeliveredAndDisablesCommandGate() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationRuntimeStore(), new InMemoryWorkerRegistry());
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId("worker-command-poll");
        worker.setWorkerGroupId("group-command-poll");
        registerWorker(workerManager, worker);
        WorkerControlService service = workerControlService(
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                TraceEventLogger.noop());

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-poll", "worker-command-poll", "DRAIN")
                .build()).success());

        List<com.xa.mass.worker.runtime.command.WorkerCommandRecord> commands =
                service.claimPendingWorkerCommands("worker-command-poll", 10);

        assertEquals(1, commands.size());
        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED, commands.getFirst().status());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker.getWorkerId()));
    }

    private static WorkerControlService workerControlService(WorkerManager workerManager,
                                                             WorkerCommandLifecycleOwner commandOwner,
                                                             WorkerStateProjectionOwner stateOwner,
                                                             TraceEventLogger traceEventLogger) {
        return new WorkerControlService(
                workerManager,
                workerManager,
                new DefaultWorkerDispatchAvailabilityPolicy(workerManager, workerManager),
                commandOwner,
                stateOwner,
                traceEventLogger);
    }

    private static WorkerControlService workerControlService(WorkerManager workerManager,
                                                             WorkerCommandLifecycleOwner commandOwner,
                                                             WorkerStateProjectionOwner stateOwner,
                                                             WorkerDispatchEligibilityRuntime eligibilityRuntime,
                                                             TraceEventLogger traceEventLogger) {
        return new WorkerControlService(
                workerManager,
                workerManager,
                eligibilityRuntime,
                commandOwner,
                stateOwner,
                traceEventLogger);
    }

}
