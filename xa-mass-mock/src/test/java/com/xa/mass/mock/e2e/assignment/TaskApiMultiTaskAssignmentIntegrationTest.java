package com.xa.mass.mock.e2e.assignment;

import com.google.gson.Gson;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.massMessage.MessageContext;
import com.xa.mass.gateway.model.massMessage.MessageResult;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class TaskApiMultiTaskAssignmentIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Test
    void twoReadyTasksAreAssignedAcrossSeparateDevicesAndBothComplete() throws Exception {
        registerWorker("it-worker-0");
        registerWorker("it-worker-1");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient firstClient = new ManualAckWebSocketClient(wsUri, "it-worker-0");
        ManualAckWebSocketClient secondClient = new ManualAckWebSocketClient(wsUri, "it-worker-1");

        try {
            assertTrue(firstClient.connectBlocking(), "First manual mock client failed to connect");
            assertTrue(secondClient.connectBlocking(), "Second manual mock client failed to connect");

            String firstTaskId = createTaskId("multi-task-a", "multi task assignment integration", "target-a");
            String secondTaskId = createTaskId("multi-task-b", "multi task assignment integration", "target-b");

            assertEquals(Boolean.TRUE, audit(firstTaskId, "multi-task-a").get("success"));
            assertEquals(Boolean.TRUE, audit(secondTaskId, "multi-task-b").get("success"));

            MassMessage firstDispatch = firstClient.awaitTask(3, TimeUnit.SECONDS);
            MassMessage secondDispatch = secondClient.awaitTask(3, TimeUnit.SECONDS);

            assertNotNull(firstDispatch, "First worker should receive one task while it remains in-flight");
            assertNotNull(secondDispatch, "Second worker should receive the other task while the first worker is locked");

            TaskSnapshot firstRunning = waitForTaskSnapshot(firstTaskId, "RUNNING");
            TaskSnapshot secondRunning = waitForTaskSnapshot(secondTaskId, "RUNNING");

            assertRunningSingleDeviceTask(firstRunning);
            assertRunningSingleDeviceTask(secondRunning);

            String firstWorkerId = String.valueOf(firstRunning.messages().get(0).get("workerId"));
            String secondWorkerId = String.valueOf(secondRunning.messages().get(0).get("workerId"));
            assertEquals(Set.of("it-worker-0", "it-worker-1"), Set.of(firstWorkerId, secondWorkerId));

            AckSnapshot firstAck = firstClient.sendSuccess(firstDispatch, "multi-task-a-ok");
            AckSnapshot secondAck = secondClient.sendSuccess(secondDispatch, "multi-task-b-ok");
            assertNotNull(firstAck);
            assertNotNull(secondAck);
            assertEquals(200, firstAck.code());
            assertEquals(200, secondAck.code());
            assertEquals("task result processed", firstAck.message());
            assertEquals("task result processed", secondAck.message());

            TaskSnapshot firstTerminal = waitForTerminalTask(firstTaskId);
            TaskSnapshot secondTerminal = waitForTerminalTask(secondTaskId);

            assertTerminalSingleDeviceTask(firstTerminal);
            assertTerminalSingleDeviceTask(secondTerminal);
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

    private void assertRunningSingleDeviceTask(TaskSnapshot snapshot) {
        assertEquals("RUNNING", snapshot.task().get("status"));
        assertEquals(1, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, snapshot.messages().size());
        Map<String, Object> message = snapshot.messages().get(0);
        assertNotNull(message.get("workerId"));
        assertNotNull(message.get("workerContextId"));
        assertNotNull(message.get("batchId"));
    }

    private void assertTerminalSingleDeviceTask(TaskSnapshot snapshot) {
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, ((Number) snapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(1, snapshot.messages().size());
        Map<String, Object> message = snapshot.messages().get(0);
        assertEquals("SUCCESS", message.get("status"));
        assertNotNull(message.get("workerId"));
        assertNotNull(message.get("workerContextId"));
        assertNotNull(message.get("batchId"));
    }

    private void registerWorker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("us");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        workerManager.addWorker(worker);

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("worker-context-" + workerId);
        workerContext.setWorkerId(workerId);
        workerContext.setChannel("us");
        workerContext.setStatus(WorkerContextStatus.IDLE);
        workerManager.addWorkerContext(workerId, workerContext);
    }

    private record AckSnapshot(String msgId, int code, String message) {
    }

    private static final class ManualAckWebSocketClient extends MassWebSocketClientImpl {
        private final BlockingQueue<MassMessage> taskQueue = new LinkedBlockingQueue<>();
        private final BlockingQueue<AckSnapshot> ackQueue = new LinkedBlockingQueue<>();

        private ManualAckWebSocketClient(URI serverUri, String workerId) {
            super(serverUri, workerId);
        }

        @Override
        public void onMessage(String message) {
            try {
                MassMessage massMessage = GSON.fromJson(message, MassMessage.class);
                if (massMessage != null && massMessage.getMsgType() == MessageType.TASK) {
                    if (massMessage.isResponse()) {
                        MessageResult result = GSON.fromJson(massMessage.getPayload(), MessageResult.class);
                        ackQueue.offer(new AckSnapshot(massMessage.getMsgId(), result.getCode(), result.getMessage()));
                    } else {
                        taskQueue.offer(massMessage);
                    }
                    return;
                }
            } catch (Exception ignored) {
                // Fall through to the base client for non-task frames or malformed payloads.
            }
            super.onMessage(message);
        }

        private MassMessage awaitTask(long timeout, TimeUnit unit) throws InterruptedException {
            return taskQueue.poll(timeout, unit);
        }

        private AckSnapshot sendSuccess(MassMessage taskMessage, String detail) throws Exception {
            MassMessage response = new MassMessage();
            response.setMsgId(taskMessage.getMsgId());
            response.setResponse(true);
            response.setMsgType(MessageType.TASK);
            response.setSubMsgType(taskMessage.getSubMsgType());
            response.setFrom(MessageDirection.CLIENT);
            response.setProject(taskMessage.getProject());

            MessageContext originalContext = taskMessage.getContext();
            if (originalContext != null) {
                MessageContext responseContext = new MessageContext();
                responseContext.setConnRole(originalContext.getConnRole());
                responseContext.setTid(originalContext.getTid());
                responseContext.setRetryCount(originalContext.getRetryCount());
                responseContext.setWorkerId(getWorkerId());
                response.setContext(responseContext);
            }

            response.setPayload(GSON.toJsonTree(Map.of(
                    "status", "SUCCESS",
                    "mockData", detail
            )));
            sendMessage(GSON.toJson(response));
            return ackQueue.poll(3, TimeUnit.SECONDS);
        }
    }
}
