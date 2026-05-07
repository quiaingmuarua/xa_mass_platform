package com.xa.mass.transport.runtime.dispatch;

import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatch;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskDispatchHandoffPumpTest {

    @Test
    void pumpForwardsSubmittedBatchToListener() throws Exception {
        InMemoryTaskDispatchHandoff handoff = new InMemoryTaskDispatchHandoff(4);
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("dispatch-handoff-test-", 2);
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TaskDispatchContext> deliveredTask = new AtomicReference<>();
        AtomicReference<List<TaskDispatchBinding>> deliveredBindings = new AtomicReference<>();
        TaskDispatchBatch batch = batch("task-1", "msg-1");

        TaskDispatchBatchListener listener = (task, dispatchBindings) -> {
            deliveredTask.set(task);
            deliveredBindings.set(dispatchBindings);
            delivered.countDown();
        };

        TaskDispatchHandoffPump pump = new TaskDispatchHandoffPump(handoff, listener, executor);
        try {
            pump.start();
            handoff.submit(batch);

            assertTrue(delivered.await(1, TimeUnit.SECONDS));
            assertEquals(batch.task(), deliveredTask.get());
            assertEquals(batch.dispatchBindings(), deliveredBindings.get());
        } finally {
            pump.stop();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void stopShutsDownHandoffAndDrainLoop() throws Exception {
        InMemoryTaskDispatchHandoff handoff = new InMemoryTaskDispatchHandoff(2);
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("dispatch-handoff-test-", 2);
        TaskDispatchHandoffPump pump = new TaskDispatchHandoffPump(handoff, (task, dispatchBindings) -> {
        }, executor);

        try {
            pump.start();
            pump.stop();

            IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> handoff.submit(batch("task-2", "msg-2"))
            );
            assertEquals("task dispatch handoff is stopped", error.getMessage());
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertNotNull(executor.getStatistics());
        }
    }

    @Test
    void runtimeExceptionFromListenerDoesNotStopDrainLoop() throws Exception {
        InMemoryTaskDispatchHandoff handoff = new InMemoryTaskDispatchHandoff(4);
        VirtualThreadRuntimeTaskExecutor executor = new VirtualThreadRuntimeTaskExecutor("dispatch-handoff-test-", 2);
        CountDownLatch deliveredAfterFailure = new CountDownLatch(1);
        AtomicReference<String> deliveredMessageId = new AtomicReference<>();

        TaskDispatchBatchListener listener = new TaskDispatchBatchListener() {
            private boolean first = true;

            @Override
            public void onTaskDispatchBatch(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings) {
                if (first) {
                    first = false;
                    throw new IllegalStateException("boom");
                }
                deliveredMessageId.set(dispatchBindings.getFirst().messageId());
                deliveredAfterFailure.countDown();
            }
        };

        TaskDispatchHandoffPump pump = new TaskDispatchHandoffPump(handoff, listener, executor);
        try {
            pump.start();
            handoff.submit(batch("task-1", "msg-1"));
            handoff.submit(batch("task-1", "msg-2"));

            assertTrue(deliveredAfterFailure.await(1, TimeUnit.SECONDS));
            assertEquals("msg-2", deliveredMessageId.get());
        } finally {
            pump.stop();
            executor.shutdown();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static TaskDispatchBatch batch(String taskId, String messageId) {
        TaskMsg taskMsg = new TaskMsg();
        taskMsg.setTaskId(taskId);
        taskMsg.setMessageId(messageId);

        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-" + messageId, taskId, messageId, 1);
        attempt.setWorkerId("worker-" + messageId);
        attempt.setBatchId("batch-" + messageId);

        return new TaskDispatchBatch(
                new TaskDispatchContext(
                        taskId,
                        "task-" + taskId,
                        "demo-project",
                        "demo-user",
                        "demo.event",
                        Map.of("routingCode", "us")
                ),
                List.of(new TaskDispatchBinding(taskMsg, attempt))
        );
    }
}
