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
import com.xa.mass.workerdelivery.adapter.netty.internal.process.AdapterProcessManager;
import io.netty.channel.ChannelHandler;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class NettyWorkerDeliveryAdapterProcessTest {

    @Test
    void aggregateKeepsNetworkAndLaneShutdownInOwnerOrder() {
        NettyWorkerServer network = mock(NettyWorkerServer.class);
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        AdapterProcessManager processes = mock(AdapterProcessManager.class);
        NettyWorkerDeliveryAdapter adapter = adapter(
                network,
                mock(WorkerConnectionInboundHandler.class),
                connection,
                processes
        );

        adapter.start();
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.RUNNING
        );

        adapter.close();

        InOrder order = inOrder(network, processes, connection);
        order.verify(network).start(any(ChannelHandler.class));
        order.verify(processes).start();
        order.verify(processes).stopCommand();
        order.verify(network).close();
        order.verify(connection).clear();
        order.verify(processes).stopReport();
        order.verify(processes).awaitStopped();
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.CLOSED
        );

        adapter.close();
        verify(processes).stopCommand();
        verify(processes).stopReport();
    }

    @Test
    void processShutdownFailureStillClosesAggregate() {
        AdapterProcessManager processes = mock(AdapterProcessManager.class);
        doThrow(new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_TIMEOUT,
                "adapterProcess.awaitStopped",
                null,
                null
        )).when(processes).awaitStopped();
        NettyWorkerDeliveryAdapter adapter = adapter(
                mock(NettyWorkerServer.class),
                mock(WorkerConnectionInboundHandler.class),
                mock(WorkerConnectionMechanism.class),
                processes
        );
        adapter.start();

        assertThatThrownBy(adapter::close)
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        failure -> {
                            assertThat(failure.errorCode()).isEqualTo(
                                    WorkerDeliveryAdapterErrorCode
                                            .SHUTDOWN_TIMEOUT
                            );
                            assertThat(failure.operation()).isEqualTo(
                                    "adapterProcess.awaitStopped"
                            );
                        }
                );
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.CLOSED
        );
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
        AdapterProcessManager processes = mock(AdapterProcessManager.class);
        NettyWorkerDeliveryAdapter adapter = adapter(
                network,
                mock(WorkerConnectionInboundHandler.class),
                connection,
                processes
        );

        assertThatThrownBy(adapter::start)
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(
                                WorkerDeliveryAdapterErrorCode
                                        .LISTENER_START_FAILED
                        )
                );

        verify(processes).stopCommand();
        verify(network).close();
        verify(connection).clear();
        verify(processes).stopReport();
        verify(processes).awaitStopped();
        assertThat(adapter.state()).isEqualTo(
                WorkerDeliveryAdapterState.CLOSED
        );
    }

    @Test
    void partialLaneStartFailureAlsoRunsTheConcreteClosePath() {
        NettyWorkerServer network = mock(NettyWorkerServer.class);
        WorkerConnectionMechanism connection = mock(
                WorkerConnectionMechanism.class
        );
        AdapterProcessManager processes = mock(AdapterProcessManager.class);
        doThrow(new IllegalStateException("lane start failed"))
                .when(processes)
                .start();
        NettyWorkerDeliveryAdapter adapter = adapter(
                network,
                mock(WorkerConnectionInboundHandler.class),
                connection,
                processes
        );

        assertThatThrownBy(adapter::start)
                .isInstanceOfSatisfying(
                        WorkerDeliveryAdapterException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(
                                WorkerDeliveryAdapterErrorCode
                                        .LISTENER_START_FAILED
                        )
                );

        InOrder order = inOrder(network, processes, connection);
        order.verify(network).start(any(ChannelHandler.class));
        order.verify(processes).start();
        order.verify(processes).stopCommand();
        order.verify(network).close();
        order.verify(connection).clear();
        order.verify(processes).stopReport();
        order.verify(processes).awaitStopped();
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
                mock(AdapterProcessManager.class)
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
            AdapterProcessManager processes
    ) {
        return new NettyWorkerDeliveryAdapter(
                "adapter-1",
                network,
                inbound,
                connection,
                processes
        );
    }
}
