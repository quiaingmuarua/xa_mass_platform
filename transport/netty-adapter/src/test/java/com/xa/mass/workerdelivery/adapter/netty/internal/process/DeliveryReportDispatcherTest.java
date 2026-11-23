package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.ADAPTER_WORKER_CONNECTION_CHANGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer;
import com.xa.mass.workerdelivery.adapter.support.ScriptedHttpServer.Response;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryReportDispatcherTest {

    @Test
    void oneThreadRotatesAcrossHomogeneousLanes() throws Exception {
        List<List<DeliveryReport>> batches = new CopyOnWriteArrayList<>();
        CountDownLatch submitted = new CountDownLatch(4);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            batches.add(List.copyOf(invocation.getArgument(1)));
            submitted.countDown();
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(10, remoteApi);
        dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task-1"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task-2"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.SERVER, "server"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.SYSTEM, "system"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.KERNEL, "kernel"));

        dispatcher.start();
        assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(dispatcher.thread().isDaemon()).isTrue();
        assertThat(dispatcher.thread().getName()).isEqualTo(
                "worker-delivery-adapter-1-delivery-report"
        );
        assertThat(batches).extracting(batch -> batch.get(0).dst())
                .containsExactly(
                        DeliveryEndpoint.TASK,
                        DeliveryEndpoint.SERVER,
                        DeliveryEndpoint.SYSTEM,
                        DeliveryEndpoint.KERNEL
                );
        assertThat(batches.get(0)).hasSize(2);
        assertThat(batches).allSatisfy(batch -> assertThat(batch)
                .extracting(DeliveryReport::dst)
                .containsOnly(batch.get(0).dst()));
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryEndpoint.class,
            names = {"TASK", "SERVER", "SYSTEM", "KERNEL"})
    void drainsReportsInBatchesOfOneHundred(DeliveryEndpoint destination)
            throws Exception {
        List<Integer> sizes = new CopyOnWriteArrayList<>();
        CountDownLatch submitted = new CountDownLatch(3);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            sizes.add(invocation.<List<?>>getArgument(1).size());
            submitted.countDown();
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(300, remoteApi);
        for (int index = 0; index < 205; index++) {
            assertThat(dispatcher.tryDispatch(report(
                    destination,
                    "report-" + index
            ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        }

        dispatcher.start();
        assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(sizes).containsExactly(100, 100, 5);
    }

    @ParameterizedTest(name = "10,020 disconnect observations with lane capacity {0}")
    @ValueSource(ints = {20_000, 1_000})
    void disconnectBurstPreservesAdmittedEvidenceAndOtherLaneFairnessWithinBudget(int capacity)
            throws Exception {
        int originalCount = 10_020;
        int firstBatchSize = 100;
        int expectedKernelCount = firstBatchSize + Math.min(capacity, originalCount - firstBatchSize);
        List<DeliveryReport> originals = IntStream.range(0, originalCount)
                .mapToObj(index -> DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER, "adapter-1", DeliveryEndpoint.KERNEL,
                        ADAPTER_WORKER_CONNECTION_CHANGED, "", Jsons.toJson(Map.of(
                                "workerId", "worker-" + index,
                                "state", "DISCONNECTED",
                                "observedAtMillis", 1_700_000_000_000L + index)),
                        "worker-serviceability-evidence:v1"))
                .toList();
        List<List<DeliveryReport>> batches = new CopyOnWriteArrayList<>();
        CountDownLatch firstAppendEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstAppend = new CountDownLatch(1);
        CountDownLatch submitted = new CountDownLatch(expectedKernelCount + 2);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            List<DeliveryReport> batch = List.copyOf(invocation.getArgument(1));
            batches.add(batch);
            if (batches.size() == 1) {
                firstAppendEntered.countDown();
                releaseFirstAppend.await();
            }
            batch.forEach(ignored -> submitted.countDown());
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(capacity, remoteApi);
        DeliveryReport task = report(DeliveryEndpoint.TASK, "task-after-disconnect-burst");
        DeliveryReport server = report(DeliveryEndpoint.SERVER, "server-after-disconnect-burst");
        try {
            for (DeliveryReport report : originals.subList(0, firstBatchSize)) {
                assertThat(dispatcher.tryDispatch(report))
                        .isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
            }
            dispatcher.start();
            assertThat(firstAppendEntered.await(2, TimeUnit.SECONDS)).isTrue();

            int accepted = firstBatchSize;
            int rejected = 0;
            for (DeliveryReport report : originals.subList(firstBatchSize, originalCount)) {
                DeliveryReportDispatcher.DispatchStatus status = dispatcher.tryDispatch(report);
                if (status == DeliveryReportDispatcher.DispatchStatus.ACCEPTED) {
                    accepted++;
                } else {
                    assertThat(status).isEqualTo(DeliveryReportDispatcher.DispatchStatus.FULL);
                    rejected++;
                }
            }
            assertThat(accepted).isEqualTo(expectedKernelCount);
            assertThat(rejected).isEqualTo(capacity == 20_000 ? 0 : 8_920);
            assertThat(dispatcher.tryDispatch(task)).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
            assertThat(dispatcher.tryDispatch(server)).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
            assertThat(batches).hasSize(1);
            assertThat(batches.get(0)).containsExactlyElementsOf(originals.subList(0, firstBatchSize));

            releaseFirstAppend.countDown();
            assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseFirstAppend.countDown();
            stop(dispatcher);
        }

        assertThat(dispatcher.isAlive()).isFalse();
        assertThat(batches).allSatisfy(batch -> {
            assertThat(batch).hasSizeBetween(1, firstBatchSize);
            assertThat(batch).extracting(DeliveryReport::dst).containsOnly(batch.get(0).dst());
        });
        assertThat(batches.subList(0, 4)).extracting(batch -> batch.get(0).dst())
                .containsExactly(DeliveryEndpoint.KERNEL, DeliveryEndpoint.TASK,
                        DeliveryEndpoint.SERVER, DeliveryEndpoint.KERNEL);
        assertThat(batches.get(1)).containsExactly(task);
        assertThat(batches.get(2)).containsExactly(server);
        List<DeliveryReport> deliveredKernel = batches.stream()
                .flatMap(List::stream)
                .filter(report -> report.dst() == DeliveryEndpoint.KERNEL)
                .toList();
        // Exact envelopes retain each original identity, timestamp and FIFO position.
        assertThat(deliveredKernel).containsExactlyElementsOf(originals.subList(0, expectedKernelCount));
        assertThat(dispatcher.tryDispatch(originals.get(0)))
                .isEqualTo(DeliveryReportDispatcher.DispatchStatus.CLOSED);
    }

    @Test
    void taskUnavailableCanRequeueWithoutAnAttemptLimit() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            if (attempts.incrementAndGet() <= 5) {
                throw unavailable();
            }
            completed.countDown();
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(2, remoteApi);
        DeliveryReport report = report(DeliveryEndpoint.TASK, "task");
        dispatcher.tryDispatch(report);

        dispatcher.start();
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(attempts).hasValue(6);
    }

    @Test
    void taskReserveReturnsTheFailedBatchBehindNewIngress()
            throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        List<List<DeliveryReport>> batches = new CopyOnWriteArrayList<>();
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            List<DeliveryReport> batch = List.copyOf(invocation.getArgument(1));
            batches.add(batch);
            if (attempts.incrementAndGet() == 1) {
                firstEntered.countDown();
                releaseFirst.await();
                throw unavailable();
            }
            secondCompleted.countDown();
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(2, remoteApi);
        DeliveryReport oldOne = report(DeliveryEndpoint.TASK, "old-1");
        DeliveryReport oldTwo = report(DeliveryEndpoint.TASK, "old-2");
        dispatcher.tryDispatch(oldOne);
        dispatcher.tryDispatch(oldTwo);
        dispatcher.start();
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

        DeliveryReport newOne = report(DeliveryEndpoint.TASK, "new-1");
        DeliveryReport newTwo = report(DeliveryEndpoint.TASK, "new-2");
        assertThat(dispatcher.tryDispatch(newOne)).isEqualTo(
                DeliveryReportDispatcher.DispatchStatus.ACCEPTED
        );
        assertThat(dispatcher.tryDispatch(newTwo)).isEqualTo(
                DeliveryReportDispatcher.DispatchStatus.ACCEPTED
        );
        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.TASK,
                "full"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.FULL);
        releaseFirst.countDown();
        assertThat(secondCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).containsExactly(oldOne, oldTwo);
        assertThat(batches.get(1)).containsExactly(
                newOne,
                newTwo,
                oldOne,
                oldTwo
        );
    }

    @Test
    void serverSystemAndKernelFailuresDropOnceAndDoNotBlockTheNextLane()
            throws Exception {
        AtomicInteger serverAttempts = new AtomicInteger();
        AtomicInteger systemAttempts = new AtomicInteger();
        AtomicInteger kernelAttempts = new AtomicInteger();
        CountDownLatch bestEffortAttempted = new CountDownLatch(3);
        CountDownLatch taskCompleted = new CountDownLatch(1);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            List<DeliveryReport> batch = invocation.getArgument(1);
            if (batch.get(0).dst() == DeliveryEndpoint.SERVER) {
                serverAttempts.incrementAndGet();
                bestEffortAttempted.countDown();
                throw unavailable();
            }
            if (batch.get(0).dst() == DeliveryEndpoint.SYSTEM) {
                systemAttempts.incrementAndGet();
                bestEffortAttempted.countDown();
                throw unavailable();
            }
            if (batch.get(0).dst() == DeliveryEndpoint.KERNEL) {
                kernelAttempts.incrementAndGet();
                bestEffortAttempted.countDown();
                throw unavailable();
            }
            taskCompleted.countDown();
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(2, remoteApi);
        dispatcher.tryDispatch(report(DeliveryEndpoint.SERVER, "server"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.SYSTEM, "system"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.KERNEL, "kernel"));

        dispatcher.start();
        assertThat(bestEffortAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task"));
        assertThat(taskCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(serverAttempts).hasValue(1);
        assertThat(systemAttempts).hasValue(1);
        assertThat(kernelAttempts).hasValue(1);
    }

    @Test
    void concurrentProducersShareOnlyEachLaneSoftCapacity() {
        DeliveryReportDispatcher dispatcher = dispatcher(
                5,
                mock(WorkerDeliveryRemoteApi.class)
        );
        List<DeliveryEndpoint> destinations = List.of(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.KERNEL
        );
        Map<DeliveryEndpoint, AtomicInteger> accepted =
                new EnumMap<>(DeliveryEndpoint.class);
        destinations.forEach(destination -> accepted.put(
                destination,
                new AtomicInteger()
        ));

        IntStream.range(0, 60).parallel().forEach(index -> {
            DeliveryEndpoint destination = destinations.get(
                    index % destinations.size()
            );
            if (dispatcher.tryDispatch(report(
                    destination,
                    destination + "-" + index
            )) == DeliveryReportDispatcher.DispatchStatus.ACCEPTED) {
                accepted.get(destination).incrementAndGet();
            }
        });
        dispatcher.stopIngress();

        assertThat(accepted.get(DeliveryEndpoint.TASK)).hasValue(5);
        assertThat(accepted.get(DeliveryEndpoint.SERVER)).hasValue(5);
        assertThat(accepted.get(DeliveryEndpoint.SYSTEM)).hasValue(5);
        assertThat(accepted.get(DeliveryEndpoint.KERNEL)).hasValue(5);
    }

    @Test
    void lanesHaveIndependentFiniteAdmissionAndStopClosesAll() {
        DeliveryReportDispatcher dispatcher = dispatcher(
                2,
                mock(WorkerDeliveryRemoteApi.class)
        );
        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.TASK,
                "task-1"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.TASK,
                "task-2"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.TASK,
                "task-full"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.FULL);
        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.SYSTEM,
                "system"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);

        dispatcher.stopIngress();

        for (DeliveryEndpoint destination : List.of(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.SERVER,
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.KERNEL
        )) {
            assertThat(dispatcher.tryDispatch(report(destination, "late")))
                    .isEqualTo(DeliveryReportDispatcher.DispatchStatus.CLOSED);
        }
        assertThatThrownBy(() -> dispatcher.tryDispatch(DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.ADAPTER,
                "test.report",
                "",
                "{}",
                ""
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stopInterruptsSynchronousHttpWithoutReprocessingTheBatch()
            throws Exception {
        try (BlockingReportPeer peer = new BlockingReportPeer()) {
            WorkerDeliveryRemoteApi remoteApi = new WorkerDeliveryRemoteApi(
                    peer.server.baseUri(),
                    Duration.ofSeconds(2),
                    new WorkerDeliveryCodec()
            );
            DeliveryReportDispatcher dispatcher = dispatcher(2, remoteApi);
            dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task"));
            dispatcher.start();
            assertThat(peer.requestStarted.await(2, TimeUnit.SECONDS)).isTrue();

            stop(dispatcher);

            assertThat(dispatcher.isAlive()).isFalse();
            assertThat(peer.attempts).hasValue(1);
            Thread.sleep(100);
            assertThat(peer.attempts).hasValue(1);
        }
    }

    @Test
    void errorEscapesAndClosesIngress() throws Exception {
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            throw new AssertionError("fatal");
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(2, remoteApi);
        dispatcher.tryDispatch(report(DeliveryEndpoint.SYSTEM, "system"));

        dispatcher.start();
        dispatcher.thread().join(2_000);

        assertThat(dispatcher.isAlive()).isFalse();
        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.SYSTEM,
                "late"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.CLOSED);
    }

    @Test
    void rejectsInvalidCapacityAndBackoff() {
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        assertThatThrownBy(() -> new DeliveryReportDispatcher(
                "adapter-1",
                0,
                Duration.ofMillis(1),
                remoteApi
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryReportDispatcher(
                "adapter-1",
                Integer.MAX_VALUE,
                Duration.ofMillis(1),
                remoteApi
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeliveryReportDispatcher(
                "adapter-1",
                2,
                Duration.ZERO,
                remoteApi
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static DeliveryReportDispatcher dispatcher(
            int capacity,
            WorkerDeliveryRemoteApi remoteApi
    ) {
        return new DeliveryReportDispatcher(
                "adapter-1",
                capacity,
                Duration.ofMillis(5),
                remoteApi
        );
    }

    private static DeliveryReport report(
            DeliveryEndpoint destination,
            String payload
    ) {
        DeliveryEndpoint source = destination == DeliveryEndpoint.KERNEL
                ? DeliveryEndpoint.ADAPTER
                : DeliveryEndpoint.WORKER;
        return DeliveryReport.create(source, source == DeliveryEndpoint.ADAPTER
                        ? "adapter-1"
                        : "worker-1", destination, "test.report", "", payload, destination == DeliveryEndpoint.TASK
                        ? "task-context"
                        : "context");
    }

    private static WorkerDeliveryAdapterException unavailable() {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.REMOTE_API_UNAVAILABLE,
                "deliveryReport.submitRemote",
                "unavailable",
                null
        );
    }

    private static void stop(DeliveryReportDispatcher dispatcher)
            throws InterruptedException {
        dispatcher.stopIngress();
        dispatcher.stop();
        dispatcher.thread().join(2_000);
    }

    private static final class BlockingReportPeer implements AutoCloseable {

        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger attempts = new AtomicInteger();
        private final ScriptedHttpServer server = new ScriptedHttpServer(
                this::handle
        );

        private Response handle(ScriptedHttpServer.Request request)
                throws InterruptedException {
            attempts.incrementAndGet();
            requestStarted.countDown();
            release.await();
            return new Response(202, com.xa.mass.workerdelivery.json.Jsons
                    .toJson(Map.of(
                            "acceptedCount", 1,
                            "rejectedCount", 0
                    )));
        }

        @Override
        public void close() {
            release.countDown();
            server.close();
        }
    }
}
