package com.xa.mass.starter.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.starter.transport.TransportBinding;
import com.xa.mass.starter.transport.TransportRuntimeRegistry;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
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

        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket");
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, webSocketAdapter, pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg wsMsg = new TaskMsg();
        wsMsg.setTaskId("task-1");
        wsMsg.setMessageId("msg-ws");
        wsMsg.applyLatestAttemptProjection("ws-worker", null, "batch-ws");

        TaskMsg pollMsg = new TaskMsg();
        pollMsg.setTaskId("task-1");
        pollMsg.setMessageId("msg-poll");
        pollMsg.applyLatestAttemptProjection("poll-worker", null, "batch-poll");

        listener.onTaskMsgsReady(task, List.of(wsMsg, pollMsg));

        assertEquals(List.of("msg-ws"), webSocketAdapter.dispatchedMessageIds);
        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
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
                runtimeRegistry(workerManager, realtimeAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMessageId("msg-rt");
        taskMsg.applyLatestAttemptProjection("ws-worker", null, "batch-rt");

        listener.onTaskMsgsReady(task, List.of(taskMsg));

        assertEquals(List.of("msg-rt"), realtimeAdapter.dispatchedMessageIds);
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsMissing() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("missing-transport-worker");
        workerManager.addWorker(worker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket");
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, webSocketAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMessageId("msg-1");
        taskMsg.applyLatestAttemptProjection("missing-transport-worker", null, "batch-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskMsgsReady(task, List.of(taskMsg))
        );
        assertEquals("Worker adapterId is not set and transportHint/onlineStrategy is not set: missing-transport-worker",
                error.getMessage());
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsUnsupported() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("unsupported-transport-worker");
        worker.setOnlineStrategy("grpc");
        workerManager.addWorker(worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMessageId("msg-1");
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
        private final List<String> dispatchedMessageIds = new ArrayList<>();

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
                return Set.of("ws");
            }
            if ("polling".equals(protocol)) {
                return Set.of("pull", "queue");
            }
            return Set.of();
        }

        @Override
        public void dispatchTaskItems(List<TaskDispatchItem> items) {
            for (TaskDispatchItem item : items) {
                dispatchedMessageIds.add(item.getMessageId());
            }
        }
    }

    private static TransportRuntimeRegistry runtimeRegistry(WorkerManager workerManager, RecordingAdapter... adapters) {
        return new TransportRuntimeRegistry(
                workerManager,
                report -> true,
                new NoopWorkerSystemEventChannel(),
                Arrays.stream(adapters)
                        .map(adapter -> TransportBinding.builder(adapter).build())
                        .toList()
        );
    }

    private static final class NoopWorkerSystemEventChannel implements com.xa.mass.transport.channel.WorkerSystemEventChannel {
        @Override
        public void publishWorkerOnline(String workerId, String reason, String traceId) {
        }

        @Override
        public void publishWorkerOffline(String workerId, String reason, String traceId) {
        }
    }
}
