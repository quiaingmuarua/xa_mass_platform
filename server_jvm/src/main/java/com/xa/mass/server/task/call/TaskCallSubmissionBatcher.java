package com.xa.mass.server.task.call;

import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Task group commit for synchronous Item Calls.
 *
 * <p>A call that finds no submission in flight for its Task submits alone, with no
 * timer or added wait. Calls arriving while one is in flight queue behind it; when
 * it completes, the queue head submits the next batch for itself and the calls behind
 * it, then hands the lane on. A batch keeps FIFO order, holds at most 100 Items, never
 * splits one call, and stops before a call whose message IDs repeat an earlier call's,
 * so repeated IDs keep today's sequential semantics. Every call in a batch receives
 * the batch's outcome or failure; each caller interprets its own Items.</p>
 */
final class TaskCallSubmissionBatcher {

    /** The Kernel Task Call submission bound. */
    static final int MAX_BATCH_ITEMS = 100;

    @FunctionalInterface
    interface BatchSubmission {
        BatchOutcome submit(String taskId, List<TaskItem> items, boolean observe);
    }

    /** One batch's Kernel submission, its immediate observations and their read start. */
    record BatchOutcome(
            TaskCallSubmissionResult submission,
            Map<String, TaskItemResult> observed,
            long observationStarted
    ) {
    }

    private final BatchSubmission submission;
    /** A present lane means one batch of that Task is in flight or being handed on. */
    private final ConcurrentHashMap<String, ArrayDeque<PendingCall>> lanes =
            new ConcurrentHashMap<>();

    TaskCallSubmissionBatcher(BatchSubmission submission) {
        this.submission = Objects.requireNonNull(submission, "submission");
    }

    BatchOutcome submit(String taskId, List<TaskItem> items, boolean observe) {
        var call = new PendingCall(List.copyOf(items), observe);
        boolean[] leads = {false};
        lanes.compute(taskId, (ignored, queue) -> {
            ArrayDeque<PendingCall> lane = queue == null ? new ArrayDeque<>() : queue;
            leads[0] = queue == null;
            lane.addLast(call);
            return lane;
        });
        if (!leads[0]) {
            try {
                CompletableFuture.anyOf(call.lead, call.outcome).join();
            } catch (CompletionException ignored) {
                // The outcome carries the failure; read it below.
            }
            if (call.outcome.isDone()) {
                return outcome(call);
            }
        }
        submitNextBatch(taskId);
        return outcome(call);
    }

    /** Calls queued behind the in-flight batch of one Task, for tests. */
    int pendingCalls(String taskId) {
        ArrayDeque<PendingCall> lane = lanes.get(taskId);
        return lane == null ? 0 : lane.size();
    }

    /** Runs by the lane head: submits one batch, completes it and hands the lane on. */
    private void submitNextBatch(String taskId) {
        List<PendingCall> batch = new ArrayList<>();
        lanes.computeIfPresent(taskId, (ignored, lane) -> {
            takeBatch(lane, batch);
            return lane;
        });
        try {
            List<TaskItem> items = new ArrayList<>();
            boolean observe = false;
            for (PendingCall call : batch) {
                items.addAll(call.items);
                observe |= call.observe;
            }
            BatchOutcome outcome = submission.submit(taskId, List.copyOf(items), observe);
            batch.forEach(call -> call.outcome.complete(outcome));
        } catch (Throwable failure) {
            batch.forEach(call -> call.outcome.completeExceptionally(failure));
        } finally {
            PendingCall[] next = {null};
            lanes.computeIfPresent(taskId, (ignored, lane) -> {
                next[0] = lane.peekFirst();
                return lane.isEmpty() ? null : lane;
            });
            if (next[0] != null) {
                next[0].lead.complete(null);
            }
        }
    }

    private static void takeBatch(ArrayDeque<PendingCall> lane, List<PendingCall> batch) {
        PendingCall head = lane.pollFirst();
        batch.add(head);
        int itemCount = head.items.size();
        Set<String> messageIds = new HashSet<>();
        head.items.forEach(item -> messageIds.add(item.messageId()));
        while (!lane.isEmpty()) {
            PendingCall next = lane.peekFirst();
            if (itemCount + next.items.size() > MAX_BATCH_ITEMS
                    || next.items.stream().anyMatch(item -> messageIds.contains(item.messageId()))) {
                return;
            }
            lane.pollFirst();
            batch.add(next);
            itemCount += next.items.size();
            next.items.forEach(item -> messageIds.add(item.messageId()));
        }
    }

    private static BatchOutcome outcome(PendingCall call) {
        try {
            return call.outcome.join();
        } catch (CompletionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw wrapped;
        }
    }

    private static final class PendingCall {
        private final List<TaskItem> items;
        private final boolean observe;
        private final CompletableFuture<Void> lead = new CompletableFuture<>();
        private final CompletableFuture<BatchOutcome> outcome = new CompletableFuture<>();

        private PendingCall(List<TaskItem> items, boolean observe) {
            this.items = items;
            this.observe = observe;
        }
    }
}
