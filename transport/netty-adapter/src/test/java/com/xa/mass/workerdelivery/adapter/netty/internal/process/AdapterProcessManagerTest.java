package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.AFTER_NETWORK_CLOSE;
import static com.xa.mass.workerdelivery.adapter.netty.internal.process.QuiescePhase.BEFORE_NETWORK_CLOSE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void ownsRoundIsolationPhaseQuiescenceAndReverseFinish()
            throws Exception {
        AtomicBoolean commandQuiesced = new AtomicBoolean();
        CountDownLatch commandRounds = new CountDownLatch(2);
        CountDownLatch reportDuringNetworkClose = new CountDownLatch(1);
        var finishOrder = new java.util.concurrent.CopyOnWriteArrayList<
                String>();
        RecordingProcess command = new RecordingProcess(
                "command",
                commandRounds,
                commandQuiesced,
                null,
                null,
                finishOrder,
                true
        );
        RecordingProcess report = new RecordingProcess(
                "report",
                new CountDownLatch(1),
                new AtomicBoolean(),
                commandQuiesced,
                reportDuringNetworkClose,
                finishOrder,
                false
        );
        AdapterProcessManager manager = new AdapterProcessManager(
                "adapter-1",
                Duration.ofSeconds(1),
                List.of(
                        scheduled("command", BEFORE_NETWORK_CLOSE, command),
                        scheduled("report", AFTER_NETWORK_CLOSE, report)
                )
        );
        try {
            manager.start();
            assertThat(commandRounds.await(2, TimeUnit.SECONDS)).isTrue();

            manager.quiesce(BEFORE_NETWORK_CLOSE);
            assertThat(command.quiesceCalls.get()).isOne();
            assertThat(reportDuringNetworkClose.await(
                    2,
                    TimeUnit.SECONDS
            )).isTrue();

            manager.quiesce(AFTER_NETWORK_CLOSE);
            manager.close();
            manager.close();

            assertThat(report.quiesceCalls.get()).isOne();
            assertThat(finishOrder).containsExactly("report", "command");
        } finally {
            manager.quiesce(BEFORE_NETWORK_CLOSE);
            manager.quiesce(AFTER_NETWORK_CLOSE);
            manager.close();
        }
    }

    private static ScheduledAdapterProcess scheduled(
            String id,
            QuiescePhase phase,
            AdapterProcess process
    ) {
        return new ScheduledAdapterProcess(
                id,
                Duration.ZERO,
                Duration.ofMillis(5),
                phase,
                process
        );
    }

    private static final class RecordingProcess implements AdapterProcess {

        private final String id;
        private final CountDownLatch initialRounds;
        private final AtomicBoolean quiesced;
        private final AtomicBoolean roundTrigger;
        private final CountDownLatch roundAfterCommandQuiesce;
        private final List<String> finishOrder;
        private final boolean failRound;
        private final AtomicInteger quiesceCalls = new AtomicInteger();

        private RecordingProcess(
                String id,
                CountDownLatch initialRounds,
                AtomicBoolean quiesced,
                AtomicBoolean roundTrigger,
                CountDownLatch roundAfterCommandQuiesce,
                List<String> finishOrder,
                boolean failRound
        ) {
            this.id = id;
            this.initialRounds = initialRounds;
            this.quiesced = quiesced;
            this.roundTrigger = roundTrigger;
            this.roundAfterCommandQuiesce = roundAfterCommandQuiesce;
            this.finishOrder = finishOrder;
            this.failRound = failRound;
        }

        @Override
        public void round() {
            initialRounds.countDown();
            if (roundAfterCommandQuiesce != null
                    && roundTrigger.get()) {
                roundAfterCommandQuiesce.countDown();
            }
            if (failRound) {
                throw new IllegalStateException("round failed");
            }
        }

        @Override
        public void quiesce() {
            quiesceCalls.incrementAndGet();
            quiesced.set(true);
        }

        @Override
        public void finishAfterSchedulerStop() {
            finishOrder.add(id);
        }
    }

}
