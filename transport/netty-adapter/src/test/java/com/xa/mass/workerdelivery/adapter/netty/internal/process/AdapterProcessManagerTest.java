package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class AdapterProcessManagerTest {

    @Test
    void startsTheFixedReportThenCommandDispatchers() {
        BatchDispatcher<DeliveryCommandItem> command = commandMock();
        DeliveryReportDispatcher report = reportMock();
        AdapterProcessManager manager = new AdapterProcessManager(
                command,
                report,
                Duration.ofSeconds(1)
        );

        manager.start();

        InOrder order = inOrder(report, command);
        order.verify(report).start();
        order.verify(command).start();
    }

    @Test
    void stopReportClosesIngressBeforeInterruptingDispatcher() {
        BatchDispatcher<DeliveryCommandItem> command = commandMock();
        DeliveryReportDispatcher report = reportMock();
        AdapterProcessManager manager = new AdapterProcessManager(
                command,
                report,
                Duration.ofSeconds(1)
        );

        manager.stopReport();

        InOrder order = inOrder(report);
        order.verify(report).stopIngress();
        order.verify(report).stop();
    }

    @Test
    void ownsExactlyTwoNamedDaemonPlatformThreads() throws Exception {
        CountDownLatch processed = new CountDownLatch(2);
        BatchDispatcher<DeliveryCommandItem> command =
                BatchDispatcher.pulling(
                        "adapter-1",
                        "delivery-command",
                        2,
                        2,
                        Duration.ofMillis(20),
                        () -> List.of(item("worker-1")),
                        batch -> {
                            processed.countDown();
                            return BatchProcessResult.completed();
                        }
                );
        WorkerDeliveryRemoteApi remoteApi = mock(WorkerDeliveryRemoteApi.class);
        doAnswer(invocation -> {
            processed.countDown();
            return null;
        }).when(remoteApi).appendReports(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()
        );
        DeliveryReportDispatcher report = new DeliveryReportDispatcher(
                "adapter-1",
                2,
                Duration.ofMillis(20),
                remoteApi
        );
        assertThat(report.tryDispatch(report("report-1"))).isEqualTo(
                DeliveryReportDispatcher.DispatchStatus.ACCEPTED
        );
        AdapterProcessManager manager = new AdapterProcessManager(
                command,
                report,
                Duration.ofSeconds(1)
        );

        manager.start();
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();

        assertThat(List.of(manager.commandThread(), manager.reportThread()))
                .allMatch(Thread::isDaemon)
                .extracting(Thread::getName)
                .containsExactly(
                        "worker-delivery-adapter-1-delivery-command",
                        "worker-delivery-adapter-1-delivery-report"
                );

        manager.stopCommand();
        manager.stopReport();
        manager.awaitStopped();
        assertThat(manager.commandThread().isAlive()).isFalse();
        assertThat(manager.reportThread().isAlive()).isFalse();
    }

    @Test
    void awaitStoppedUsesOneSharedDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BatchDispatcher<DeliveryCommandItem> command =
                BatchDispatcher.pulling(
                        "adapter-1",
                        "delivery-command",
                        2,
                        2,
                        Duration.ofSeconds(1),
                        () -> List.of(item("worker-1")),
                        batch -> {
                            entered.countDown();
                            awaitIgnoringInterrupt(release);
                            return BatchProcessResult.completed();
                        }
                );
        DeliveryReportDispatcher report = new DeliveryReportDispatcher(
                "adapter-1",
                2,
                Duration.ofSeconds(1),
                mock(WorkerDeliveryRemoteApi.class)
        );
        AdapterProcessManager manager = new AdapterProcessManager(
                command,
                report,
                Duration.ofMillis(40)
        );
        manager.start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        manager.stopCommand();
        manager.stopReport();

        long started = System.nanoTime();
        try {
            assertThatThrownBy(manager::awaitStopped)
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
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            release.countDown();
            manager.commandThread().join(2_000);
        }
    }

    @SuppressWarnings("unchecked")
    private static BatchDispatcher<DeliveryCommandItem> commandMock() {
        return (BatchDispatcher<DeliveryCommandItem>) (BatchDispatcher<?>)
                mock(BatchDispatcher.class);
    }

    private static DeliveryReportDispatcher reportMock() {
        return mock(DeliveryReportDispatcher.class);
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release) {
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
    }

    private static DeliveryCommandItem item(String workerId) {
        return new DeliveryCommandItem(
                workerId,
                DeliveryCommand.create(
                        DeliveryEndpoint.TASK,
                        DeliveryEndpoint.WORKER,
                        "test.observe",
                        Long.MAX_VALUE,
                        "{}",
                        "context"
                )
        );
    }

    private static DeliveryReport report(String payload) {
        return DeliveryReport.create(
                DeliveryEndpoint.WORKER,
                "worker-1",
                DeliveryEndpoint.SYSTEM,
                "test.observe",
                "200",
                payload,
                "direct-call:v1:test"
        );
    }
}
