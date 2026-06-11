package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTargetedTaskDispatchHandoffPumpTest {

    @Test
    void pumpForwardsRouteTargetedBatches() throws Exception {
        InMemoryRouteTargetedTaskDispatchHandoff handoff = new InMemoryRouteTargetedTaskDispatchHandoff(4);
        RouteTargetedTaskDispatchBatch batch = RouteTargetedDispatchFixtures.batch(
                "route-1",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("msg-1", "worker-1")
        );
        handoff.submit(batch);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RouteTargetedTaskDispatchBatch> received = new AtomicReference<>();
        try (VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("route-pump-test-", 16)) {
            RouteTargetedTaskDispatchHandoffPump pump = new RouteTargetedTaskDispatchHandoffPump(
                    handoff,
                    value -> {
                        received.set(value);
                        latch.countDown();
                    },
                    executor
            );
            pump.start();
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            pump.stop();
        }

        assertEquals(List.of("msg-1"), RouteTargetedDispatchFixtures.messages(received.get()));
    }
}
