package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.TransportDispatchFailureHandler;
import com.xa.mass.transport.runtime.TransportDispatchTarget;
import com.xa.mass.transport.runtime.TransportDispatchTargetResolver;
import com.xa.mass.transport.runtime.delivery.TransportDispatchEnvelopeFactory;
import com.xa.mass.transport.worker.WorkerAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    void routesDispatchByResolvedOpaqueTransportTarget() {
        RecordingAdapter webSocketAdapter = new RecordingAdapter("websocket", WorkerTransportHints.REALTIME);
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(resolver(
                target("ws-worker", webSocketAdapter, "websocket", "opaque:ws-route"),
                target("poll-worker", pollingAdapter, WorkerTransportHints.POLLING, "opaque:poll-route")
        ));

        listener.onTaskDispatchBatch(context(), List.of(
                binding("msg-ws", "attempt-ws", "ws-worker", "batch-ws"),
                binding("msg-poll", "attempt-poll", "poll-worker", "batch-poll")
        ));

        assertEquals(List.of("msg-ws"), webSocketAdapter.dispatchedMessageIds);
        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
        assertEquals(List.of("opaque:ws-route"), webSocketAdapter.dispatchedRouteKeys());
        assertEquals(List.of("opaque:poll-route"), pollingAdapter.dispatchedRouteKeys());
        assertEquals(List.of(DispatchOutcomeStatus.SENT), webSocketAdapter.outcomeStatuses());
        assertEquals(List.of(DispatchOutcomeStatus.SENT), pollingAdapter.outcomeStatuses());
    }

    @Test
    void rejectsDispatchWhenResolvedTransportTargetIsMissing() {
        TransportRoutingTaskDispatchListener listener =
                new TransportRoutingTaskDispatchListener((task, binding) -> null);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> listener.onTaskDispatchBatch(context(), List.of(
                        binding("msg-1", "attempt-1", "worker-1", "batch-1")
                ))
        );
        assertEquals("Cannot dispatch task item because transport target is missing: workerId=worker-1",
                error.getMessage());
    }

    @Test
    void retryableDispatchOutcomesTriggerCompensationForMatchedBindings() {
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        pollingAdapter.overrideStatus = DispatchOutcomeStatus.ENDPOINT_OFFLINE;
        List<List<TaskDispatchBinding>> compensated = new CopyOnWriteArrayList<>();
        TransportDispatchFailureHandler failureHandler = (task, dispatchBindings, detail) -> {
            compensated.add(List.copyOf(dispatchBindings));
            return true;
        };
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                resolver(target("poll-worker", pollingAdapter, WorkerTransportHints.POLLING, "opaque:poll-route")),
                failureHandler
        );

        listener.onTaskDispatchBatch(context(), List.of(
                binding("msg-offline", "attempt-offline", "poll-worker", "batch-1")
        ));

        assertEquals(1, compensated.size());
        assertEquals(List.of("msg-offline"),
                compensated.getFirst().stream().map(TaskDispatchBinding::messageId).toList());
    }

    @Test
    void runtimeOwnsEnvelopeIdentityAndCreatedTime() {
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        AtomicLong now = new AtomicLong(123456789L);
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                resolver(target("poll-worker", pollingAdapter, WorkerTransportHints.POLLING, "opaque:poll-route")),
                null,
                new TransportDispatchEnvelopeFactory(() -> "delivery-1", now::get)
        );

        listener.onTaskDispatchBatch(context(), List.of(
                binding("msg-1", "attempt-1", "poll-worker", "batch-1")
        ));

        assertEquals("delivery-1", pollingAdapter.outcomes.get(0).getDeliveryId());
        assertEquals(123456789L, pollingAdapter.lastEnvelopes.get(0).getCreatedAtEpochMillis());
    }

    @Test
    void dispatchesAdapterGroupsConcurrentlyWhenRuntimeExecutorIsAvailable() throws Exception {
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
                    resolver(
                            target("ws-worker", realtimeAdapter, "websocket", "opaque:ws-route"),
                            target("poll-worker", pollingAdapter, WorkerTransportHints.POLLING, "opaque:poll-route")
                    ),
                    null,
                    executor
            );

            listener.onTaskDispatchBatch(context(), List.of(
                    binding("msg-ws", "attempt-ws", "ws-worker", "batch-ws"),
                    binding("msg-poll", "attempt-poll", "poll-worker", "batch-poll")
            ));
        }

        assertEquals(2, maxConcurrentDispatches.get(), "adapter groups should fan out concurrently");
    }

    @Test
    void adapterDispatchFailureBecomesRetryableOutcomeWithoutBlockingOtherGroups() {
        ThrowingAdapter realtimeAdapter = new ThrowingAdapter("websocket", WorkerTransportHints.REALTIME);
        RecordingAdapter pollingAdapter = new RecordingAdapter(WorkerTransportHints.POLLING);
        List<List<TaskDispatchBinding>> compensated = new CopyOnWriteArrayList<>();
        TransportDispatchFailureHandler failureHandler = (task, dispatchBindings, detail) -> {
            compensated.add(List.copyOf(dispatchBindings));
            return true;
        };
        TransportRoutingTaskDispatchListener listener = new TransportRoutingTaskDispatchListener(
                resolver(
                        target("ws-worker", realtimeAdapter, "websocket", "opaque:ws-route"),
                        target("poll-worker", pollingAdapter, WorkerTransportHints.POLLING, "opaque:poll-route")
                ),
                failureHandler
        );

        listener.onTaskDispatchBatch(context(), List.of(
                binding("msg-ws", "attempt-ws", "ws-worker", "batch-ws"),
                binding("msg-poll", "attempt-poll", "poll-worker", "batch-poll")
        ));

        assertEquals(List.of("msg-poll"), pollingAdapter.dispatchedMessageIds);
        assertEquals(1, compensated.size());
        assertEquals(List.of("msg-ws"),
                compensated.getFirst().stream().map(TaskDispatchBinding::messageId).toList());
    }

    private static NamedTransportDispatchTarget target(String workerId,
                                                       WorkerAdapter adapter,
                                                       String adapterId,
                                                       String routeKey) {
        return new NamedTransportDispatchTarget(workerId, adapter, adapterId, routeKey);
    }

    private static TransportDispatchTargetResolver resolver(NamedTransportDispatchTarget... targets) {
        Map<String, TransportDispatchTarget> byWorkerId = new LinkedHashMap<>();
        for (NamedTransportDispatchTarget target : targets) {
            byWorkerId.put(target.workerId(), target.target());
        }
        return (task, binding) -> binding == null ? null : byWorkerId.get(binding.workerId());
    }

    private record NamedTransportDispatchTarget(String workerId,
                                                WorkerAdapter adapter,
                                                String adapterId,
                                                String routeKey) {

        TransportDispatchTarget target() {
            return new TransportDispatchTarget(adapter, adapterId, routeKey);
        }
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

    private static TaskDispatchBinding binding(String messageId,
                                               String attemptId,
                                               String workerId,
                                               String batchId) {
        return TaskDispatchBinding.workerLevelWithEvidence(
                "task-1",
                messageId,
                null,
                Map.of("target", workerId),
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

    private static TaskDispatchContext context() {
        return new TaskDispatchContext(
                "task-1",
                "task-name",
                "demoApp",
                "agent",
                "crawler.fetch-page",
                Map.of("_sdk", Map.of("eventCode", "crawler.fetch-page"))
        );
    }
}
