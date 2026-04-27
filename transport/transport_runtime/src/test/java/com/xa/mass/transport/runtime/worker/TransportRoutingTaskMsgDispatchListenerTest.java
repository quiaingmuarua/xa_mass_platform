package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.listener.TaskDispatchBinding;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRoutingTaskMsgDispatchListenerTest {

    @Test
    void routesDispatchByWorkerOnlineStrategy() {
        WorkerManager workerManager = new WorkerManager();

        Worker webSocketWorker = new Worker();
        webSocketWorker.setWorkerId("ws-worker");
        webSocketWorker.setAdapterId("websocket");
        webSocketWorker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        workerManager.addWorker(webSocketWorker);

        Worker pollingWorker = new Worker();
        pollingWorker.setWorkerId("poll-worker");
        pollingWorker.setOnlineStrategy(WorkerTransportHints.POLLING);
        workerManager.addWorker(pollingWorker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket", WorkerTransportHints.REALTIME);
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
        TaskMsgAttempt wsAttempt = attempt("task-1", "msg-ws", "attempt-ws", "ws-worker", null, "batch-ws");

        TaskMsg pollMsg = new TaskMsg();
        pollMsg.setTaskId("task-1");
        pollMsg.setMessageId("msg-poll");
        TaskMsgAttempt pollAttempt = attempt("task-1", "msg-poll", "attempt-poll", "poll-worker", null, "batch-poll");

        listener.onTaskMsgsReady(task, List.of(new TaskDispatchBinding(wsMsg, wsAttempt), new TaskDispatchBinding(pollMsg, pollAttempt)));

        assertEquals(List.of("msg-ws"), webSocketAdapter.dispatchedMessageIds);
        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of(DispatchOutcomeStatus.SENT), webSocketAdapter.outcomeStatuses());
        assertEquals(List.of(DispatchOutcomeStatus.SENT), pollingAdapter.outcomeStatuses());
    }

    @Test
    void routesDispatchByCanonicalTransportHintInsteadOfAdapterProtocolLabel() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("ws-worker");
        worker.setAdapterId("websocket-v2");
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
        TaskMsgAttempt attempt = attempt("task-1", "msg-rt", "attempt-rt", "ws-worker", null, "batch-rt");

        listener.onTaskMsgsReady(task, List.of(new TaskDispatchBinding(taskMsg, attempt)));

        assertEquals(List.of("msg-rt"), realtimeAdapter.dispatchedMessageIds);
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsMissing() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("missing-transport-worker");
        workerManager.addWorker(worker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket", WorkerTransportHints.REALTIME);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, webSocketAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMessageId("msg-1");
        TaskMsgAttempt attempt = attempt("task-1", "msg-1", "attempt-1", "missing-transport-worker", null, "batch-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskMsgsReady(task, List.of(new TaskDispatchBinding(taskMsg, attempt)))
        );
        assertEquals("Cannot resolve transport binding for worker missing-transport-worker: transportHint must not be blank",
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
        TaskMsgAttempt attempt = attempt("task-1", "msg-1", "attempt-1", "unsupported-transport-worker", null, "batch-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskMsgsReady(task, List.of(new TaskDispatchBinding(taskMsg, attempt)))
        );
        assertEquals("Cannot resolve transport binding for worker unsupported-transport-worker: Unsupported worker transportHint 'grpc'; available transportHints=[polling]",
                error.getMessage());
    }

    @Test
    void nonSuccessDispatchOutcomesDoNotMutateTaskMessageStatus() {
        WorkerManager workerManager = new WorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        workerManager.addWorker(worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        pollingAdapter.overrideStatus = DispatchOutcomeStatus.BACKPRESSURE_REJECTED;
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId("task-1");
        taskMsg.setMessageId("msg-backpressure");
        TaskMsgAttempt attempt = attempt("task-1", "msg-backpressure", "attempt-backpressure", "poll-worker", null, "batch-1");

        listener.onTaskMsgsReady(task, List.of(new TaskDispatchBinding(taskMsg, attempt)));

        assertEquals(List.of("msg-backpressure"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE_REJECTED), pollingAdapter.outcomeStatuses());
        assertTrue(taskMsg.getStatus() == null || !taskMsg.getStatus().isFinal());
    }

    private static final class RecordingAdapter implements WorkerAdapter {
        private final String protocol;
        private final String transportHint;
        private final List<String> dispatchedMessageIds = new ArrayList<>();
        private final List<DispatchOutcome> outcomes = new ArrayList<>();
        private DispatchOutcomeStatus overrideStatus;

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
        public List<DispatchOutcome> dispatchTaskItems(List<TaskDispatchItem> items) {
            List<DispatchOutcome> currentOutcomes = new ArrayList<>();
            for (TaskDispatchItem item : items) {
                dispatchedMessageIds.add(item.getMessageId());
                DispatchOutcome outcome = outcome(item);
                outcomes.add(outcome);
                currentOutcomes.add(outcome);
            }
            return List.copyOf(currentOutcomes);
        }

        private DispatchOutcome outcome(TaskDispatchItem item) {
            if (overrideStatus == DispatchOutcomeStatus.BACKPRESSURE_REJECTED) {
                return DispatchOutcome.backpressureRejected(adapterId(), item, "test backpressure");
            }
            if (overrideStatus == DispatchOutcomeStatus.ENDPOINT_OFFLINE) {
                return DispatchOutcome.endpointOffline(adapterId(), item, "test offline");
            }
            return DispatchOutcome.sent(adapterId(), item);
        }

        private List<DispatchOutcomeStatus> outcomeStatuses() {
            return outcomes.stream().map(DispatchOutcome::getStatus).toList();
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

    private static TaskMsgAttempt attempt(String taskId,
                                          String messageId,
                                          String attemptId,
                                          String workerId,
                                          String workerContextId,
                                          String batchId) {
        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId, taskId, messageId, 1);
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        attempt.setBatchId(batchId);
        return attempt;
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
