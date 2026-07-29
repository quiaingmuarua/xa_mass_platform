package com.xa.mass.workerdelivery.adapter.dispatch;

import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.REJECTED_BEFORE_SEND;
import static com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt.UNKNOWN;
import static com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer.OfferStatus.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer.OfferStatus.CLOSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.adapter.message.BoundedWorkerResultBuffer;
import com.xa.mass.workerdelivery.adapter.dispatch.WorkerCommandDelivery.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterCoreTest {

    private static final String COMMAND_ID =
            "a5e9e10d-f78b-469e-93ab-864b49c189c1";
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void deliversConnectedWorkersAndReportsOnlyKnownPreSendRejection() {
        FakeGateway gateway = new FakeGateway();
        FakeConnectionRegistry connections =
                new FakeConnectionRegistry();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                connections,
                10,
                100
        );
        connections.respond("worker-1", ignored -> DELIVERED);
        connections.respond("worker-3", ignored -> UNKNOWN);
        Map<String, WorkerCommandEnvelope> commands =
                new LinkedHashMap<>();
        commands.put("worker-1", command(COMMAND_ID, "worker-1", 2_000));
        commands.put(
                "worker-2",
                command(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "worker-2",
                        2_000
                )
        );
        commands.put(
                "worker-3",
                command(
                        "66f60ac8-e68f-4783-90e3-13b20a54ca13",
                        "worker-3",
                        2_000
                )
        );
        gateway.batches.add(commands);

        core.dispatchOnce(executor(3));

        assertThat(gateway.appendedResults).containsExactly(List.of(
                new SeedResult(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "context",
                        "3001",
                        null
                )
        ));
        assertThat(gateway.requestedLimits).containsExactly(100);
    }

    @Test
    void dispatchesOneBatchConcurrentlyWithinTheConfiguredExecutorBound()
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        FakeConnectionRegistry connections =
                new FakeConnectionRegistry();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                connections,
                10,
                100
        );
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Map<String, WorkerCommandEnvelope> commands =
                new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) {
            String workerId = "worker-" + index;
            connections.respond(workerId, ignored -> {
                int current = active.incrementAndGet();
                maximum.accumulateAndGet(current, Math::max);
                started.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                    return DELIVERED;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return UNKNOWN;
                } finally {
                    active.decrementAndGet();
                }
            });
            commands.put(
                    workerId,
                    command(
                            "00000000-0000-4000-8000-00000000000"
                                    + index,
                            workerId,
                            2_000
                    )
            );
        }
        gateway.batches.add(commands);
        ExecutorService delivery = executor(3);
        Thread round = Thread.ofPlatform().start(() ->
                core.dispatchOnce(delivery)
        );

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(maximum.get()).isEqualTo(3);
        release.countDown();
        round.join(2_000);

        assertThat(round.isAlive()).isFalse();
        assertThat(maximum.get()).isLessThanOrEqualTo(3);
    }

    @Test
    void interruptedDeliveryWaitUsesTheOwnerErrorCode()
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        FakeConnectionRegistry connections =
                new FakeConnectionRegistry();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                connections,
                10,
                100
        );
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        connections.respond("worker-1", ignored -> {
            deliveryStarted.countDown();
            try {
                releaseDelivery.await(2, TimeUnit.SECONDS);
                return DELIVERED;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return UNKNOWN;
            }
        });
        gateway.batches.add(Map.of(
                "worker-1",
                command(COMMAND_ID, "worker-1", 2_000)
        ));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread round = Thread.ofPlatform().start(() -> {
            try {
                core.dispatchOnce(executor(1));
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        assertThat(deliveryStarted.await(2, TimeUnit.SECONDS)).isTrue();
        round.interrupt();
        round.join(2_000);
        releaseDelivery.countDown();

        assertThat(failure.get())
                .isInstanceOf(WorkerDeliveryAdapterException.class);
        assertThat(
                ((WorkerDeliveryAdapterException) failure.get())
                        .errorCode()
        ).isEqualTo(
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED
        );
        assertThat(
                ((WorkerDeliveryAdapterException) failure.get())
                        .operation()
        ).isEqualTo("adapter.dispatchCommands");
    }

    @Test
    void serializesBatchConsumptionEvenWhenCallersRace()
            throws Exception {
        BlockingGateway gateway = new BlockingGateway();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                new FakeConnectionRegistry(),
                10,
                100
        );
        ExecutorService delivery = executor(2);
        Thread first = Thread.ofPlatform().start(() ->
                core.dispatchOnce(delivery)
        );
        assertThat(gateway.firstConsumeStarted.await(
                2,
                TimeUnit.SECONDS
        )).isTrue();
        Thread second = Thread.ofPlatform().start(() ->
                core.dispatchOnce(delivery)
        );

        Thread.sleep(50);
        assertThat(gateway.maxConcurrentConsumes.get()).isEqualTo(1);
        gateway.releaseFirstConsume.countDown();
        first.join(2_000);
        second.join(2_000);

        assertThat(gateway.maxConcurrentConsumes.get()).isEqualTo(1);
        assertThat(gateway.requestedLimits)
                .containsExactly(100, 100);
    }

    @Test
    void retriesPendingBeforeConsumingAndCloseFlushesEveryBatch() {
        FakeGateway gateway = new FakeGateway();
        BoundedWorkerResultBuffer resultBuffer =
                new BoundedWorkerResultBuffer(10);
        WorkerDeliveryAdapterCore core = core(
                gateway,
                new FakeConnectionRegistry(),
                resultBuffer,
                2
        );
        for (int index = 0; index < 5; index++) {
            assertThat(resultBuffer.offer(result(index, "200")))
                    .isEqualTo(ACCEPTED);
        }
        gateway.appendFailures = 1;
        gateway.batches.add(Map.of());

        assertThatThrownBy(() -> core.dispatchOnce(executor(1)))
                .isInstanceOf(WorkerDeliveryAdapterException.class);
        assertThat(gateway.consumeCount.get()).isZero();

        core.close();

        assertThat(gateway.appendedResults)
                .extracting(List::size)
                .containsExactly(2, 2, 1);
        assertThat(resultBuffer.offer(result(8, "200")))
                .isEqualTo(CLOSED);
    }

    private WorkerDeliveryAdapterCore core(
            WorkerDeliveryGatewayClient gateway,
            WorkerCommandDelivery connections,
            int capacity,
            int batchSize
    ) {
        return core(
                gateway,
                connections,
                new BoundedWorkerResultBuffer(capacity),
                batchSize
        );
    }

    private WorkerDeliveryAdapterCore core(
            WorkerDeliveryGatewayClient gateway,
            WorkerCommandDelivery connections,
            BoundedWorkerResultBuffer resultBuffer,
            int batchSize
    ) {
        return new WorkerDeliveryAdapterCore(
                gateway,
                new WorkerDeliveryCodec(),
                connections,
                "websocket-adapter-1",
                100,
                batchSize,
                resultBuffer,
                () -> 1_000L
        );
    }

    private ExecutorService executor(int size) {
        ExecutorService executor = Executors.newFixedThreadPool(size);
        executors.add(executor);
        return executor;
    }

    private static WorkerCommandEnvelope command(
            String commandId,
            String workerId,
            long deadline
    ) {
        return new WorkerCommandEnvelope(
                commandId,
                WorkerMessageType.TASK_ITEM,
                deadline,
                "{\"opaqueDeliveryItem\":\"item\","
                        + "\"opaqueResultContext\":\"context\","
                        + "\"workerId\":\"" + workerId + "\"}"
        );
    }

    private static SeedResult result(int index, String outcomeCode) {
        return new SeedResult(
                "00000000-0000-4000-8000-00000000000" + index,
                "context-" + index,
                outcomeCode,
                "200".equals(outcomeCode) ? "null" : null
        );
    }

    private static final class FakeConnectionRegistry
            implements WorkerCommandDelivery {

        private final Map<String, DeliveryBehavior> deliveries =
                new HashMap<>();

        private void respond(
                String workerId,
                DeliveryBehavior behavior
        ) {
            deliveries.put(workerId, behavior);
        }

        @Override
        public CommandDeliveryAttempt deliver(
                String workerId,
                WorkerCommandEnvelope command
        ) {
            DeliveryBehavior behavior = deliveries.get(workerId);
            return behavior == null
                    ? REJECTED_BEFORE_SEND
                    : behavior.deliver(command);
        }

    }

    @FunctionalInterface
    private interface DeliveryBehavior {

        CommandDeliveryAttempt deliver(WorkerCommandEnvelope command);
    }

    private static class FakeGateway
            implements WorkerDeliveryGatewayClient {

        final ConcurrentLinkedQueue<
                Map<String, WorkerCommandEnvelope>
                > batches =
                new ConcurrentLinkedQueue<>();
        final List<Integer> requestedLimits =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final List<List<SeedResult>> appendedResults =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger consumeCount = new AtomicInteger();
        volatile int appendFailures;

        @Override
        public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            consumeCount.incrementAndGet();
            requestedLimits.add(limit);
            Map<String, WorkerCommandEnvelope> batch = batches.poll();
            return batch == null ? Map.of() : batch;
        }

        @Override
        public synchronized void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            if (appendFailures > 0) {
                appendFailures--;
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                        "gateway.appendResults",
                        "gateway unavailable",
                        null
                );
            }
            appendedResults.add(List.copyOf(results));
        }
    }

    private static final class BlockingGateway extends FakeGateway {

        private final CountDownLatch firstConsumeStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseFirstConsume =
                new CountDownLatch(1);
        private final AtomicInteger concurrentConsumes =
                new AtomicInteger();
        private final AtomicInteger maxConcurrentConsumes =
                new AtomicInteger();

        @Override
        public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
                String endpointManagerId,
                int limit
        ) {
            int current = concurrentConsumes.incrementAndGet();
            maxConcurrentConsumes.accumulateAndGet(current, Math::max);
            try {
                if (firstConsumeStarted.getCount() > 0) {
                    firstConsumeStarted.countDown();
                    releaseFirstConsume.await(2, TimeUnit.SECONDS);
                    requestedLimits.add(limit);
                    return Map.of();
                }
                requestedLimits.add(limit);
                return Map.of();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.GATEWAY_UNAVAILABLE,
                        "gateway.consumeCommands",
                        "interrupted",
                        error
                );
            } finally {
                concurrentConsumes.decrementAndGet();
            }
        }
    }
}
