package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportRoutingTaskMsgDispatchListenerTest {

    @Test
    void routesDispatchByWorkerOnlineStrategy() {
        WorkerManager workerManager = new WorkerManager();

        Worker webSocketWorker = new Worker();
        webSocketWorker.setWorkerId("ws-worker");
        webSocketWorker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        workerManager.addWorker(webSocketWorker);

        Worker pollingWorker = new Worker();
        pollingWorker.setWorkerId("poll-worker");
        pollingWorker.setOnlineStrategy("pull");
        workerManager.addWorker(pollingWorker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter(WebSocketWorkerAdapter.PROTOCOL);
        RecordingAdapter pollingAdapter = new RecordingAdapter(PollingWorkerAdapter.PROTOCOL);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                List.of(webSocketAdapter, pollingAdapter),
                WebSocketWorkerAdapter.PROTOCOL
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg wsMsg = new TaskMsg();
        wsMsg.setTaskId("task-1");
        wsMsg.setMsgId("msg-ws");
        wsMsg.applyLatestAttemptProjection("ws-worker", null, "batch-ws");

        TaskMsg pollMsg = new TaskMsg();
        pollMsg.setTaskId("task-1");
        pollMsg.setMsgId("msg-poll");
        pollMsg.applyLatestAttemptProjection("poll-worker", null, "batch-poll");

        listener.onTaskMsgsReady(task, List.of(wsMsg, pollMsg));

        assertEquals(List.of("msg-ws"), webSocketAdapter.dispatchedMsgIds);
        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMsgIds);
    }

    private static final class RecordingAdapter implements WorkerAdapter {
        private final String protocol;
        private final List<String> dispatchedMsgIds = new ArrayList<>();

        private RecordingAdapter(String protocol) {
            this.protocol = protocol;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public Set<String> aliases() {
            if ("websocket".equals(protocol)) {
                return Set.of("ws", WorkerTransportHints.REALTIME, "push");
            }
            if ("polling".equals(protocol)) {
                return Set.of("pull", "queue", WorkerTransportHints.POLLING);
            }
            return Set.of();
        }

        @Override
        public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
            for (TaskMsg taskMsg : taskMsgs) {
                dispatchedMsgIds.add(taskMsg.getMsgId());
            }
        }
    }
}
