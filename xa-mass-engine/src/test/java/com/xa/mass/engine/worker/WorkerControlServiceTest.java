package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.command.WorkerCommandAcknowledgement;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.command.WorkerCommandRequest;
import com.xa.mass.engine.command.WorkerCommandStatus;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_COMMAND;
import static com.xa.mass.engine.testutil.WorkerRegistrationTestSupport.registerWorker;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerControlServiceTest {

    @Test
    void appliesOwnerBackedCommandCapabilityAndStateEntriesWithReadViews() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        worker.setWorkerGroupId("group-1");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        registerWorker(workerManager, worker);
        WorkerCommandLifecycleOwner commandOwner = new WorkerCommandLifecycleOwner();
        WorkerStateProjectionOwner stateOwner = new WorkerStateProjectionOwner();
        RecordingEventSink sink = new RecordingEventSink();
        WorkerControlService service = new WorkerControlService(
                workerManager,
                commandOwner,
                stateOwner,
                new TraceEventLogger(sink));

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-1", "worker-1", "DRAIN")
                .requester("operator")
                .build()).success());
        assertTrue(workerManager.isWorkerDispatchEnabled(worker));
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-1", "handoff accepted")).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));
        assertTrue(service.applyWorkerCapabilityReport(WorkerCapabilityReport.builder("worker-1", 1)
                .availableEventCodes(List.of("crawler.fetch", "not.approved"))
                .schedulingAttributes(Map.of("country", "us"))
                .build()).success());
        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 1, "DRAINING")
                .reason("maintenance")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 2, "AVAILABLE")
                .reason("resumed")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 3, "DEGRADED")
                .reason("slow")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED,
                service.workerCommand("cmd-1").orElseThrow().status());
        assertEquals(1, service.workerCommandsForWorker("worker-1").size());
        assertEquals("DEGRADED", service.workerStateProjection("worker-1").orElseThrow().state());
        assertEquals(1, service.workerStateProjections().size());
        assertEquals("us", workerManager.getWorkerRegistrySnapshot()
                .worker("worker-1")
                .orElseThrow()
                .getAttributes()
                .get("country"));
        sink.assertHasEvent(ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION, "commandId", "cmd-1");
        assertTrue(sink.eventsOfType(ExecutionEventType.WORKER_CAPABILITY_REPORT_APPLIED).stream()
                .anyMatch(event -> "worker-1".equals(event.getIdentity().workerId())));
        assertTrue(sink.eventsOfType(ExecutionEventType.WORKER_STATE_REPORT_APPLIED).stream()
                .anyMatch(event -> "worker-1".equals(event.getIdentity().workerId())));
    }

    @Test
    void drainCommandFailureDoesNotReenableDispatchWithoutExplicitAvailableState() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        Worker worker = new Worker();
        worker.setWorkerId("worker-2");
        worker.setWorkerGroupId("group-2");
        registerWorker(workerManager, worker);
        WorkerControlService service = new WorkerControlService(
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
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.failed("cmd-2", "worker-side failure")).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-2", 1, "DEGRADED")
                .reason("still draining")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-2", 2, "AVAILABLE")
                .reason("resume")
                .build()).success());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(workerManager.getDispatchAvailabilityOwner().clearSource(
                "worker-2",
                WORKER_COMMAND,
                "command cleared"
        ));
        assertTrue(workerManager.isWorkerDispatchEnabled(worker));
    }

    @Test
    void dispatchWakeupFiresOnlyForSchedulingRecoveryEvidence() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        Worker worker = new Worker();
        worker.setWorkerId("worker-wakeup");
        worker.setWorkerGroupId("group-wakeup");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));
        registerWorker(workerManager, worker);
        WorkerControlService service = new WorkerControlService(
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
    void dispatchAvailabilityPolicyIsPluggable() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());
        Worker worker = new Worker();
        worker.setWorkerId("worker-3");
        worker.setWorkerGroupId("group-3");
        registerWorker(workerManager, worker);
        AtomicInteger stateApplications = new AtomicInteger();
        AtomicInteger commandApplications = new AtomicInteger();
        WorkerDispatchAvailabilityPolicy policy = new WorkerDispatchAvailabilityPolicy() {
            @Override
            public void applyWorkerStateProjection(WorkerStateProjection projection,
                                                   WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner) {
                stateApplications.incrementAndGet();
                dispatchAvailabilityOwner.disableForDraining(
                        projection.workerId(),
                        WORKER_STATE,
                        projection.reason()
                );
            }

            @Override
            public void applyWorkerCommandLifecycleResult(com.xa.mass.engine.command.WorkerCommandLifecycleResult result,
                                                          WorkerDispatchAvailabilityOwner dispatchAvailabilityOwner) {
                commandApplications.incrementAndGet();
                dispatchAvailabilityOwner.clearSource(
                        result.record().workerId(),
                        WORKER_STATE,
                        result.record().statusReason()
                );
            }
        };
        WorkerControlService service = new WorkerControlService(
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                workerManager.getDispatchAvailabilityOwner(),
                policy,
                TraceEventLogger.noop());

        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-3", 1, "AVAILABLE")
                .reason("custom-policy-disable")
                .build()).success());
        assertEquals(1, stateApplications.get());
        assertFalse(workerManager.isWorkerDispatchEnabled(worker));

        assertTrue(service.requestWorkerCommand(WorkerCommandRequest.builder(
                        "cmd-3", "worker-3", "DRAIN")
                .requester("operator")
                .build()).success());
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-3", "custom-policy-enable")).success());
        assertEquals(1, commandApplications.get());
        assertTrue(workerManager.isWorkerDispatchEnabled(worker));
    }
}
