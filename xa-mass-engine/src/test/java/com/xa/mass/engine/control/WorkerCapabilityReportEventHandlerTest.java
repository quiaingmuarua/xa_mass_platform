package com.xa.mass.engine.control;

import com.xa.mass.engine.InMemoryWorkerDeclarationRuntimeStore;

import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.worker.runtime.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.testutil.WorkerTestFixture;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.control.DefaultWorkerDispatchAvailabilityPolicy;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportStatus;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.xa.mass.engine.testutil.WorkerRegistrationTestSupport.registerWorker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCapabilityReportEventHandlerTest {

    @Test
    void capabilityReportEventRefreshesScoreBandMetadataThroughOwner() {
        InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime = new InMemoryWorkerScoreBandSlotRuntime();
        WorkerManager workerManager = workerManager(scoreBandRuntime);
        WorkerTestFixture worker = worker("worker-crawler", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch", "crawler.parse"));
        registerWorker(workerManager, worker);
        RecordingEventSink sink = new RecordingEventSink();

        InMemoryMassEventRuntime runtime = new InMemoryMassEventRuntime();
        WorkerCapabilityReportEventHandler handler = new WorkerCapabilityReportEventHandler(
                workerControlService(workerManager, new TraceEventLogger(sink)));
        handler.register(new KernelEventHandlerRegistry(runtime));

        CoreEventResponse response = runtime.dispatch(CoreEventRequest.builder()
                        .event(WorkerCapabilityReportEventHandler.EVENT_CODE)
                        .requestId("capability-report-1")
                        .payload(Map.of(
                                "workerId", "worker-crawler",
                                "capabilityVersion", 1,
                                "availableEventCodes", List.of("crawler.parse", "not.approved"),
                                "schedulingAttributes", Map.of("country", "us"),
                                "agentVersion", "agent-2"
                        ))
                        .build(),
                new CoreEventPrincipal("worker-crawler", "worker")
        );

        assertTrue(response.isSuccess());
        assertEquals("us", scoreBandRuntime.slot("crawler", "worker-crawler")
                .orElseThrow()
                .metadata()
                .attributes()
                .get("country"));
        assertTrue(sink.events().stream()
                .anyMatch(event -> event.getEventType() == ExecutionEventType.WORKER_CAPABILITY_REPORT_APPLIED
                        && event.getIdentity().workerId().equals("worker-crawler")
                        && "ACCEPTED".equals(event.getAttrs().get("result"))));
    }

    @Test
    void staleReportEventFailsWithoutChangingScoreBandSlot() {
        InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime = new InMemoryWorkerScoreBandSlotRuntime();
        WorkerManager workerManager = workerManager(scoreBandRuntime);
        WorkerTestFixture worker = worker("worker-crawler", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch", "crawler.parse"));
        registerWorker(workerManager, worker);
        WorkerCapabilityReportEventHandler handler = new WorkerCapabilityReportEventHandler(
                workerControlService(workerManager, TraceEventLogger.noop()));

        assertTrue(handler.handle(request(2, List.of("crawler.parse")), new CoreEventPrincipal("worker", "test"))
                .isSuccess());
        CoreEventResponse stale = handler.handle(request(1, List.of("crawler.fetch")),
                new CoreEventPrincipal("worker", "test"));

        assertFalse(stale.isSuccess());
        assertEquals(WorkerCapabilityReportStatus.STALE.name(), stale.getCode());
        assertEquals("worker-crawler", scoreBandRuntime.slot("crawler", "worker-crawler")
                .orElseThrow()
                .workerId());
    }

    private static WorkerTestFixture worker(String workerId, String workerGroupId) {
        WorkerTestFixture worker = new WorkerTestFixture();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(workerGroupId);
        return worker;
    }

    private static CoreEventRequest request(long capabilityVersion, List<String> availableEventCodes) {
        return CoreEventRequest.builder()
                .event(WorkerCapabilityReportEventHandler.EVENT_CODE)
                .requestId("report-" + capabilityVersion)
                .payload(Map.of(
                        "workerId", "worker-crawler",
                        "capabilityVersion", capabilityVersion,
                        "availableEventCodes", availableEventCodes
                ))
                .build();
    }

    private static WorkerManager workerManager(InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime) {
        return new WorkerManager(
                new InMemoryWorkerDeclarationRuntimeStore(),
                new InMemoryWorkerRegistry(),
                scoreBandRuntime
        );
    }

    private static WorkerControlService workerControlService(WorkerManager workerManager,
                                                             TraceEventLogger traceEventLogger) {
        return new WorkerControlService(
                workerManager,
                workerManager,
                new DefaultWorkerDispatchAvailabilityPolicy(workerManager, workerManager),
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                traceEventLogger);
    }
}
