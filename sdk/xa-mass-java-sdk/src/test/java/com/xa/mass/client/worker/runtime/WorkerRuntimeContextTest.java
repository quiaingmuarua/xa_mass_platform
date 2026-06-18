package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.handler.WorkerResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeContextTest {
    @Test
    void derivesWorkerRuntimeFactsFromDefinition() {
        WorkerRuntimeContext context = newContext(definitionBuilder()
                .attribute("region", "sg")
                .build(), null);

        assertEquals("worker-1", context.workerId());
        assertEquals("group-1", context.workerGroupId());
        assertEquals(Map.of("region", "sg"), context.attributes());
        assertTrue(context.eventHandlers().containsKey("probe.phone.metadata"));
        assertSame(WorkerRuntimeListener.NOOP, context.listener());
    }

    @Test
    void copiesDefinitionMapsSoCallerMutationDoesNotLeak() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("region", "sg");
        WorkerRuntimeDefinition.Builder builder = definitionBuilder().attributes(attributes);
        WorkerRuntimeContext context = newContext(builder.build(), null);

        attributes.put("region", "us");

        assertEquals("sg", context.attributes().get("region"));
        assertThrows(UnsupportedOperationException.class, () -> context.attributes().put("region", "eu"));
        assertThrows(UnsupportedOperationException.class, () -> context.eventHandlers().clear());
    }

    @Test
    void exposesSharedDispatchProcessorAndReporter() {
        WorkerRuntimeContext context = newContext(definitionBuilder().build(), null);

        WorkerDispatchProcessor.ProcessedDispatch processed = context.dispatchProcessor().process(
                new com.xa.mass.client.worker.WorkerInvocation(
                        "corr-1",
                        "probe.phone.metadata",
                        Map.of(),
                        Map.of()));

        assertEquals("corr-1", processed.resultCorrelationRef());
        assertTrue(processed.result().success());
        assertEquals("ok", processed.result().result());
        assertEquals("worker-1", context.reporter().workerId());
    }

    @Test
    void usesProvidedExecutorWhenConfigured() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            WorkerRuntimeContext context = newContext(definitionBuilder().build(),
                    new WorkerRuntimeOptions(WorkerRuntimeListener.NOOP, executor));

            assertSame(executor, context.executor());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void createsDaemonExecutorWithRuntimeThreadPrefix() throws Exception {
        WorkerRuntimeContext context = newContext(definitionBuilder().build(), null);
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<Boolean> daemon = new AtomicReference<>();

        context.executor().execute(() -> {
            threadName.set(Thread.currentThread().getName());
            daemon.set(Thread.currentThread().isDaemon());
            ran.countDown();
        });

        assertTrue(ran.await(2, TimeUnit.SECONDS));
        assertEquals("test-runtime-worker-1-1", threadName.get());
        assertTrue(daemon.get());
        context.executor().shutdownNow();
    }

    private static WorkerRuntimeContext newContext(WorkerRuntimeDefinition definition, WorkerRuntimeOptions options) {
        return new WorkerRuntimeContext(
                MassPlatform.builder()
                        .baseUrl("http://localhost:8088")
                        .build()
                        .workers(),
                definition,
                options,
                "test-runtime-");
    }

    private static WorkerRuntimeDefinition.Builder definitionBuilder() {
        return WorkerRuntimeDefinition.builder()
                .workerId("worker-1")
                .workerGroupId("group-1")
                .event("probe.phone.metadata", invocation -> WorkerResult.success("ok"));
    }

}
