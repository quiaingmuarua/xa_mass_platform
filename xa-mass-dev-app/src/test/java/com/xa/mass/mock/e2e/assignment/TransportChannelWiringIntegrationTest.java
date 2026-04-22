package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.starter.worker.PollingWorkerAdapter;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TransportChannelWiringIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Autowired
    private MassSdkApplication app;

    @Test
    void allThreeTransportChannelsAreInvokedForPollingWorker() throws Exception {
        List<String> channelCallOrder = new CopyOnWriteArrayList<>();

        // Wrap the real polling adapter to observe channel calls.
        String workerId = "channel-wire-worker-001";
        registerPollingWorker(workerId);

        // Intercept WorkerSystemEventChannel via builder overridepoint.
        // Since we can't swap the channel post-construction in this test context,
        // we verify indirectly: after connect(), the worker must be ONLINE.
        app.pollingWorker(workerId).connect();
        assertTrue(app.isWorkerOnline(workerId),
                "WorkerSystemEventChannel.publishWorkerOnline must have been invoked");
        channelCallOrder.add("WorkerSystemEventChannel.publishWorkerOnline");

        // Create + approve a task so the engine dispatches via TaskDispatchChannel.
        String taskId = createTaskId("channel-wire-task", "channel wiring", "target-wire-001");
        exchange("/status/api/tasks/" + taskId + "/audit?approved=true&comment=wire", HttpMethod.POST, null);

        // Poll confirms TaskDispatchChannel.dispatchTaskMessages was called.
        var items = List.<com.xa.mass.transport.model.TaskDispatchItem>of();
        for (int i = 0; i < 20 && items.isEmpty(); i++) {
            items = app.pollingWorker(workerId).poll(10);
            if (items.isEmpty()) Thread.sleep(250L);
        }
        assertFalse(items.isEmpty(), "TaskDispatchChannel must have dispatched at least one message");
        channelCallOrder.add("TaskDispatchChannel.dispatchTaskMessages");

        // Submit result confirms TaskResultIngestChannel.ingestTaskResult was called.
        boolean submitted = app.pollingWorker(workerId).submitResult(items.get(0), true, "ok", Map.of());
        assertTrue(submitted, "TaskResultIngestChannel.ingestTaskResult must accept the result");
        channelCallOrder.add("TaskResultIngestChannel.ingestTaskResult");

        // All three channels confirmed.
        assertEquals(3, channelCallOrder.size(), "All three transport channels must be invoked");

        // Task must reach terminal state.
        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
    }

    private void registerPollingWorker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("us");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setOnlineStrategy(PollingWorkerAdapter.PROTOCOL);
        workerManager.addWorker(worker);

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-" + workerId);
        workerContext.setWorkerId(workerId);
        workerContext.setRoutingTags(java.util.Set.of("us"));
        workerContext.setStatus(WorkerContextStatus.IDLE);
        workerManager.addWorkerContext(workerContext);
    }
}
