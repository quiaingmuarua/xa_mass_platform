package com.xa.mass.command.event;

import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedMassEventRuntimeTest {

    @Test
    void dispatchesThroughRuntimeExecutor() {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("event-test-", 2);
        try {
            BoundedMassEventRuntime runtime = new BoundedMassEventRuntime(
                    new InMemoryMassEventRuntime(),
                    executor,
                    1_000
            );
            runtime.register(descriptor("platform.test.echo"), (request, principal) ->
                    CoreEventResponse.success(Map.of(
                            "event", request.getEvent(),
                            "virtualThread", Thread.currentThread().isVirtual()
                    ), request.getRequestId()));

            CoreEventResponse response = runtime.dispatch(request("platform.test.echo", "req-1"), null);

            assertTrue(response.isSuccess());
            assertEquals("req-1", response.getRequestId());
            assertEquals(true, ((Map<?, ?>) response.getData()).get("virtualThread"));
            assertEquals(1, executor.getStatistics().getSubmittedTasks());
            assertEquals(1, executor.getStatistics().getCompletedTasks());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void timesOutSlowHandlerAndInterruptsCooperatingTask() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("event-timeout-test-", 1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            BoundedMassEventRuntime runtime = new BoundedMassEventRuntime(
                    new InMemoryMassEventRuntime(),
                    executor,
                    50
            );
            runtime.register(descriptor("platform.test.slow"), (request, principal) -> {
                try {
                    Thread.sleep(5_000);
                    return CoreEventResponse.success(Boolean.TRUE, request.getRequestId());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                    return CoreEventResponse.failure("INTERRUPTED", "handler interrupted", request.getRequestId());
                }
            });

            CoreEventResponse response = runtime.dispatch(request("platform.test.slow", "req-timeout"), null);

            assertFalse(response.isSuccess());
            assertEquals(BoundedMassEventRuntime.EVENT_TIMEOUT, response.getCode());
            assertEquals("req-timeout", response.getRequestId());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void returnsRejectedWhenExecutorAdmissionIsFull() throws Exception {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("event-reject-test-", 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            BoundedMassEventRuntime runtime = new BoundedMassEventRuntime(
                    new InMemoryMassEventRuntime(),
                    executor,
                    1_000
            );
            runtime.register(descriptor("platform.test.rejected"), (request, principal) ->
                    CoreEventResponse.success(Boolean.TRUE, request.getRequestId()));

            CoreEventResponse response = runtime.dispatch(request("platform.test.rejected", "req-rejected"), null);

            assertFalse(response.isSuccess());
            assertEquals(BoundedMassEventRuntime.EVENT_REJECTED, response.getCode());
            assertEquals("req-rejected", response.getRequestId());
        } finally {
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void convertsHandlerExceptionToFailureResponse() {
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("event-error-test-", 1);
        try {
            BoundedMassEventRuntime runtime = new BoundedMassEventRuntime(
                    new InMemoryMassEventRuntime(),
                    executor,
                    1_000
            );
            runtime.register(descriptor("platform.test.error"), (request, principal) -> {
                throw new IllegalStateException("boom");
            });

            CoreEventResponse response = runtime.dispatch(request("platform.test.error", "req-error"), null);

            assertFalse(response.isSuccess());
            assertEquals(BoundedMassEventRuntime.EVENT_ERROR, response.getCode());
            assertEquals("boom", response.getMessage());
            assertEquals("req-error", response.getRequestId());
        } finally {
            executor.shutdown();
        }
    }

    private static CoreEventDescriptor descriptor(String event) {
        return CoreEventDescriptor.builder().event(event).enabled(true).build();
    }

    private static CoreEventRequest request(String event, String requestId) {
        return CoreEventRequest.builder().event(event).requestId(requestId).build();
    }
}
