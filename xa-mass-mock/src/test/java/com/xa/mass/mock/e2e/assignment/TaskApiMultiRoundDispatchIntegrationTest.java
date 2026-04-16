package com.xa.mass.mock.e2e.assignment;

import com.google.gson.Gson;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TaskApiMultiRoundDispatchIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Gson GSON = new Gson();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Test
    void singleDeviceWithBatchSizeOneCompletesTaskAcrossMultipleRounds() throws Exception {
        String workerId = "round-worker-0";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, workerId);
        try {
            assertTrue(client.connectBlocking(), "Mock client failed to connect");

            String taskId = createTaskId(
                    "multi-round",
                    "single device multi round dispatch",
                    List.of("target-a", "target-b", "target-c"),
                    1
            );

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=multi-round",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, approveResponse.get("success"));

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, ((Number) terminal.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(3, ((Number) terminal.task().get("taskSuccessNumber")).intValue());
            assertEquals(3, terminal.messages().size());

            for (Map<String, Object> message : terminal.messages()) {
                assertEquals("SUCCESS", message.get("status"));
                assertEquals(workerId, message.get("workerId"));
            }

            WorkerContext workerContext = workerManager.getWorkerContexts(workerId).get(0);
            assertEquals(WorkerContextStatus.IDLE, workerContext.getStatus());
        } finally {
            client.disconnect();
        }
    }

    @Test
    void singleDeviceWithBatchSizeTwoWaitsForNextRoundUntilCurrentRoundFinishes() throws Exception {
        String workerId = "round-worker-batch-2";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        ManualAckWebSocketClient client = new ManualAckWebSocketClient(wsUri, workerId);
        try {
            assertTrue(client.connectBlocking(), "Manual mock client failed to connect");

            String taskId = createTaskId(
                    "multi-round-batch-two",
                    "single device waits for current round to finish",
                    List.of("target-a", "target-b", "target-c"),
                    2
            );

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=multi-round-batch-two",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, approveResponse.get("success"));

            MassMessage first = client.awaitTask(3, TimeUnit.SECONDS);
            MassMessage second = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(first, "First round should dispatch the first message");
            assertNotNull(second, "First round should dispatch the second message");
            assertNull(client.awaitTask(750, TimeUnit.MILLISECONDS), "Third message should wait for the next dispatch round");

            TaskSnapshot firstRound = waitForTaskSnapshot(
                    taskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.messages().size() == 3
                            && snapshot.messages().stream().filter(msg -> workerId.equals(msg.get("workerId"))).count() == 2
                            && snapshot.messages().stream().filter(msg -> msg.get("workerId") == null).count() == 1
                            && snapshot.messages().stream().noneMatch(msg -> isTerminalMessageStatus(msg.get("status"))),
                    "RUNNING with two in-flight messages bound to the worker and one pending INIT-style message",
                    20,
                    100L
            );
            assertEquals(2L,
                    firstRound.messages().stream().filter(msg -> workerId.equals(msg.get("workerId"))).count());
            assertEquals(1L,
                    firstRound.messages().stream().filter(msg -> msg.get("workerId") == null).count());

            AckSnapshot firstAck = client.sendSuccess(first, "round-1-a");
            assertNotNull(firstAck);
            assertEquals(200, firstAck.code());
            assertEquals("task result processed", firstAck.message());
            assertNull(client.awaitTask(750, TimeUnit.MILLISECONDS), "Worker should stay busy until the whole round finishes");

            TaskSnapshot afterFirstResult = waitForTaskSnapshot(
                    taskId,
                    snapshot -> "RUNNING".equals(snapshot.task().get("status"))
                            && snapshot.messages().size() == 3
                            && snapshot.messages().stream().filter(msg -> "SUCCESS".equals(msg.get("status"))).count() == 1
                            && snapshot.messages().stream().filter(msg -> workerId.equals(msg.get("workerId"))).count() == 2
                            && snapshot.messages().stream().filter(msg -> msg.get("workerId") == null).count() == 1,
                    "RUNNING with one finished message, one remaining in-flight message, and one pending message",
                    20,
                    100L
            );
            assertEquals(1L,
                    afterFirstResult.messages().stream().filter(msg -> "SUCCESS".equals(msg.get("status"))).count());

            AckSnapshot secondAck = client.sendSuccess(second, "round-1-b");
            assertNotNull(secondAck);
            assertEquals(200, secondAck.code());
            assertEquals("task result processed", secondAck.message());

            MassMessage third = client.awaitTask(3, TimeUnit.SECONDS);
            assertNotNull(third, "Next round should begin after the first round finishes");

            AckSnapshot thirdAck = client.sendSuccess(third, "round-2-c");
            assertNotNull(thirdAck);
            assertEquals(200, thirdAck.code());
            assertEquals("task result processed", thirdAck.message());

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, ((Number) terminal.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(3, ((Number) terminal.task().get("taskSuccessNumber")).intValue());
            assertEquals(List.of("SUCCESS", "SUCCESS", "SUCCESS"),
                    terminal.messages().stream().map(msg -> String.valueOf(msg.get("status"))).collect(Collectors.toList()));
            assertEquals(List.of(workerId, workerId, workerId),
                    terminal.messages().stream().map(msg -> String.valueOf(msg.get("workerId"))).collect(Collectors.toList()));
        } finally {
            client.disconnect();
        }
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
        workerManager.addWorkerContext(workerContext);
    }

    private boolean isTerminalMessageStatus(Object status) {
        return "SUCCESS".equals(status) || "FAILED".equals(status) || "EXPIRED".equals(status);
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
