package com.xa.mass.engine.worker;

import com.xa.mass.worker.runtime.WorkerStateProjectionOwner;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import com.xa.mass.command.event.CoreEventPrincipal;
import com.xa.mass.command.event.CoreEventRequest;
import com.xa.mass.command.event.CoreEventResponse;
import com.xa.mass.command.event.InMemoryMassEventRuntime;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwner;
import com.xa.mass.engine.event.KernelEventHandlerRegistry;
import com.xa.mass.engine.strategy.WorkerTaskSelectorFactory;
import com.xa.mass.engine.testutil.RecordingEventSink;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerCapabilityReportStatus;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
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
    void capabilityReportEventRefreshesWorkerRegistrySnapshotThroughOwner() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
        Worker worker = worker("worker-crawler", "crawler");
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
        assertEquals(List.of("worker-crawler"), workerIds(workerManager, task("demoApp", "crawler.parse")));
        assertEquals(List.of("worker-crawler"), workerIds(workerManager, task("demoApp", "crawler.fetch")));
        assertEquals(List.of("worker-crawler"), workerIds(workerManager, task("demoApp", "not.approved")));
        assertFalse(workerManager.getWorkerRegistrySnapshot()
                .workerSupportsEventKey("worker-crawler", new EventKey("demoApp", "not.approved")));
        assertEquals("us", workerManager.getWorkerRegistrySnapshot()
                .worker("worker-crawler")
                .orElseThrow()
                .getAttributes()
                .get("country"));
        assertTrue(sink.events().stream()
                .anyMatch(event -> event.getEventType() == ExecutionEventType.WORKER_CAPABILITY_REPORT_APPLIED
                        && event.getIdentity().workerId().equals("worker-crawler")
                        && "ACCEPTED".equals(event.getAttrs().get("result"))));
    }

    @Test
    void staleReportEventFailsWithoutChangingCandidateSnapshot() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage(), new InMemoryWorkerRegistry());
        Worker worker = worker("worker-crawler", "crawler");
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
        assertEquals(List.of("worker-crawler"), workerIds(workerManager, task("demoApp", "crawler.parse")));
        assertEquals(List.of("worker-crawler"), workerIds(workerManager, task("demoApp", "crawler.fetch")));
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

    private static Task task(String project, String eventCode) {
        Task task = new Task();
        task.setProject(project);
        task.setSharedConfig(Map.of(
                TaskSharedConfig.WORKER_GROUP_ID, "crawler",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, eventCode)
        ));
        return task;
    }

    private static List<String> workerIds(WorkerManager workerManager, Task task) {
        return workerManager.findWorkerCandidateBatch(WorkerTaskSelectorFactory.fromTask(task), 512).candidates().stream()
                .map(WorkerCandidateRow::workerId)
                .toList();
    }

    private static Worker worker(String workerId, String workerGroupId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(workerGroupId);
        return worker;
    }

    private static WorkerControlService workerControlService(WorkerManager workerManager,
                                                             TraceEventLogger traceEventLogger) {
        return new WorkerControlService(
                workerManager,
                workerManager,
                workerManager,
                new WorkerCommandLifecycleOwner(),
                new WorkerStateProjectionOwner(),
                traceEventLogger);
    }
}
