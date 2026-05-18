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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        workerManager.addWorker(worker);
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
        assertTrue(service.applyWorkerCommandAcknowledgement(
                WorkerCommandAcknowledgement.deliveryAccepted("cmd-1", "handoff accepted")).success());
        assertTrue(service.applyWorkerCapabilityReport(WorkerCapabilityReport.builder("worker-1", 1)
                .availableEventCodes(List.of("crawler.fetch", "not.approved"))
                .schedulingAttributes(Map.of("country", "us"))
                .build()).success());
        assertTrue(service.applyWorkerStateReport(WorkerStateReport.builder("worker-1", 1, "READY")
                .reason("test")
                .build()).success());

        assertEquals(WorkerCommandStatus.DELIVERY_ACCEPTED,
                service.workerCommand("cmd-1").orElseThrow().status());
        assertEquals(1, service.workerCommandsForWorker("worker-1").size());
        assertEquals("READY", service.workerStateProjection("worker-1").orElseThrow().state());
        assertEquals(1, service.workerStateProjections().size());
        assertEquals("us", workerManager.getWorkerRegistrySnapshot()
                .group("group-1")
                .orElseThrow()
                .defaultAttributes()
                .get("country"));
        sink.assertHasEvent(ExecutionEventType.WORKER_COMMAND_STATUS_TRANSITION, "commandId", "cmd-1");
        assertTrue(sink.eventsOfType(ExecutionEventType.WORKER_CAPABILITY_REPORT_APPLIED).stream()
                .anyMatch(event -> "worker-1".equals(event.getIdentity().workerId())));
        assertTrue(sink.eventsOfType(ExecutionEventType.WORKER_STATE_REPORT_APPLIED).stream()
                .anyMatch(event -> "worker-1".equals(event.getIdentity().workerId())));
    }
}
