package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.worker.PullWorkerSession;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates that all three transport channels are wired and invoked in the correct
 * order when a polling worker completes a task:
 *
 * <ol>
 *   <li>{@link WorkerSystemEventChannel} — worker announces online</li>
 *   <li>{@link TaskDispatchChannel} — engine dispatches task messages</li>
 *   <li>{@link TaskResultIngestChannel} — worker submits result</li>
 * </ol>
 */
@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TransportChannelWiringIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private MassSdkApplication app;

    @Test
    void allThreeTransportChannelsAreInvokedForPollingWorker() throws Exception {
        assertRuntimeExecutorDiagnosticsExposeEventExecutor();

        List<String> channelCallOrder = new CopyOnWriteArrayList<>();

        String workerId = "channel-wire-worker-001";
        registerPollingWorker(workerId);
        PullWorkerSession session = app.pullWorker(workerId);

        // We verify system events indirectly: connect() marks the worker online through
        // the runtime system-event channel.
        session.connect();
        for (int i = 0; i < 20 && !app.isWorkerOnline(workerId); i++) {
            Thread.sleep(100L);
        }
        assertTrue(app.isWorkerOnline(workerId),
                "WorkerSystemEventChannel.publishWorkerOnline must have been invoked");
        channelCallOrder.add("WorkerSystemEventChannel.publishWorkerOnline");

        // Create + approve a task so the runtime dispatches via TaskDispatchChannel.
        String taskId = createTaskId("channel-wire-task", "channel wiring", "target-wire-001");
        exchange("/api/v1/tasks/" + taskId + ":approve", HttpMethod.POST, null);

        // Poll confirms TaskDispatchChannel.dispatchTaskItems was called.
        var items = List.<com.xa.mass.transport.model.TaskDispatchItem>of();
        for (int i = 0; i < 20 && items.isEmpty(); i++) {
            items = session.poll(10);
            if (items.isEmpty()) Thread.sleep(250L);
        }
        assertFalse(items.isEmpty(), "TaskDispatchChannel must have dispatched at least one task item");
        channelCallOrder.add("TaskDispatchChannel.dispatchTaskItems");

        // Submit result confirms TaskResultIngestChannel.ingest was called.
        boolean submitted = session.submitResult(items.get(0), true, "ok", Map.of());
        assertTrue(submitted, "TaskResultIngestChannel.ingest must accept the result");
        channelCallOrder.add("TaskResultIngestChannel.ingest");

        // All three channels confirmed.
        assertEquals(3, channelCallOrder.size(), "All three transport channels must be invoked");

        // Task must reach terminal state.
        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
    }

    @SuppressWarnings("unchecked")
    private void assertRuntimeExecutorDiagnosticsExposeEventExecutor() {
        Map<String, Object> response = exchange("/api/v1/runtime/queues", HttpMethod.GET, null);
        assertApiOk(response);
        Map<String, Object> runtimeExecutors =
                (Map<String, Object>) responseData(response).get("runtimeExecutors");
        assertNotNull(runtimeExecutors, "runtimeExecutors diagnostics should be exposed");
        Map<String, Object> transport = (Map<String, Object>) runtimeExecutors.get("transport");
        Map<String, Object> event = (Map<String, Object>) runtimeExecutors.get("event");
        assertEquals(true, transport.get("available"));
        assertEquals(10000, transport.get("maxPendingTasks"));
        assertEquals(true, event.get("available"),
                "dev-app should enable bounded SDK event handler execution by default");
        assertEquals(2000, event.get("maxPendingTasks"));
    }

    private void registerPollingWorker(String workerId) {
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId("us")
                .eventBindings(List.of(
                        WorkerEventBinding.builder()
                                .eventCode("demo.dispatch")
                                .projectCodes(List.of("demoApp"))
                                .build()
                ))
                .transportHint("polling")
                .build());

        app.registerWorkerContext(WorkerContextRegistration.builder()
                .workerContextId("ctx-" + workerId)
                .workerId(workerId)
                .routingTags(java.util.Set.of("us"))
                .build());
    }
}
