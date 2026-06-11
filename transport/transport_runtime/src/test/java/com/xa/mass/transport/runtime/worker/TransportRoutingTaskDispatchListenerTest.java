package com.xa.mass.transport.runtime.worker;


import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.storage.memory.InMemoryWorkerDeclarationStore;
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
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import com.xa.mass.transport.runtime.delivery.TransportDispatchEnvelopeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRoutingTaskDispatchListenerTest {
    private static final String TEST_WORKER_GROUP_ID = "test-workers";

    @Test
    void routesDispatchByWorkerOnlineStrategy() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker webSocketWorker = new Worker();
        webSocketWorker.setWorkerId("ws-worker");
        webSocketWorker.setAdapterId("websocket");
        webSocketWorker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        addWorker(workerManager, webSocketWorker);

        Worker pollingWorker = new Worker();
        pollingWorker.setWorkerId("poll-worker");
        pollingWorker.setOnlineStrategy(WorkerTransportHints.POLLING);
        addWorker(workerManager, pollingWorker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket", WorkerTransportHints.REALTIME);
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, webSocketAdapter, pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-ws", "attempt-ws", "ws-worker", "batch-ws"),
                binding("task-1", "msg-poll", "attempt-poll", "poll-worker", "batch-poll")
        ));

        assertEquals(List.of("msg-ws"), webSocketAdapter.dispatchedMessageIds);
        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of(routeKey("ws-worker")), webSocketAdapter.dispatchedRouteKeys());
        assertEquals(List.of(routeKey("poll-worker")), pollingAdapter.dispatchedRouteKeys());
        assertEquals(List.of(DispatchOutcomeStatus.SENT), webSocketAdapter.outcomeStatuses());
        assertEquals(List.of(DispatchOutcomeStatus.SENT), pollingAdapter.outcomeStatuses());
    }

    @Test
    void routesDispatchByCanonicalTransportHintInsteadOfAdapterProtocolLabel() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("ws-worker");
        worker.setAdapterId("websocket-v2");
        worker.setOnlineStrategy(WorkerTransportHints.REALTIME);
        addWorker(workerManager, worker);

        RecordingAdapter realtimeAdapter = new RecordingAdapter("websocket-v2", WorkerTransportHints.REALTIME);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, realtimeAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-rt", "attempt-rt", "ws-worker", "batch-rt")
        ));

        assertEquals(List.of("msg-rt"), realtimeAdapter.dispatchedMessageIds);
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsMissing() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("missing-transport-worker");
        addWorker(workerManager, worker);

        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket", WorkerTransportHints.REALTIME);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, webSocketAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskDispatchBatch(taskContext(task), List.of(
                        binding("task-1", "msg-1", "attempt-1", "missing-transport-worker", "batch-1")
                ))
        );
        assertEquals("Cannot resolve transport binding for worker missing-transport-worker: transportHint must not be blank",
                error.getMessage());
    }

    @Test
    void rejectsDispatchWhenWorkerTransportIsUnsupported() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("unsupported-transport-worker");
        worker.setOnlineStrategy("grpc");
        addWorker(workerManager, worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskDispatchBatch(taskContext(task), List.of(
                        binding("task-1", "msg-1", "attempt-1", "unsupported-transport-worker", "batch-1")
                ))
        );
        assertEquals("Cannot resolve transport binding for worker unsupported-transport-worker: Unsupported worker transportHint 'grpc'; available transportHints=[polling]",
                error.getMessage());
    }

    @Test
    void nonSuccessDispatchOutcomesDoNotMutateTaskMessageStatus() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        addWorker(workerManager, worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        pollingAdapter.overrideStatus = DispatchOutcomeStatus.BACKPRESSURE_REJECTED;
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter)
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-backpressure", "attempt-backpressure", "poll-worker", "batch-1")
        ));

        assertEquals(List.of("msg-backpressure"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE_REJECTED), pollingAdapter.outcomeStatuses());
    }

    @Test
    void retryableDispatchOutcomesTriggerCompensationForMatchedBindings() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        addWorker(workerManager, worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        pollingAdapter.overrideStatus = DispatchOutcomeStatus.ENDPOINT_OFFLINE;
        List<List<TaskDispatchBinding>> compensated = new CopyOnWriteArrayList<>();
        TransportDispatchFailureHandler failureHandler = (task, dispatchBindings, detail) -> {
            compensated.add(List.copyOf(dispatchBindings));
            return true;
        };
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter),
                failureHandler
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-offline", "attempt-offline", "poll-worker", "batch-1")
        ));

        assertEquals(1, compensated.size());
        assertEquals(List.of("msg-offline"),
                compensated.getFirst().stream().map(TaskDispatchBinding::messageId).toList());
    }

    @Test
    void runtimeOwnsEnvelopeIdentityAndCreatedTime() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        addWorker(workerManager, worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        AtomicLong now = new AtomicLong(123456789L);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, pollingAdapter),
                null,
                new TransportDispatchEnvelopeFactory(() -> "delivery-1", now::get)
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-1", "attempt-1", "poll-worker", "batch-1")
        ));

        assertEquals("delivery-1", pollingAdapter.outcomes.get(0).getDeliveryId());
        assertEquals(123456789L, pollingAdapter.lastEnvelopes.get(0).getCreatedAtEpochMillis());
    }

    @Test
    void routeKeyComesFromTransportBindingResolverInsteadOfBeingHardcodedToWorkerId() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());

        Worker worker = new Worker();
        worker.setWorkerId("poll-worker");
        worker.setOnlineStrategy(WorkerTransportHints.POLLING);
        addWorker(workerManager, worker);

        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportBinding binding = TransportBinding.builder(pollingAdapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> "endpoint:" + routeContext.batchId())
                .build();
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                new TransportRuntimeRegistry(
                        workerManager,
                        report -> true,
                        new NoopWorkerSystemEventChannel(),
                        new InMemoryWorkerPresenceStore(),
                        List.of(binding)
                )
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-1", "attempt-1", "poll-worker", "batch-9")
        ));

        assertEquals("endpoint:batch-9", pollingAdapter.lastEnvelopes.get(0).getRouteKey());
    }

    @Test
    void batchReusesResolvedDispatchTargetForRepeatedWorkerBindings() {
        CountingWorkerResourceRuntime workerResourceRuntime =
                new CountingWorkerResourceRuntime(worker("poll-worker", null, WorkerTransportHints.POLLING));
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerResourceRuntime,
                new TransportRuntimeRegistry(
                        workerResourceRuntime,
                        report -> true,
                        new NoopWorkerSystemEventChannel(),
                        new InMemoryWorkerPresenceStore(),
                        List.of(canonicalRouteBinding(pollingAdapter))
                )
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-1", "attempt-1", "poll-worker", "batch-1"),
                binding("task-1", "msg-2", "attempt-2", "poll-worker", "batch-2")
        ));

        assertEquals(1, workerResourceRuntime.lookupCount(), "worker lookup should be reused within one dispatch batch");
        assertEquals(List.of("msg-1", "msg-2"), pollingAdapter.dispatchedMessageIds);
    }

    @Test
    void dispatchesAdapterGroupsConcurrentlyWhenRuntimeExecutorIsAvailable() throws Exception {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());
        addWorker(workerManager, worker("ws-worker", "websocket", WorkerTransportHints.REALTIME));
        addWorker(workerManager, worker("poll-worker", null, WorkerTransportHints.POLLING));

        AtomicInteger activeDispatches = new AtomicInteger();
        AtomicInteger maxConcurrentDispatches = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(2);
        BlockingRecordingAdapter realtimeAdapter = new BlockingRecordingAdapter(
                "websocket",
                WorkerTransportHints.REALTIME,
                started,
                activeDispatches,
                maxConcurrentDispatches
        );
        BlockingRecordingAdapter pollingAdapter = new BlockingRecordingAdapter(
                WorkerTransportHints.POLLING,
                WorkerTransportHints.POLLING,
                started,
                activeDispatches,
                maxConcurrentDispatches
        );

        try (VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("dispatch-fanout-test-", 8)) {
            TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                    workerManager,
                    runtimeRegistry(workerManager, realtimeAdapter, pollingAdapter),
                    null,
                    executor
            );

            Task task = new Task();
            task.setTid("task-1");

            listener.onTaskDispatchBatch(taskContext(task), List.of(
                    binding("task-1", "msg-ws", "attempt-ws", "ws-worker", "batch-ws"),
                    binding("task-1", "msg-poll", "attempt-poll", "poll-worker", "batch-poll")
            ));
        }

        assertEquals(2, maxConcurrentDispatches.get(), "adapter groups should fan out concurrently");
    }

    @Test
    void adapterDispatchFailureBecomesRetryableOutcomeWithoutBlockingOtherGroups() {
        WorkerManager workerManager = new WorkerManager(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());
        addWorker(workerManager, worker("ws-worker", "websocket", WorkerTransportHints.REALTIME));
        addWorker(workerManager, worker("poll-worker", null, WorkerTransportHints.POLLING));

        ThrowingAdapter realtimeAdapter = new ThrowingAdapter("websocket", WorkerTransportHints.REALTIME);
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        List<List<TaskDispatchBinding>> compensated = new CopyOnWriteArrayList<>();
        TransportDispatchFailureHandler failureHandler = (task, dispatchBindings, detail) -> {
            compensated.add(List.copyOf(dispatchBindings));
            return true;
        };
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                workerManager,
                runtimeRegistry(workerManager, realtimeAdapter, pollingAdapter),
                failureHandler
        );

        Task task = new Task();
        task.setTid("task-1");

        listener.onTaskDispatchBatch(taskContext(task), List.of(
                binding("task-1", "msg-ws", "attempt-ws", "ws-worker", "batch-ws"),
                binding("task-1", "msg-poll", "attempt-poll", "poll-worker", "batch-poll")
        ));

        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
        assertEquals(1, compensated.size());
        assertEquals(List.of("msg-ws"),
                compensated.getFirst().stream().map(TaskDispatchBinding::messageId).toList());
    }

    private static class RecordingAdapter implements WorkerAdapter {
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

        private List<String> dispatchedRouteKeys() {
            return lastEnvelopes.stream().map(TransportDispatchEnvelope::getRouteKey).toList();
        }
    }

    private static final class BlockingRecordingAdapter extends RecordingAdapter {
        private final CountDownLatch started;
        private final AtomicInteger activeDispatches;
        private final AtomicInteger maxConcurrentDispatches;

        private BlockingRecordingAdapter(String protocol,
                                         String transportHint,
                                         CountDownLatch started,
                                         AtomicInteger activeDispatches,
                                         AtomicInteger maxConcurrentDispatches) {
            super(protocol, transportHint);
            this.started = started;
            this.activeDispatches = activeDispatches;
            this.maxConcurrentDispatches = maxConcurrentDispatches;
        }

        @Override
        public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
            int concurrent = activeDispatches.incrementAndGet();
            maxConcurrentDispatches.accumulateAndGet(concurrent, Math::max);
            started.countDown();
            try {
                assertTrue(started.await(1, TimeUnit.SECONDS), "both adapter groups should start before dispatch completes");
                return super.dispatchEnvelopes(envelopes);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("dispatch interrupted", e);
            } finally {
                activeDispatches.decrementAndGet();
            }
        }
    }

    private static final class ThrowingAdapter extends RecordingAdapter {
        private ThrowingAdapter(String protocol, String transportHint) {
            super(protocol, transportHint);
        }

        @Override
        public List<DispatchOutcome> dispatchEnvelopes(List<TransportDispatchEnvelope> envelopes) {
            throw new IllegalStateException("adapter dispatch failed");
        }
    }

    private static TransportRuntimeRegistry runtimeRegistry(WorkerManager workerManager, RecordingAdapter... adapters) {
        return new TransportRuntimeRegistry(
                workerManager,
                report -> true,
                new NoopWorkerSystemEventChannel(),
                new InMemoryWorkerPresenceStore(),
                Arrays.stream(adapters)
                        .map(TransportRoutingTaskDispatchListenerTest::canonicalRouteBinding)
                        .toList()
        );
    }

    private static Worker worker(String workerId, String adapterId, String transportHint) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(TEST_WORKER_GROUP_ID);
        worker.setAdapterId(adapterId);
        worker.setOnlineStrategy(transportHint);
        return worker;
    }

    private static void addWorker(WorkerManager workerManager, Worker worker) {
        workerManager.addWorker(new WorkerResourceRecord(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId() == null ? TEST_WORKER_GROUP_ID : worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        ));
    }

    private static TransportBinding canonicalRouteBinding(WorkerAdapter adapter) {
        return TransportBinding.builder(adapter)
                .routeKeyResolver((dispatchBinding, routeContext) -> routeKey(routeContext.workerId()))
                .build();
    }

    private static TaskDispatchBinding binding(String taskId,
                                               String messageId,
                                               String attemptId,
                                               String workerId,
                                               String batchId) {
        return TaskDispatchBinding.workerLevelWithEvidence(
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
                batchId,
                TEST_WORKER_GROUP_ID,
                null,
                null,
                "test-fixture"
        );
    }

    private static String routeKey(String workerId) {
        return "route:" + workerId;
    }

    private static TaskDispatchContext taskContext(Task task) {
        return new TaskDispatchContext(
                task.getTid(),
                task.getTaskName(),
                task.getProject(),
                task.getUser() != null ? task.getUser().getUserId() : null,
                "crawler.fetch-page",
                Map.of("_sdk", Map.of("eventCode", "crawler.fetch-page"))
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

    private static final class CountingWorkerResourceRuntime extends WorkerManager {
        private int lookupCount;

        private CountingWorkerResourceRuntime(Worker worker) {
            super(new InMemoryWorkerDeclarationStore(), new InMemoryWorkerRegistry());
            TransportRoutingTaskDispatchListenerTest.addWorker(this, worker);
        }

        @Override
        public java.util.Optional<WorkerResourceRecord> worker(String workerId) {
            lookupCount++;
            return super.worker(workerId);
        }

        private int lookupCount() {
            return lookupCount;
        }
    }
}
