package com.xa.mass.server.task.call;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.task.TaskItemMapper;
import com.xa.mass.server.task.call.TaskCallSubmissionBatcher.BatchOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskCallSubmissionBatcherTest {

    private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
    private final List<Batch> batches = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);

    record Batch(String taskId, List<String> messageIds, boolean observe, Thread thread) {
    }

    @AfterEach
    void stopCallers() {
        releaseFirst.countDown();
        callers.shutdownNow();
    }

    @Test
    void aCallAloneSubmitsDirectlyOnItsOwnThread() {
        var batcher = new TaskCallSubmissionBatcher(this::record);

        BatchOutcome outcome = batcher.submit("task", items("a", "b"), true);

        assertThat(outcome.submission().status()).isEqualTo(TaskCallSubmissionStatus.SUBMITTED);
        assertThat(batches).containsExactly(new Batch("task", List.of("a", "b"), true, Thread.currentThread()));
        assertThat(batcher.pendingCalls("task")).isZero();
    }

    @Test
    void callsArrivingDuringASubmissionShareTheNextBatchSubmittedByItsHead() throws Exception {
        var batcher = new TaskCallSubmissionBatcher(this::blockFirst);
        Future<BatchOutcome> first = callers.submit(() -> batcher.submit("task", items("a"), false));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        List<Future<BatchOutcome>> queued = new ArrayList<>();
        queued.add(callers.submit(() -> batcher.submit("task", items("b"), false)));
        awaitPending(batcher, 1);
        queued.add(callers.submit(() -> batcher.submit("task", items("c", "d"), true)));
        awaitPending(batcher, 2);
        queued.add(callers.submit(() -> batcher.submit("task", items("e"), false)));
        awaitPending(batcher, 3);

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        for (Future<BatchOutcome> call : queued) {
            assertThat(call.get(5, TimeUnit.SECONDS).submission().itemResults()).containsKeys("b", "c", "d", "e");
        }

        assertThat(batches).extracting(Batch::messageIds)
                .containsExactly(List.of("a"), List.of("b", "c", "d", "e"));
        // Any queued call reads observations for the whole batch.
        assertThat(batches.get(1).observe()).isTrue();
        // The first caller hands the lane on instead of submitting for later calls.
        assertThat(batches.get(1).thread()).isNotSameAs(batches.get(0).thread());
        assertThat(batcher.pendingCalls("task")).isZero();
    }

    @Test
    void repeatedMessageIdsAndTheItemBoundStartTheNextBatchInArrivalOrder() throws Exception {
        var batcher = new TaskCallSubmissionBatcher(this::blockFirst);
        Future<?> first = callers.submit(() -> batcher.submit("task", items("head"), false));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        List<String> sixty = IntStream.range(0, 60).mapToObj(i -> "x" + i).toList();
        List<String> fifty = IntStream.range(0, 50).mapToObj(i -> "y" + i).toList();
        List<List<String>> arrivals = List.of(List.of("a"), List.of("a"), sixty, fifty, List.of("z"));
        List<Future<BatchOutcome>> queued = new ArrayList<>();
        for (int i = 0; i < arrivals.size(); i++) {
            List<String> ids = arrivals.get(i);
            queued.add(callers.submit(() -> batcher.submit("task", items(ids.toArray(String[]::new)), false)));
            awaitPending(batcher, i + 1);
        }

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        for (Future<BatchOutcome> call : queued) {
            call.get(5, TimeUnit.SECONDS);
        }

        List<String> second = new ArrayList<>(List.of("a"));
        List<String> third = new ArrayList<>(List.of("a"));
        third.addAll(sixty);
        List<String> fourth = new ArrayList<>(fifty);
        fourth.add("z");
        // A repeated ID waits for the next batch; 1 + 60 + 50 would exceed 100 Items.
        assertThat(batches).extracting(Batch::messageIds)
                .containsExactly(List.of("head"), second, third, fourth);
    }

    @Test
    void aFailedBatchFailsEachOfItsCallsAndTheLaneRecovers() throws Exception {
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var batcher = new TaskCallSubmissionBatcher((taskId, items, observe) -> {
            if (items.getFirst().messageId().equals("head")) {
                return blockFirst(taskId, items, observe);
            }
            if (failing.getAndSet(false)) {
                throw new IllegalStateException("redis unavailable");
            }
            return record(taskId, items, observe);
        });
        Future<?> first = callers.submit(() -> batcher.submit("task", items("head"), false));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<BatchOutcome> b = callers.submit(() -> batcher.submit("task", items("b"), false));
        awaitPending(batcher, 1);
        Future<BatchOutcome> c = callers.submit(() -> batcher.submit("task", items("c"), false));
        awaitPending(batcher, 2);

        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
        for (Future<BatchOutcome> call : List.of(b, c)) {
            assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("redis unavailable");
        }

        assertThat(batcher.submit("task", items("d"), false).submission().itemResults()).containsKey("d");
        assertThat(batcher.pendingCalls("task")).isZero();
    }

    @Test
    void aSubmissionInFlightForOneTaskDoesNotDelayAnotherTask() throws Exception {
        var batcher = new TaskCallSubmissionBatcher(this::blockFirst);
        Future<?> first = callers.submit(() -> batcher.submit("task-a", items("a"), false));
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();

        BatchOutcome other = batcher.submit("task-b", items("b"), false);

        assertThat(other.submission().itemResults()).containsKey("b");
        assertThat(first.isDone()).isFalse();
        releaseFirst.countDown();
        first.get(5, TimeUnit.SECONDS);
    }

    private BatchOutcome blockFirst(String taskId, List<TaskItem> items, boolean observe) {
        if (firstEntered.getCount() > 0) {
            firstEntered.countDown();
            try {
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        return record(taskId, items, observe);
    }

    private BatchOutcome record(String taskId, List<TaskItem> items, boolean observe) {
        List<String> ids = items.stream().map(TaskItem::messageId).toList();
        batches.add(new Batch(taskId, ids, observe, Thread.currentThread()));
        var results = new java.util.LinkedHashMap<String, com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult>();
        ids.forEach(id -> results.put(id, new com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult(
                com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus.APPENDED)));
        return new BatchOutcome(
                new TaskCallSubmissionResult(TaskCallSubmissionStatus.SUBMITTED, results, null),
                Map.of(),
                0
        );
    }

    private static void awaitPending(TaskCallSubmissionBatcher batcher, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (batcher.pendingCalls("task") < expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertThat(batcher.pendingCalls("task")).isEqualTo(expected);
    }

    private static List<TaskItem> items(String... messageIds) {
        var mapper = new TaskItemMapper();
        var query = new WorkerQuery("worker.any", Map.of());
        return java.util.Arrays.stream(messageIds)
                .map(id -> mapper.callItem(new TaskItemRequest(id, "event", Map.of(), 5, 1000L, query), 1_000L, query))
                .toList();
    }
}
