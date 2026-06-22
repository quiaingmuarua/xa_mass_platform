package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process non-blocking dispatch handoff.
 */
public final class InMemoryTransportDispatchHandoff implements TransportDispatchHandoff,
        AdapterMailboxConsumerRegistry {

    private static final long DEFAULT_VISIBILITY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    private final Map<String, LinkedBlockingQueue<ClaimedDispatchRoutingBatch>> readyByMailbox = new ConcurrentHashMap<>();
    private final Map<String, InflightClaim> inflightByDeliveryId = new ConcurrentHashMap<>();
    private final Map<String, MailboxConsumerEvidence> mailboxConsumers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int capacity;
    private final long visibilityTimeoutMillis;

    public InMemoryTransportDispatchHandoff(int capacity) {
        this(capacity, DEFAULT_VISIBILITY_TIMEOUT_MILLIS);
    }

    InMemoryTransportDispatchHandoff(int capacity, long visibilityTimeoutMillis) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        if (visibilityTimeoutMillis < 1L) {
            throw new IllegalArgumentException("visibilityTimeoutMillis must be greater than 0");
        }
        this.capacity = capacity;
        this.visibilityTimeoutMillis = visibilityTimeoutMillis;
    }

    @Override
    public List<DispatchOutcome> offer(DispatchRoutingBatch batch) {
        Objects.requireNonNull(batch, "batch");
        reclaimExpiredInflight();
        if (!running.get()) {
            return batch.items().stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "dispatch handoff is stopped"))
                    .toList();
        }
        if (currentEvidence(batch.adapterMailboxKey()) == null) {
            return batch.items().stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "adapter mailbox has no active consumer"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(batch.items().size());
        for (DispatchRoutingItem item : batch.items()) {
            ClaimedDispatchRoutingBatch claimed = new ClaimedDispatchRoutingBatch(
                    new DispatchRoutingBatch(batch.target(), List.of(item)),
                    List.of(new DispatchHandoffReference(batch.adapterMailboxKey(), item.deliveryId()))
            );
            boolean accepted = offerReadyBatch(claimed);
            outcomes.add(DispatchOutcomeFactory.fromItem(
                    item,
                    accepted ? DispatchOutcomeStatus.QUEUED : DispatchOutcomeStatus.BACKPRESSURE,
                    !accepted,
                    accepted ? null : "dispatch handoff queue is full"
            ));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public ClaimedDispatchRoutingBatch poll(String adapterMailboxKey, long timeoutMillis) throws InterruptedException {
        String mailboxKey = normalizeRequired(adapterMailboxKey, "adapterMailboxKey");
        reclaimExpiredInflight();
        if (currentEvidence(mailboxKey) == null) {
            return null;
        }
        if (!running.get() && readyQueueEmpty(mailboxKey)) {
            return null;
        }
        ClaimedDispatchRoutingBatch batch = pollLocalMailboxBatch(mailboxKey, Math.max(0L, timeoutMillis));
        if (batch == null) {
            return null;
        }
        DispatchHandoffReference reference = firstReference(batch);
        if (reference != null) {
            inflightByDeliveryId.put(
                    reference.deliveryId(),
                    new InflightClaim(batch, System.currentTimeMillis() + visibilityTimeoutMillis)
            );
        }
        return batch;
    }

    @Override
    public void complete(ClaimedDispatchRoutingBatch batch, List<DispatchOutcome> outcomes) {
        if (batch == null || batch.references().isEmpty()) {
            return;
        }
        for (DispatchHandoffReference reference : batch.references()) {
            inflightByDeliveryId.remove(reference.deliveryId());
        }
    }

    @Override
    public void shutdown() {
        running.set(false);
        readyByMailbox.clear();
        inflightByDeliveryId.clear();
        mailboxConsumers.clear();
    }

    @Override
    public void publishMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        Objects.requireNonNull(lease, "lease");
        mailboxConsumers.put(lease.adapterMailboxKey(), new MailboxConsumerEvidence(
                lease.consumerId(),
                lease.generation(),
                lease.availableUntilEpochMillis()
        ));
    }

    @Override
    public void removeMailboxConsumerAvailability(AdapterMailboxConsumerAvailability lease) {
        Objects.requireNonNull(lease, "lease");
        mailboxConsumers.computeIfPresent(lease.adapterMailboxKey(),
                (ignored, current) -> lease.consumerId().equals(current.consumerId()) ? null : current);
    }

    private MailboxConsumerEvidence currentEvidence(String adapterMailboxKey) {
        MailboxConsumerEvidence evidence = mailboxConsumers.get(adapterMailboxKey);
        if (evidence == null) {
            return null;
        }
        if (evidence.availableUntilEpochMillis() <= System.currentTimeMillis()) {
            mailboxConsumers.remove(adapterMailboxKey, evidence);
            return null;
        }
        return evidence;
    }

    private boolean offerReadyBatch(ClaimedDispatchRoutingBatch batch) {
        String mailboxKey = batch.adapterMailboxKey();
        LinkedBlockingQueue<ClaimedDispatchRoutingBatch> readyQueue = queueForMailbox(mailboxKey);
        if (readyQueue.size() + inflightClaimsForMailbox(mailboxKey) >= capacity) {
            return false;
        }
        return readyQueue.offer(batch);
    }

    private void reclaimExpiredInflight() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, InflightClaim> entry : inflightByDeliveryId.entrySet()) {
            InflightClaim claim = entry.getValue();
            if (claim.visibilityDeadlineEpochMillis() > now) {
                continue;
            }
            if (inflightByDeliveryId.remove(entry.getKey(), claim)) {
                queueForMailbox(claim.batch().adapterMailboxKey()).offer(claim.batch());
            }
        }
    }

    private ClaimedDispatchRoutingBatch pollLocalMailboxBatch(String adapterMailboxKey,
                                                             long timeoutMillis) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            if (currentEvidence(adapterMailboxKey) == null) {
                return null;
            }
            ClaimedDispatchRoutingBatch batch = queueForMailbox(adapterMailboxKey).poll();
            if (batch != null) {
                return batch;
            }
            if (timeoutMillis <= 0L) {
                return null;
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0L) {
                return null;
            }
            TimeUnit.MILLISECONDS.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 50L));
            timeoutMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
        } while (running.get());
        return null;
    }

    private LinkedBlockingQueue<ClaimedDispatchRoutingBatch> queueForMailbox(String adapterMailboxKey) {
        return readyByMailbox.computeIfAbsent(adapterMailboxKey, ignored -> new LinkedBlockingQueue<>());
    }

    private boolean readyQueueEmpty(String adapterMailboxKey) {
        LinkedBlockingQueue<ClaimedDispatchRoutingBatch> queue = readyByMailbox.get(adapterMailboxKey);
        return queue == null || queue.isEmpty();
    }

    private long inflightClaimsForMailbox(String adapterMailboxKey) {
        long count = 0L;
        for (InflightClaim claim : inflightByDeliveryId.values()) {
            if (adapterMailboxKey.equals(claim.batch().adapterMailboxKey())) {
                count++;
            }
        }
        return count;
    }

    private static DispatchHandoffReference firstReference(ClaimedDispatchRoutingBatch batch) {
        return batch != null && !batch.references().isEmpty() ? batch.references().getFirst() : null;
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    long inflightClaimsForTest() {
        return inflightByDeliveryId.size();
    }

    void expireInflightForTest() {
        long expiredAt = System.currentTimeMillis() - 1L;
        inflightByDeliveryId.replaceAll((ignored, claim) -> new InflightClaim(claim.batch(), expiredAt));
    }

    private record MailboxConsumerEvidence(String consumerId,
                                           long generation,
                                           long availableUntilEpochMillis) {
    }

    private record InflightClaim(ClaimedDispatchRoutingBatch batch,
                                 long visibilityDeadlineEpochMillis) {
    }
}
