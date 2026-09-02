package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.AFTER_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.BEFORE_NETWORK_CLOSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdapterProcessManagerTest {

    @Test
    void requiresAFiniteProcessSet() {
        assertThatThrownBy(() -> new AdapterProcessManager(
                "adapter-1",
                Duration.ofSeconds(1),
                List.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownsOnePlatformThreadPerProcessAndPhaseLocalInterrupts()
            throws Exception {
        List<String> finishOrder = new CopyOnWriteArrayList<>();
        RecordingProcess command = new RecordingProcess(
                "command",
                finishOrder
        );
        RecordingProcess report = new RecordingProcess(
                "report",
                finishOrder
        );
        AdapterProcessManager manager = new AdapterProcessManager(
                "adapter-1",
                Duration.ofSeconds(1),
                List.of(
                        entry("command", BEFORE_NETWORK_CLOSE, command),
                        entry("report", AFTER_NETWORK_CLOSE, report)
                )
        );
        try {
            manager.start();
            assertThat(command.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(report.started.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(command.threadName).isEqualTo(
                    "worker-delivery-adapter-1-command-consumer"
            );
            assertThat(report.threadName).isEqualTo(
                    "worker-delivery-adapter-1-report-consumer"
            );
            assertThat(command.daemon).isTrue();
            assertThat(report.daemon).isTrue();
            assertThat(command.virtual).isFalse();
            assertThat(report.virtual).isFalse();

            manager.quiesce(BEFORE_NETWORK_CLOSE);
            assertThat(command.exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(report.exited.await(
                    100,
                    TimeUnit.MILLISECONDS
            )).isFalse();
            assertThat(command.quiesceCalls).hasValue(1);
            assertThat(report.quiesceCalls).hasValue(0);

            manager.quiesce(AFTER_NETWORK_CLOSE);
            assertThat(report.exited.await(2, TimeUnit.SECONDS)).isTrue();
            manager.close();
            manager.close();

            assertThat(report.quiesceCalls).hasValue(1);
            assertThat(finishOrder).containsExactly("report", "command");
        } finally {
            manager.quiesce(BEFORE_NETWORK_CLOSE);
            manager.quiesce(AFTER_NETWORK_CLOSE);
            manager.close();
        }
    }

    @Test
    void closeUsesOneSharedDeadlineAndSkipsAllFinishHooks()
            throws Exception {
        StubbornProcess first = new StubbornProcess();
        StubbornProcess second = new StubbornProcess();
        AdapterProcessManager manager = new AdapterProcessManager(
                "adapter-1",
                Duration.ofMillis(150),
                List.of(
                        entry("first", BEFORE_NETWORK_CLOSE, first),
                        entry("second", AFTER_NETWORK_CLOSE, second)
                )
        );
        manager.start();
        assertThat(first.started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(second.started.await(2, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        try {
            assertThatThrownBy(manager::close)
                    .isInstanceOfSatisfying(
                            WorkerDeliveryAdapterException.class,
                            failure -> {
                                assertThat(failure.errorCode()).isEqualTo(
                                        WorkerDeliveryAdapterErrorCode
                                                .SHUTDOWN_TIMEOUT
                                );
                                assertThat(failure.operation()).isEqualTo(
                                        "adapterProcess.stopLoops"
                                );
                            }
                    );
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofMillis(260));
            assertThat(first.finishCalls).hasValue(0);
            assertThat(second.finishCalls).hasValue(0);
        } finally {
            first.release.countDown();
            second.release.countDown();
            assertThat(first.exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(second.exited.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static AdapterProcessEntry entry(
            String id,
            QuiescePhase phase,
            AdapterProcess process
    ) {
        return new AdapterProcessEntry(id, phase, process);
    }

    private static final class RecordingProcess implements AdapterProcess {

        private final String id;
        private final List<String> finishOrder;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicInteger quiesceCalls = new AtomicInteger();
        private volatile String threadName;
        private volatile boolean daemon;
        private volatile boolean virtual;

        private RecordingProcess(String id, List<String> finishOrder) {
            this.id = id;
            this.finishOrder = finishOrder;
        }

        @Override
        public void runLoop() {
            Thread current = Thread.currentThread();
            threadName = current.getName();
            daemon = current.isDaemon();
            virtual = current.isVirtual();
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exited.countDown();
            }
        }

        @Override
        public void quiesce() {
            quiesceCalls.incrementAndGet();
        }

        @Override
        public void finishAfterLoopStop() {
            finishOrder.add(id);
        }
    }

    private static final class StubbornProcess implements AdapterProcess {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);
        private final AtomicInteger finishCalls = new AtomicInteger();

        @Override
        public void runLoop() {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Deliberately ignore shutdown interruption for the test.
                }
            }
            exited.countDown();
        }

        @Override
        public void quiesce() {
        }

        @Override
        public void finishAfterLoopStop() {
            finishCalls.incrementAndGet();
        }
    }
}
