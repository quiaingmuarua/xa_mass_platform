package com.xa.mass.server.taskdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.server.kernelbinding.TaskDispatchWakeCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TaskDispatchWakeBufferTest {

    @Test
    void coalescesPendingTaskIdsAndDropsAtItsBound() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<List<String>> sent = new ArrayList<>();
        TaskDispatchWakeCommands commands = taskIds -> {
            synchronized (sent) {
                sent.add(List.copyOf(taskIds));
            }
            if ("seed".equals(taskIds.getFirst())) {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        TaskDispatchWakeBuffer buffer = new TaskDispatchWakeBuffer(
                commands,
                new TaskRpcProperties(
                        30_000,
                        60_000,
                        10,
                        50,
                        100,
                        250,
                        2,
                        100
                )
        );

        buffer.start();
        try {
            assertThat(buffer.offer("seed")).isTrue();
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(buffer.offer("task-1")).isTrue();
            assertThat(buffer.offer("task-1")).isTrue();
            assertThat(buffer.offer("task-2")).isTrue();
            assertThat(buffer.offer("task-3")).isFalse();
            releaseFirst.countDown();

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < deadline) {
                synchronized (sent) {
                    if (sent.size() >= 2) {
                        break;
                    }
                }
                Thread.onSpinWait();
            }
            synchronized (sent) {
                assertThat(sent).containsExactly(
                        List.of("seed"),
                        List.of("task-1", "task-2")
                );
            }
        } finally {
            releaseFirst.countDown();
            buffer.stop();
        }
    }
}
