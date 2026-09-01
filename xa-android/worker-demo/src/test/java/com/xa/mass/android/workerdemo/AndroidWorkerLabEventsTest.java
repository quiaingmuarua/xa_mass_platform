package com.xa.mass.android.workerdemo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerCommandOutcome;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class AndroidWorkerLabEventsTest {

    @Test
    public void exposesOnlyTheTwoFixedLabEvents() {
        AndroidWorkerLabEvents events = new AndroidWorkerLabEvents();

        assertEquals(
                List.of(
                        AndroidWorkerLabEvents.DELAY_EVENT,
                        AndroidWorkerLabEvents.FAIL_EVENT
                ),
                events.definitions().stream()
                        .map(definition -> definition.eventName())
                        .collect(java.util.stream.Collectors.toList())
        );
    }

    @Test
    public void delayTracksActiveExecutionAndReturnsNull() throws Exception {
        AndroidWorkerLabEvents events = new AndroidWorkerLabEvents();
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                events.definitions()
        );
        AtomicReference<WorkerCommandOutcome> outcome = new AtomicReference<>();
        Thread execution = new Thread(
                () -> outcome.set(execute(
                        dispatcher,
                        AndroidWorkerLabEvents.DELAY_EVENT,
                        Map.of("delayMillis", 250L)
                )),
                "android-worker-lab-delay-test"
        );

        execution.start();
        awaitActiveCount(events, 1);
        execution.join(2_000L);

        assertFalse(execution.isAlive());
        assertEquals(0, events.activeDelayCount());
        assertEquals("200", outcome.get().outcomeCode());
        assertEquals("null", outcome.get().payload());
    }

    @Test
    public void delayRejectsInvalidPayloads() {
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                new AndroidWorkerLabEvents().definitions()
        );

        for (Map<String, Object> payload : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("delayMillis", 0L),
                Map.<String, Object>of("delayMillis", 30_001L),
                Map.<String, Object>of("delayMillis", "10"),
                Map.<String, Object>of(
                        "delayMillis",
                        10L,
                        "extra",
                        true
                )
        )) {
            assertEquals(
                    "3301",
                    execute(
                            dispatcher,
                            AndroidWorkerLabEvents.DELAY_EVENT,
                            payload
                    ).outcomeCode()
            );
        }
    }

    @Test
    public void interruptedDelayRestoresInterruptAndMapsExecutionFailure()
            throws Exception {
        AndroidWorkerLabEvents events = new AndroidWorkerLabEvents();
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                events.definitions()
        );
        AtomicReference<WorkerCommandOutcome> outcome = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread execution = new Thread(() -> {
            outcome.set(execute(
                    dispatcher,
                    AndroidWorkerLabEvents.DELAY_EVENT,
                    Map.of("delayMillis", 30_000L)
            ));
            interrupted.set(Thread.currentThread().isInterrupted());
        }, "android-worker-lab-interrupt-test");

        execution.start();
        awaitActiveCount(events, 1);
        execution.interrupt();
        execution.join(2_000L);

        assertFalse(execution.isAlive());
        assertEquals(0, events.activeDelayCount());
        assertEquals("3303", outcome.get().outcomeCode());
        assertTrue(interrupted.get());
    }

    @Test
    public void failMapsExecutionFailureAndDoesNotPoisonDispatcher() {
        WorkerCommandDispatcher dispatcher = WorkerCommandDispatcher.forWorker(
                new AndroidWorkerLabEvents().definitions()
        );

        assertEquals(
                "3303",
                execute(
                        dispatcher,
                        AndroidWorkerLabEvents.FAIL_EVENT,
                        Map.of()
                ).outcomeCode()
        );
        assertEquals(
                "3301",
                execute(
                        dispatcher,
                        AndroidWorkerLabEvents.FAIL_EVENT,
                        Map.of("extra", true)
                ).outcomeCode()
        );
        assertEquals(
                "200",
                execute(
                        dispatcher,
                        AndroidWorkerLabEvents.DELAY_EVENT,
                        Map.of("delayMillis", 1L)
                ).outcomeCode()
        );
    }

    private static WorkerCommandOutcome execute(
            WorkerCommandDispatcher dispatcher,
            String eventName,
            Map<String, Object> payload
    ) {
        return dispatcher.execute(DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                eventName,
                Long.MAX_VALUE,
                com.xa.mass.workerdelivery.json.Jsons.toJson(payload),
                "android-worker-lab-test"
        )).orElseThrow();
    }

    private static void awaitActiveCount(
            AndroidWorkerLabEvents events,
            int expected
    ) throws InterruptedException {
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(2L);
        while (System.nanoTime() < deadline) {
            if (events.activeDelayCount() == expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("activeDelayCount did not reach " + expected);
    }
}
