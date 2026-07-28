package com.xa.mass.workerdelivery.adapter.websocket;

import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.DELIVERED;
import static com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt.UNKNOWN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerDeliveryAdapterCore.WorkerResultAcceptance.ACCEPTED;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerDeliveryAdapterCore.WorkerResultAcceptance.ADAPTER_CLOSED;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerDeliveryAdapterCore.WorkerResultAcceptance.BUFFER_FULL;
import static com.xa.mass.workerdelivery.adapter.websocket.WorkerDeliveryAdapterCore.WorkerResultAcceptance.INVALID_OUTCOME;

import com.xa.mass.workerdelivery.adapter.application.InMemoryWorkerConnectionRegistry;
import com.xa.mass.workerdelivery.adapter.application.WorkerCommandPage;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.CommandDeliveryAttempt;
import com.xa.mass.workerdelivery.adapter.application.WorkerConnection.WorkerConnectionCloseReason;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryGatewayClient;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        InMemoryWorkerConnectionRegistry connections =
                new InMemoryWorkerConnectionRegistry();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                connections,
                10,
                100
        );
        connections.bind(
                "worker-1",
                new FakeConnection(DELIVERED)
        );
        connections.bind(
                "worker-3",
                new FakeConnection(UNKNOWN)
        );
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
        gateway.pages.add(new WorkerCommandPage(commands, "7"));

        core.dispatchOnce(executor(3));

        assertThat(gateway.appendedResults).containsExactly(List.of(
                new SeedResult(
                        "9f0d983c-8010-4d59-a6d2-e8fedb8d0059",
                        "context",
                        "3001",
                        null
                )
        ));
        assertThat(gateway.requestedCursors).containsExactly((String) null);
    }

    @Test
    void dispatchesOnePageConcurrentlyWithinTheConfiguredExecutorBound()
            throws Exception {
        FakeGateway gateway = new FakeGateway();
        InMemoryWorkerConnectionRegistry connections =
                new InMemoryWorkerConnectionRegistry();
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
            connections.bind(
                    workerId,
                    new BlockingConnection(
                            started,
                            release,
                            active,
                            maximum
                    )
            );
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
        gateway.pages.add(new WorkerCommandPage(commands, null));
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
    void serializesCursorConsumptionEvenWhenCallersRace()
            throws Exception {
        BlockingGateway gateway = new BlockingGateway();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                new InMemoryWorkerConnectionRegistry(),
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
        assertThat(gateway.requestedCursors)
                .containsExactly(null, "7");
    }

    @Test
    void retriesPendingBeforeConsumingAndCloseFlushesEveryBatch() {
        FakeGateway gateway = new FakeGateway();
        WorkerDeliveryAdapterCore core = core(
                gateway,
                new InMemoryWorkerConnectionRegistry(),
                10,
                2
        );
        for (int index = 0; index < 5; index++) {
            assertThat(core.acceptWorkerResult(result(index, "200")))
                    .isEqualTo(ACCEPTED);
        }
        assertThat(core.acceptWorkerResult(result(9, "3001")))
                .isEqualTo(INVALID_OUTCOME);
        gateway.appendFailures = 1;
        gateway.pages.add(new WorkerCommandPage(Map.of(), null));

        assertThatThrownBy(() -> core.dispatchOnce(executor(1)))
                .isInstanceOf(WorkerDeliveryAdapterException.class);
        assertThat(gateway.consumeCount.get()).isZero();

        core.close();

        assertThat(gateway.appendedResults)
                .extracting(List::size)
                .containsExactly(2, 2, 1);
        assertThat(core.acceptWorkerResult(result(8, "200")))
                .isEqualTo(ADAPTER_CLOSED);
    }

    @Test
    void boundedWorkerResultBufferRejectsExcess() {
        WorkerDeliveryAdapterCore core = core(
                new FakeGateway(),
                new InMemoryWorkerConnectionRegistry(),
                1,
                100
        );

        assertThat(core.acceptWorkerResult(result(1, "1500")))
                .isEqualTo(ACCEPTED);
        assertThat(core.acceptWorkerResult(result(2, "1500")))
                .isEqualTo(BUFFER_FULL);
    }

    private WorkerDeliveryAdapterCore core(
            WorkerDeliveryGatewayClient gateway,
            InMemoryWorkerConnectionRegistry connections,
            int capacity,
            int batchSize
    ) {
        return new WorkerDeliveryAdapterCore(
                gateway,
                new WorkerDeliveryCodec(),
                connections,
                "websocket-adapter-1",
                100,
                batchSize,
                capacity,
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

    private static final class FakeConnection
            implements WorkerConnection {

        private final CommandDeliveryAttempt attempt;

        private FakeConnection(CommandDeliveryAttempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public CommandDeliveryAttempt deliver(
                WorkerCommandEnvelope command
        ) {
            return attempt;
        }

        @Override
        public void close(WorkerConnectionCloseReason reason) {
        }
    }

    private static final class BlockingConnection
            implements WorkerConnection {

        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicInteger active;
        private final AtomicInteger maximum;

        private BlockingConnection(
                CountDownLatch started,
                CountDownLatch release,
                AtomicInteger active,
                AtomicInteger maximum
        ) {
            this.started = started;
            this.release = release;
            this.active = active;
            this.maximum = maximum;
        }

        @Override
        public CommandDeliveryAttempt deliver(
                WorkerCommandEnvelope command
        ) {
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
        }

        @Override
        public void close(WorkerConnectionCloseReason reason) {
        }
    }

    private static class FakeGateway
            implements WorkerDeliveryGatewayClient {

        final ConcurrentLinkedQueue<WorkerCommandPage> pages =
                new ConcurrentLinkedQueue<>();
        final List<String> requestedCursors =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final List<List<SeedResult>> appendedResults =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger consumeCount = new AtomicInteger();
        volatile int appendFailures;

        @Override
        public WorkerCommandPage consumeWorkerCommands(
                String endpointManagerId,
                String cursor,
                int scanCount
        ) {
            consumeCount.incrementAndGet();
            requestedCursors.add(cursor);
            WorkerCommandPage page = pages.poll();
            return page == null
                    ? new WorkerCommandPage(Map.of(), null)
                    : page;
        }

        @Override
        public synchronized void appendResults(
                String endpointManagerId,
                List<SeedResult> results
        ) {
            if (appendFailures > 0) {
                appendFailures--;
                throw new WorkerDeliveryAdapterException(
                        "gateway unavailable"
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
        public WorkerCommandPage consumeWorkerCommands(
                String endpointManagerId,
                String cursor,
                int scanCount
        ) {
            int current = concurrentConsumes.incrementAndGet();
            maxConcurrentConsumes.accumulateAndGet(current, Math::max);
            try {
                if (firstConsumeStarted.getCount() > 0) {
                    firstConsumeStarted.countDown();
                    releaseFirstConsume.await(2, TimeUnit.SECONDS);
                    requestedCursors.add(cursor);
                    return new WorkerCommandPage(Map.of(), "7");
                }
                requestedCursors.add(cursor);
                return new WorkerCommandPage(Map.of(), null);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new WorkerDeliveryAdapterException(
                        "interrupted",
                        error
                );
            } finally {
                concurrentConsumes.decrementAndGet();
            }
        }
    }
}
