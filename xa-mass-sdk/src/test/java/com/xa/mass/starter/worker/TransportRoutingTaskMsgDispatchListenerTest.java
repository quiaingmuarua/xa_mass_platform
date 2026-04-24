package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                List.of(webSocketAdapter, pollingAdapter)
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

    @Test
    void routesDispatchByCanonicalTransportHintInsteadOfAdapterProtocolLabel() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("ws-worker");
        worker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        workerManager.addWorker(worker);

        RecordingAdapter realtimeAdapter = new RecordingAdapter("websocket-v2", WorkerTransportHints.REALTIME);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                List.of(realtimeAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMsgId("msg-rt");
        taskMsg.applyLatestAttemptProjection("ws-worker", null, "batch-rt");

        listener.onTaskMsgsReady(task, List.of(taskMsg));

        assertEquals(List.of("msg-rt"), realtimeAdapter.dispatchedMsgIds);
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsMissing() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("missing-transport-worker");
        workerManager.addWorker(worker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter(WebSocketWorkerAdapter.PROTOCOL);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                List.of(webSocketAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMsgId("msg-1");
        taskMsg.applyLatestAttemptProjection("missing-transport-worker", null, "batch-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskMsgsReady(task, List.of(taskMsg))
        );
        assertEquals("Worker transportHint/onlineStrategy must be set before dispatch: missing-transport-worker",
                error.getMessage());
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsUnsupported() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("unsupported-transport-worker");
        worker.setOnlineStrategy("grpc");
        workerManager.addWorker(worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(PollingWorkerAdapter.PROTOCOL);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                List.of(pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMsgId("msg-1");
        taskMsg.applyLatestAttemptProjection("unsupported-transport-worker", null, "batch-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskMsgsReady(task, List.of(taskMsg))
        );
        assertEquals("Unsupported worker transport 'grpc' for worker unsupported-transport-worker; available transports=[polling]",
                error.getMessage());
    }

    private static final class RecordingAdapter implements WorkerAdapter {
        private final String protocol;
        private final String transportHint;
        private final List<String> dispatchedMsgIds = new ArrayList<>();

        private RecordingAdapter(String protocol) {
            this(protocol, WorkerTransportHints.normalize(protocol));
        }

        private RecordingAdapter(String protocol, String transportHint) {
            this.protocol = protocol;
            this.transportHint = transportHint;
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public String transportHint() {
            return transportHint;
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
        public void dispatchTaskItems(List<TaskDispatchItem> items) {
            for (TaskDispatchItem item : items) {
                dispatchedMsgIds.add(item.getMsgId());
            }
        }
    }
}
