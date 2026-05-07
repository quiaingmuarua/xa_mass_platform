package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;
import com.xa.mass.transport.worker.WorkerAdapter;
import com.xa.mass.transport.runtime.TransportBinding;
import com.xa.mass.transport.runtime.TransportDispatchFailureHandler;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.TransportDispatchEnvelopeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRoutingTaskMsgDispatchListenerTest {

    @Test
    void routesDispatchByWorkerOnlineStrategy() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

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

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-ws", "attempt-ws", "ws-worker", null, "batch-ws"),
                binding("task-1", "msg-poll", "attempt-poll", "poll-worker", null, "batch-poll")
        ));

        assertEquals(List.of("msg-ws"), webSocketAdapter.dispatchedMessageIds);
        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of(DispatchOutcomeStatus.SENT), webSocketAdapter.outcomeStatuses());
        assertEquals(List.of(DispatchOutcomeStatus.SENT), pollingAdapter.outcomeStatuses());
    }

    @Test
    void routesDispatchByCanonicalTransportHintInsteadOfAdapterProtocolLabel() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

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

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-rt", "attempt-rt", "ws-worker", null, "batch-rt")
        ));

        assertEquals(List.of("msg-rt"), realtimeAdapter.dispatchedMessageIds);
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsMissing() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

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

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskDispatchBatch(taskContext(task), List.of(
                        binding("task-1", "msg-1", "attempt-1", "missing-transport-worker", null, "batch-1")
                ))
        );
        assertEquals("Cannot resolve transport binding for worker missing-transport-worker: transportHint must not be blank",
                error.getMessage());
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsUnsupported() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

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

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskDispatchBatch(taskContext(task), List.of(
                        binding("task-1", "msg-1", "attempt-1", "unsupported-transport-worker", null, "batch-1")
                ))
        );
        assertEquals("Cannot resolve transport binding for worker unsupported-transport-worker: Unsupported worker transportHint 'grpc'; available transportHints=[polling]",
                error.getMessage());
    }

    @Test
    void nonSuccessDispatchOutcomesDoNotMutateTaskMessageStatus() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

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

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-backpressure", "attempt-backpressure", "poll-worker", null, "batch-1")
        ));

        assertEquals(List.of("msg-backpressure"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE_REJECTED), pollingAdapter.outcomeStatuses());
    }

    @Test
    void retryableDispatchOutcomesTriggerCompensationForMatchedBindings() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        workerManager.addWorker(worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        pollingAdapter.overrideStatus = DispatchOutcomeStatus.ENDPOINT_OFFLINE;
        List<List<TaskDispatchBinding>> compensated = new CopyOnWriteArrayList<>();
        TransportDispatchFailureHandler failureHandler = (task, dispatchBindings, detail) -> {
            compensated.add(List.copyOf(dispatchBindings));
            return true;
        };
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter),
                failureHandler
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-offline", "attempt-offline", "poll-worker", null, "batch-1")
        ));

        assertEquals(1, compensated.size());
        assertEquals(List.of("msg-offline"),
                compensated.getFirst().stream().map(TaskDispatchBinding::messageId).toList());
    }

    @Test
    void runtimeOwnsEnvelopeIdentityAndCreatedTime() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        workerManager.addWorker(worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        AtomicLong now = new AtomicLong(123456789L);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter),
                null,
                new TransportDispatchEnvelopeFactory(() -> "delivery-1", now::get)
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-1", "attempt-1", "poll-worker", null, "batch-1")
        ));

        assertEquals("delivery-1", pollingAdapter.outcomes.get(0).getDeliveryId());
        assertEquals(123456789L, pollingAdapter.lastEnvelopes.get(0).getCreatedAtEpochMillis());
    }

    @Test
    void routeKeyComesFromTransportBindingResolverInsteadOfBeingHardcodedToWorkerId() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerStorage());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        workerManager.addWorker(worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportBinding binding = TransportBinding.builder(pollingAdapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> "endpoint:" + routeContext.batchId())
                .build();
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerManager,
                new TransportRuntimeRegistry(
                        workerManager,
                        report -> true,
                        new NoopWorkerSystemEventChannel(),
                        List.of(binding)
                )
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-1", "attempt-1", "poll-worker", null, "batch-9")
        ));

        assertEquals("endpoint:batch-9", pollingAdapter.lastEnvelopes.get(0).getRouteKey());
    }

    @Test
    void batchReusesResolvedDispatchTargetForRepeatedWorkerBindings() {
        CountingWorkerLookupStore workerLookupStore = new CountingWorkerLookupStore(worker("poll-worker", null, WorkerTransportHints.POLLING));
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskMsgDispatchListener listener = new TransportRoutingTaskMsgDispatchListener(
                workerLookupStore,
                new TransportRuntimeRegistry(
                        workerLookupStore,
                        report -> true,
                        new NoopWorkerSystemEventChannel(),
                        List.of(workerIdRouteBinding(pollingAdapter))
                )
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-1", "attempt-1", "poll-worker", null, "batch-1"),
                binding("task-1", "msg-2", "attempt-2", "poll-worker", null, "batch-2")
        ));

        assertEquals(1, workerLookupStore.lookupCount(), "worker lookup should be reused within one dispatch batch");
        assertEquals(List.of("msg-1", "msg-2"), pollingAdapter.dispatchedMessageIds);
    }

    private static final class RecordingAdapter implements WorkerAdapter {
        private final String protocol;
        private final String transportHint;
        private final List<String> dispatchedMessageIds = new ArrayList<>();
        private final List<DispatchOutcome> outcomes = new ArrayList<>();
        private final List<TransportDispatchEnvelope> lastEnvelopes = new ArrayList<>();
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
        public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
            lastEnvelopes.clear();
            lastEnvelopes.addAll(envelopes);
            List<DispatchOutcome> currentOutcomes = new ArrayList<>();
            for (TransportDispatchEnvelope envelope : envelopes) {
                TransportPacket packet = envelope.getPacket();
                dispatchedMessageIds.add(packet.messageId());
                DispatchOutcome outcome = outcome(envelope);
                outcomes.add(outcome);
                currentOutcomes.add(outcome);
            }
            return List.copyOf(currentOutcomes);
        }

        private DispatchOutcome outcome(TransportDispatchEnvelope envelope) {
            if (overrideStatus == DispatchOutcomeStatus.BACKPRESSURE_REJECTED) {
                return DispatchOutcome.backpressureRejected(adapterId(), envelope, "test backpressure");
            }
            if (overrideStatus == DispatchOutcomeStatus.ENDPOINT_OFFLINE) {
                return DispatchOutcome.endpointOffline(adapterId(), envelope, "test offline");
            }
            return DispatchOutcome.sent(adapterId(), envelope);
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
                        .map(TransportRoutingTaskMsgDispatchListenerTest::workerIdRouteBinding)
                        .toList()
        );
    }

    private static Worker worker(String workerId, String adapterId, String transportHint) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setAdapterId(adapterId);
        worker.setOnlineStrategy(transportHint);
        return worker;
    }

    private static TransportBinding workerIdRouteBinding(WorkerAdapter adapter) {
        return TransportBinding.builder(adapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> {
                    if (routeContext != null && routeContext.workerId() != null && !routeContext.workerId().isBlank()) {
                        return routeContext.workerId();
                    }
                    return dispatchBinding != null ? dispatchBinding.workerId() : null;
                })
                .build();
    }

    private static TaskDispatchBinding binding(String taskId,
                                               String messageId,
                                               String attemptId,
                                               String workerId,
                                               String workerContextId,
                                               String batchId) {
        return new TaskDispatchBinding(
                taskId,
                messageId,
                null,
                java.util.Map.of("target", workerId),
                null,
                0,
                attemptId,
                1,
                null,
                workerId,
                workerContextId,
                batchId
        );
    }

    private static TaskDispatchContext taskContext(Task task) {
        return TaskDispatchContext.from(task);
    }

    private static final class NoopWorkerSystemEventChannel implements com.xa.mass.transport.channel.WorkerSystemEventChannel {
        @Override
        public void publishWorkerOnline(String workerId, String reason, String traceId) {
        }

        @Override
        public void publishWorkerOffline(String workerId, String reason, String traceId) {
        }
    }

    private static final class CountingWorkerLookupStore implements com.xa.mass.storage.api.WorkerLookupStore {
        private final Worker worker;
        private int lookupCount;

        private CountingWorkerLookupStore(Worker worker) {
            this.worker = worker;
        }

        @Override
        public Worker findWorker(String workerId) {
            lookupCount++;
            if (worker != null && worker.getWorkerId() != null && worker.getWorkerId().equals(workerId)) {
                return worker;
            }
            return null;
        }

        private int lookupCount() {
            return lookupCount;
        }
    }
}
