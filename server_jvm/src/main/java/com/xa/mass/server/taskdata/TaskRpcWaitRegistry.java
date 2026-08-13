package com.xa.mass.server.taskdata;

import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

@Component
public final class TaskRpcWaitRegistry {

    private final int maxWaiters;
    private final long initialProbeIntervalMillis;
    private final long normalProbeIntervalMillis;
    private final long longProbeIntervalMillis;
    private final DelayQueue<DueItem> dueItems = new DelayQueue<>();
    private final Map<ItemKey, ItemWaitGroup> groups =
            new LinkedHashMap<>();
    private int waiterCount;
    private boolean closed;

    public TaskRpcWaitRegistry(TaskRpcProperties properties) {
        this.maxWaiters = properties.maxWaiters();
        this.initialProbeIntervalMillis =
                properties.initialProbeIntervalMillis();
        this.normalProbeIntervalMillis =
                properties.normalProbeIntervalMillis();
        this.longProbeIntervalMillis =
                properties.longProbeIntervalMillis();
    }

    public Waiter register(
            String taskId,
            String messageId,
            DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred
    ) {
        Waiter waiter;
        synchronized (this) {
            if (closed) {
                throw new ServerException(
                        ServerErrorCode.TASK_DATA_UNAVAILABLE,
                        "taskRpc.register",
                        "RPC wait registry is stopping",
                        null
                );
            }
            if (waiterCount >= maxWaiters) {
                throw new ServerException(
                        ServerErrorCode.TASK_RPC_CAPACITY_EXCEEDED,
                        "taskRpc.register",
                        null,
                        null
                );
            }
            ItemKey key = new ItemKey(taskId, messageId);
            ItemWaitGroup group = groups.computeIfAbsent(
                    key,
                    ItemWaitGroup::new
            );
            waiter = new Waiter(
                    this,
                    key,
                    deferred,
                    System.nanoTime()
            );
            group.waiters.add(waiter);
            waiterCount++;
            if (!group.scheduled && !group.inFlight) {
                schedule(group, 0);
            }
        }
        deferred.onTimeout(waiter::completePending);
        deferred.onError(ignored -> waiter.cancel());
        deferred.onCompletion(waiter::cancel);
        return waiter;
    }

    public ProbeRequest takeDue() throws InterruptedException {
        while (true) {
            DueItem due = dueItems.take();
            synchronized (this) {
                ItemWaitGroup group = groups.get(due.key);
                if (group == null
                        || !group.scheduled
                        || group.generation != due.generation) {
                    continue;
                }
                group.scheduled = false;
                if (group.waiters.isEmpty()) {
                    groups.remove(group.key);
                    continue;
                }
                group.inFlight = true;
                return new ProbeRequest(
                        group.key.taskId,
                        group.key.messageId
                );
            }
        }
    }

    public void completeSuccess(
            String taskId,
            String messageId,
            String payload
    ) {
        List<Waiter> waiters;
        synchronized (this) {
            ItemWaitGroup group = groups.get(
                    new ItemKey(taskId, messageId)
            );
            if (group == null) {
                return;
            }
            waiters = List.copyOf(group.waiters);
        }
        waiters.forEach(waiter -> waiter.completeSuccess(payload));
    }

    public synchronized void finishProbe(
            String taskId,
            String messageId,
            long retryDelayMillis
    ) {
        ItemKey key = new ItemKey(taskId, messageId);
        ItemWaitGroup group = groups.get(key);
        if (group == null) {
            return;
        }
        group.inFlight = false;
        if (group.waiters.isEmpty()) {
            groups.remove(key);
        } else if (!group.scheduled) {
            schedule(
                    group,
                    retryDelayMillis > 0
                            ? retryDelayMillis
                            : intervalForWaiterAgeMillis(
                                    oldestWaiterAgeMillis(group)
                            )
            );
        }
    }

    public void shutdown() {
        List<Waiter> waiters;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            waiters = groups.values().stream()
                    .flatMap(group -> group.waiters.stream())
                    .toList();
        }
        waiters.forEach(Waiter::completePending);
        dueItems.clear();
    }

    long intervalForWaiterAgeMillis(long ageMillis) {
        if (ageMillis <= 1_000) {
            return initialProbeIntervalMillis;
        }
        if (ageMillis <= 5_000) {
            return normalProbeIntervalMillis;
        }
        return longProbeIntervalMillis;
    }

    synchronized int waiterCount() {
        return waiterCount;
    }

    private synchronized void remove(Waiter waiter) {
        ItemWaitGroup group = groups.get(waiter.key);
        if (group == null || !group.waiters.remove(waiter)) {
            return;
        }
        waiterCount--;
        if (group.waiters.isEmpty() && !group.inFlight) {
            groups.remove(group.key);
        }
    }

    private void schedule(ItemWaitGroup group, long delayMillis) {
        group.scheduled = true;
        group.generation++;
        dueItems.offer(new DueItem(
                group.key,
                group.generation,
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(delayMillis)
        ));
    }

    private static long oldestWaiterAgeMillis(ItemWaitGroup group) {
        long oldest = group.waiters.stream()
                .mapToLong(waiter -> waiter.registeredAtNanos)
                .min()
                .orElse(System.nanoTime());
        return Math.max(
                0,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oldest)
        );
    }

    public record ProbeRequest(
            String taskId,
            String messageId
    ) {
    }

    public static final class Waiter {

        private final TaskRpcWaitRegistry registry;
        private final ItemKey key;
        private final DeferredResult<
                ResponseEntity<TaskRpcCallResponse>
                > deferred;
        private final long registeredAtNanos;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Waiter(
                TaskRpcWaitRegistry registry,
                ItemKey key,
                DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred,
                long registeredAtNanos
        ) {
            this.registry = registry;
            this.key = key;
            this.deferred = deferred;
            this.registeredAtNanos = registeredAtNanos;
        }

        public boolean completeSuccess(String payload) {
            return complete(ResponseEntity.ok(
                    TaskRpcCallResponse.succeeded(
                            key.messageId,
                            payload
                    )
            ));
        }

        public boolean completePending() {
            return complete(ResponseEntity.accepted().body(
                    TaskRpcCallResponse.pending(
                            key.messageId
                    )
            ));
        }

        public synchronized void cancel() {
            if (completed.compareAndSet(false, true)) {
                registry.remove(this);
            }
        }

        private synchronized boolean complete(
                ResponseEntity<TaskRpcCallResponse> response
        ) {
            if (completed.get() || !deferred.setResult(response)) {
                return false;
            }
            completed.set(true);
            registry.remove(this);
            return true;
        }
    }

    private record ItemKey(
            String taskId,
            String messageId
    ) {
    }

    private static final class ItemWaitGroup {

        private final ItemKey key;
        private final Set<Waiter> waiters = new LinkedHashSet<>();
        private long generation;
        private boolean scheduled;
        private boolean inFlight;

        private ItemWaitGroup(ItemKey key) {
            this.key = key;
        }
    }

    private static final class DueItem implements Delayed {

        private final ItemKey key;
        private final long generation;
        private final long dueAtNanos;

        private DueItem(
                ItemKey key,
                long generation,
                long dueAtNanos
        ) {
            this.key = key;
            this.generation = generation;
            this.dueAtNanos = dueAtNanos;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(
                    dueAtNanos - System.nanoTime(),
                    TimeUnit.NANOSECONDS
            );
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(
                    dueAtNanos,
                    ((DueItem) other).dueAtNanos
            );
        }
    }
}
