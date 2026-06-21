package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.DeliveryCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process non-blocking delivery command handoff.
 */
public final class InMemoryTransportDeliveryCommandHandoff implements TransportDeliveryCommandHandoff,
        AdapterMailboxConsumerRegistry {

    private static final long DEFAULT_VISIBILITY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    private final Map<String, LinkedBlockingQueue<DeliveryCommandBatch>> readyByMailbox = new ConcurrentHashMap<>();
    private final Map<String, InflightClaim> inflightByCommandId = new ConcurrentHashMap<>();
    private final Map<String, MailboxConsumerEvidence> mailboxConsumers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int capacity;
    private final long visibilityTimeoutMillis;

    public InMemoryTransportDeliveryCommandHandoff(int capacity) {
        this(capacity, DEFAULT_VISIBILITY_TIMEOUT_MILLIS);
    }

    InMemoryTransportDeliveryCommandHandoff(int capacity, long visibilityTimeoutMillis) {
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
    public List<DispatchOutcome> offer(AdapterMailboxDeliveryOffer offer) {
        Objects.requireNonNull(offer, "offer");
        reclaimExpiredInflight();
        if (!running.get()) {
            return offer.commands().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.SHUTDOWN,
                            true,
                            "delivery command handoff is stopped"))
                    .toList();
        }
        if (currentEvidence(offer.adapterMailboxKey()) == null) {
            return offer.commands().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            "adapter mailbox has no active consumer"))
                    .toList();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(offer.commands().size());
        for (DeliveryCommand command : offer.commands()) {
            DeliveryCommandBatch batch = new DeliveryCommandBatch(
                    offer.adapterMailboxKey(),
                    List.of(new DeliveryCommandReference(
                            offer.adapterMailboxKey(),
                            command.getCommandId()
                    )),
                    List.of(command)
            );
            boolean accepted = offerReadyBatch(batch);
            outcomes.add(DispatchOutcome.fromCommand(
                    command,
                    accepted ? DispatchOutcomeStatus.QUEUED : DispatchOutcomeStatus.BACKPRESSURE,
                    !accepted,
                    accepted ? null : "delivery command handoff queue is full"
            ));
        }
        return List.copyOf(outcomes);
    }

    @Override
    public DeliveryCommandBatch poll(long timeoutMillis) throws InterruptedException {
        reclaimExpiredInflight();
        if (!running.get() && readyQueuesEmpty()) {
            return null;
        }
        DeliveryCommandBatch batch = pollLocalMailboxBatch(Math.max(0L, timeoutMillis));
        if (batch == null) {
            return null;
        }
        DeliveryCommandReference reference = firstReference(batch);
        if (reference != null) {
            inflightByCommandId.put(
                    reference.commandId(),
                    new InflightClaim(batch, System.currentTimeMillis() + visibilityTimeoutMillis)
            );
        }
        return batch;
    }

    @Override
    public void complete(DeliveryCommandBatch batch, List<DispatchOutcome> outcomes) {
        if (batch == null || batch.references().isEmpty()) {
            return;
        }
        for (DeliveryCommandReference reference : batch.references()) {
            inflightByCommandId.remove(reference.commandId());
        }
    }

    @Override
    public void shutdown() {
        running.set(false);
        readyByMailbox.clear();
        inflightByCommandId.clear();
        mailboxConsumers.clear();
    }

    @Override
    public void claimMailboxConsumer(AdapterMailboxConsumerLease lease) {
        Objects.requireNonNull(lease, "lease");
        mailboxConsumers.put(lease.adapterMailboxKey(), new MailboxConsumerEvidence(
                lease.consumerId(),
                lease.generation(),
                lease.leaseDeadlineEpochMillis()
        ));
    }

    @Override
    public void releaseMailboxConsumer(AdapterMailboxConsumerLease lease) {
        Objects.requireNonNull(lease, "lease");
        mailboxConsumers.computeIfPresent(lease.adapterMailboxKey(),
                (ignored, current) -> lease.consumerId().equals(current.consumerId()) ? null : current);
    }

    private MailboxConsumerEvidence currentEvidence(String adapterMailboxKey) {
        MailboxConsumerEvidence evidence = mailboxConsumers.get(adapterMailboxKey);
        if (evidence == null) {
            return null;
        }
        if (evidence.leaseDeadlineEpochMillis() <= System.currentTimeMillis()) {
            mailboxConsumers.remove(adapterMailboxKey, evidence);
            return null;
        }
        return evidence;
    }

    private boolean offerReadyBatch(DeliveryCommandBatch batch) {
        String mailboxKey = batch.adapterMailboxKey();
        LinkedBlockingQueue<DeliveryCommandBatch> readyQueue = queueForMailbox(mailboxKey);
        if (readyQueue.size() + inflightClaimsForMailbox(mailboxKey) >= capacity) {
            return false;
        }
        return readyQueue.offer(batch);
    }

    private void reclaimExpiredInflight() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, InflightClaim> entry : inflightByCommandId.entrySet()) {
            InflightClaim claim = entry.getValue();
            if (claim.visibilityDeadlineEpochMillis() > now) {
                continue;
            }
            if (inflightByCommandId.remove(entry.getKey(), claim)) {
                queueForMailbox(claim.batch().adapterMailboxKey()).offer(claim.batch());
            }
        }
    }

    private DeliveryCommandBatch pollLocalMailboxBatch(long timeoutMillis) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            for (String mailboxKey : activeMailboxKeys()) {
                DeliveryCommandBatch batch = queueForMailbox(mailboxKey).poll();
                if (batch != null) {
                    return batch;
                }
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

    private LinkedBlockingQueue<DeliveryCommandBatch> queueForMailbox(String adapterMailboxKey) {
        return readyByMailbox.computeIfAbsent(adapterMailboxKey, ignored -> new LinkedBlockingQueue<>());
    }

    private List<String> activeMailboxKeys() {
        List<String> keys = new ArrayList<>();
        for (String mailboxKey : mailboxConsumers.keySet()) {
            if (currentEvidence(mailboxKey) != null) {
                keys.add(mailboxKey);
            }
        }
        return keys;
    }

    private boolean readyQueuesEmpty() {
        for (LinkedBlockingQueue<DeliveryCommandBatch> queue : readyByMailbox.values()) {
            if (!queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private long inflightClaimsForMailbox(String adapterMailboxKey) {
        long count = 0L;
        for (InflightClaim claim : inflightByCommandId.values()) {
            if (adapterMailboxKey.equals(claim.batch().adapterMailboxKey())) {
                count++;
            }
        }
        return count;
    }

    private static DeliveryCommandReference firstReference(DeliveryCommandBatch batch) {
        return batch != null && !batch.references().isEmpty() ? batch.references().getFirst() : null;
    }

    long inflightClaimsForTest() {
        return inflightByCommandId.size();
    }

    void expireInflightForTest() {
        long expiredAt = System.currentTimeMillis() - 1L;
        inflightByCommandId.replaceAll((ignored, claim) -> new InflightClaim(claim.batch(), expiredAt));
    }

    private record MailboxConsumerEvidence(String consumerId,
                                           long generation,
                                           long leaseDeadlineEpochMillis) {
    }

    private record InflightClaim(DeliveryCommandBatch batch,
                                 long visibilityDeadlineEpochMillis) {
    }
}
