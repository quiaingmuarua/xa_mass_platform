package com.xa.mass.workerdelivery.adapter.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScheduledWorkerDeliveryAdapterTest {

    @Test
    void startsOneLoopAndClosesTheCore() throws Exception {
        WorkerDeliveryAdapterCore core = mock(
                WorkerDeliveryAdapterCore.class
        );
        ScheduledWorkerDeliveryAdapter adapter =
                new ScheduledWorkerDeliveryAdapter(
                        WorkerDeliveryAdapterType.WEBSOCKET,
                        "websocket-1",
                        Duration.ofMillis(5),
                        core
                );

        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.REGISTERED);
        adapter.start();
        adapter.start();
        try {
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(1).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    verify(core, atLeastOnce()).dispatchOnce();
                    break;
                } catch (AssertionError ignored) {
                    Thread.sleep(5);
                }
            }
            verify(core, atLeastOnce()).dispatchOnce();
            assertThat(adapter.state())
                    .isEqualTo(WorkerDeliveryAdapterState.RUNNING);
        } finally {
            adapter.close();
        }

        assertThat(adapter.state())
                .isEqualTo(WorkerDeliveryAdapterState.CLOSED);
        verify(core).close();
        adapter.close();
        assertThatThrownBy(adapter::start)
                .isInstanceOf(IllegalStateException.class);
    }
}
