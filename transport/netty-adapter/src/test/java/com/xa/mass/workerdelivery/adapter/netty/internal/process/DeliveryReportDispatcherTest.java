package com.xa.mass.workerdelivery.adapter.netty.internal.process;

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

class DeliveryReportDispatcherTest {

    @Test
    void oneThreadRotatesAcrossHomogeneousLanes() throws Exception {
        List<List<DeliveryReport>> batches = new CopyOnWriteArrayList<>();
        CountDownLatch submitted = new CountDownLatch(3);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            batches.add(List.copyOf(invocation.getArgument(1)));
            submitted.countDown();
            return null;
        }).when(remoteApi).appendReports(anyString(), anyList());
        DeliveryReportDispatcher dispatcher = dispatcher(10, remoteApi);
        dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task-1"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task-2"));
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
                        DeliveryEndpoint.SYSTEM,
                        DeliveryEndpoint.KERNEL
                );
        assertThat(batches.get(0)).hasSize(2);
        assertThat(batches).allSatisfy(batch -> assertThat(batch)
                .extracting(DeliveryReport::dst)
                .containsOnly(batch.get(0).dst()));
    }

    @Test
    void drainsTaskReportsInBatchesOfOneHundred() throws Exception {
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
                    DeliveryEndpoint.TASK,
                    "task-" + index
            ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.ACCEPTED);
        }

        dispatcher.start();
        assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

        assertThat(sizes).containsExactly(100, 100, 5);
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
    void systemAndKernelFailuresDropOnceAndDoNotBlockTheNextLane()
            throws Exception {
        AtomicInteger systemAttempts = new AtomicInteger();
        AtomicInteger kernelAttempts = new AtomicInteger();
        CountDownLatch bestEffortAttempted = new CountDownLatch(2);
        CountDownLatch taskCompleted = new CountDownLatch(1);
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            List<DeliveryReport> batch = invocation.getArgument(1);
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
        dispatcher.tryDispatch(report(DeliveryEndpoint.SYSTEM, "system"));
        dispatcher.tryDispatch(report(DeliveryEndpoint.KERNEL, "kernel"));

        dispatcher.start();
        assertThat(bestEffortAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.tryDispatch(report(DeliveryEndpoint.TASK, "task"));
        assertThat(taskCompleted.await(2, TimeUnit.SECONDS)).isTrue();
        stop(dispatcher);

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
            DeliveryEndpoint destination = destinations.get(index % 3);
            if (dispatcher.tryDispatch(report(
                    destination,
                    destination + "-" + index
            )) == DeliveryReportDispatcher.DispatchStatus.ACCEPTED) {
                accepted.get(destination).incrementAndGet();
            }
        });
        dispatcher.stopIngress();

        assertThat(accepted.get(DeliveryEndpoint.TASK)).hasValue(5);
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

        assertThat(dispatcher.tryDispatch(report(
                DeliveryEndpoint.KERNEL,
                "late"
        ))).isEqualTo(DeliveryReportDispatcher.DispatchStatus.CLOSED);
        assertThatThrownBy(() -> dispatcher.tryDispatch(DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.ADAPTER,
                "test.report",
                "200",
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
        return DeliveryReport.create(
                source,
                source == DeliveryEndpoint.ADAPTER
                        ? "adapter-1"
                        : "worker-1",
                destination,
                "test.report",
                "200",
                payload,
                destination == DeliveryEndpoint.TASK
                        ? "task-context"
                        : "context"
        );
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
