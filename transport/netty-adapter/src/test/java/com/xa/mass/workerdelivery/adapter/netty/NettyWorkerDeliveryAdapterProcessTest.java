package com.xa.mass.workerdelivery.adapter.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import io.netty.channel.ChannelHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class NettyWorkerDeliveryAdapterProcessTest {

    @Test
    void aggregateOwnsTwoDaemonLoopsAndClosesInOwnerOrder()
            throws Exception {
        CountDownLatch loopsStarted = new CountDownLatch(2);
        List<Thread> consumerThreads = new CopyOnWriteArrayList<>();
        DeliveryCommandProcess command = mock(DeliveryCommandProcess.class);
        DeliveryReportProcess report = mock(DeliveryReportProcess.class);
        doAnswer(invocation -> runUntilInterrupted(
                loopsStarted,
                consumerThreads
        )).when(command).runLoop();
        doAnswer(invocation -> runUntilInterrupted(
                loopsStarted,
                consumerThreads
        )).when(report).runLoop();
        NettyWorkerServer network = mock(NettyWorkerServer.class);
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        WorkerConnectionInboundHandler inbound = mock(
                WorkerConnectionInboundHandler.class
        );
        NettyWorkerDeliveryAdapter adapter = adapter(
                network,
                inbound,
                connection,
                command,
                report,
                Duration.ofSeconds(1)
        );

        adapter.start();
        assertThat(loopsStarted.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.RUNNING
        );
        assertThat(consumerThreads)
                .extracting(Thread::getName)
                .containsExactlyInAnyOrder(
                        "worker-delivery-adapter-1-delivery-command",
                        "worker-delivery-adapter-1-delivery-report"
                );
        assertThat(consumerThreads).allMatch(Thread::isDaemon);

        adapter.close();

        InOrder closeOrder = inOrder(command, network, connection, report);
        closeOrder.verify(command).stop();
        closeOrder.verify(network).close();
        closeOrder.verify(connection).clear();
        closeOrder.verify(report).stop();
        assertThat(consumerThreads).noneMatch(Thread::isAlive);
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.CLOSED
        );

        adapter.close();
        verify(command).stop();
        verify(report).stop();
    }

    @Test
    void loopTimeoutUsesOneBoundedAggregateShutdownBudget()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> stubbornThread = new AtomicReference<>();
        DeliveryCommandProcess command = mock(DeliveryCommandProcess.class);
        doAnswer(invocation -> {
            stubbornThread.set(Thread.currentThread());
            started.countDown();
            boolean interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(command).runLoop();
        DeliveryReportProcess report = mock(DeliveryReportProcess.class);
        NettyWorkerDeliveryAdapter adapter = adapter(
                mock(NettyWorkerServer.class),
                mock(WorkerConnectionInboundHandler.class),
                mock(WorkerConnectionMechanism.class),
                command,
                report,
                Duration.ofMillis(40)
        );
        adapter.start();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        long closeStarted = System.nanoTime();
        try {
            assertThatThrownBy(adapter::close)
                    .isInstanceOfSatisfying(
                            WorkerDeliveryAdapterException.class,
                            failure -> {
                                assertThat(failure.errorCode()).isEqualTo(
                                        WorkerDeliveryAdapterErrorCode
                                                .SHUTDOWN_TIMEOUT
                                );
                                assertThat(failure.operation()).isEqualTo(
                                        "netty.stopConsumerLoops"
                                );
                            }
                    );
            assertThat(Duration.ofNanos(
                    System.nanoTime() - closeStarted
            )).isLessThan(Duration.ofSeconds(1));
            assertThat(adapter.state()).isEqualTo(
                    WorkerDeliveryAdapterState.CLOSED
            );
        } finally {
            release.countDown();
            stubbornThread.get().join(2_000);
        }
    }

    @Test
    void startFailureRunsTheSameConcreteClosePath() {
        NettyWorkerServer network = mock(NettyWorkerServer.class);
        doThrow(new IllegalStateException("bind failed"))
                .when(network)
                .start(any(ChannelHandler.class));
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        DeliveryCommandProcess command = mock(DeliveryCommandProcess.class);
        DeliveryReportProcess report = mock(DeliveryReportProcess.class);
        NettyWorkerDeliveryAdapter adapter = adapter(
                network,
                mock(WorkerConnectionInboundHandler.class),
                connection,
                command,
                report,
                Duration.ofSeconds(1)
        );

        assertThatThrownBy(adapter::start)
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(
                                WorkerDeliveryAdapterErrorCode
                                        .LISTENER_START_FAILED
                        )
                );

        verify(command).stop();
        verify(network).close();
        verify(connection).clear();
        verify(report).stop();
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.CLOSED
        );
    }

    @Test
    void concurrentCloseHasOneSerializedLifecycleOwner() throws Exception {
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        NettyWorkerServer network = mock(NettyWorkerServer.class);
        doAnswer(invocation -> {
            closeCalls.incrementAndGet();
            closeStarted.countDown();
            if (!releaseClose.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("close was not released");
            }
            return null;
        }).when(network).close();
        NettyWorkerDeliveryAdapter adapter = adapter(
                network,
                mock(WorkerConnectionInboundHandler.class),
                mock(WorkerConnectionMechanism.class),
                mock(DeliveryCommandProcess.class),
                mock(DeliveryReportProcess.class),
                Duration.ofSeconds(1)
        );
        adapter.start();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch secondReturned = new CountDownLatch(1);

        Thread first = Thread.startVirtualThread(() ->
                close(adapter, failures, null)
        );
        assertThat(closeStarted.await(2, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.startVirtualThread(() ->
                close(adapter, failures, secondReturned)
        );

        assertThat(secondReturned.await(100, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(closeCalls).hasValue(1);

        releaseClose.countDown();
        first.join(2_000);
        second.join(2_000);

        assertThat(first.isAlive()).isFalse();
        assertThat(second.isAlive()).isFalse();
        assertThat(failures).isEmpty();
        assertThat(closeCalls).hasValue(1);
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.CLOSED
        );
    }

    private static Object runUntilInterrupted(
            CountDownLatch started,
            List<Thread> threads
    ) {
        Thread current = Thread.currentThread();
        threads.add(current);
        started.countDown();
        while (!current.isInterrupted()) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        return null;
    }

    private static void close(
            NettyWorkerDeliveryAdapter adapter,
            List<Throwable> failures,
            CountDownLatch returned
    ) {
        try {
            adapter.close();
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            if (returned != null) {
                returned.countDown();
            }
        }
    }

    private static NettyWorkerDeliveryAdapter adapter(
            NettyWorkerServer network,
            WorkerConnectionInboundHandler inbound,
            WorkerConnectionMechanism connection,
            DeliveryCommandProcess command,
            DeliveryReportProcess report,
            Duration shutdownTimeout
    ) {
        return new NettyWorkerDeliveryAdapter(
                "adapter-1",
                network,
                inbound,
                connection,
                command,
                report,
                shutdownTimeout
        );
    }
}
