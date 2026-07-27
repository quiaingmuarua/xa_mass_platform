package com.xa.mass.server.workerdelivery.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerDeliveryAdapterLoopTest {

    @Test
    void startsOneRoundLoopAndClosesAdapterAfterStopping() throws Exception {
        WorkerDeliveryAdapter adapter = mock(WorkerDeliveryAdapter.class);
        WorkerDeliveryAdapterLoop loop = new WorkerDeliveryAdapterLoop(
                adapter,
                Duration.ofMillis(5)
        );

        loop.start();
        try {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    verify(adapter, atLeastOnce()).dispatchOnce();
                    break;
                } catch (AssertionError ignored) {
                    Thread.sleep(5);
                }
            }
            verify(adapter, atLeastOnce()).dispatchOnce();
            assertThat(loop.isRunning()).isTrue();
        } finally {
            loop.stop();
        }

        assertThat(loop.isRunning()).isFalse();
        verify(adapter).close();
    }
}
