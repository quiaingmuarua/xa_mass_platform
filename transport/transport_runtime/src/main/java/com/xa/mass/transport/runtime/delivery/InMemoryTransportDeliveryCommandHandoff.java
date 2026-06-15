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
        DeliveryCommandConsumerRegistry {

    private static final long DEFAULT_VISIBILITY_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    private final LinkedBlockingQueue<DeliveryCommandBatch> queue;
    private final Map<String, InflightClaim> inflightByCommandId = new ConcurrentHashMap<>();
    private final Map<String, ConsumerEvidence> consumerByBucketWorker = new ConcurrentHashMap<>();
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
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public List<DispatchOutcome> offer(DeliveryQueueOffer offer) {
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
        List<DispatchOutcome> outcomes = new ArrayList<>(offer.commands().size());
        for (DeliveryCommand command : offer.commands()) {
            ConsumerEvidence evidence = currentEvidence(offer.deliveryQueueKey(), command.getSelectedWorkerId());
            if (evidence == null) {
                outcomes.add(DispatchOutcome.fromCommand(
                        command,
                        DispatchOutcomeStatus.NO_ENDPOINT,
                        true,
                        "selected worker has no assigned-delivery consumer"
                ));
                continue;
            }
            DeliveryCommandBatch batch = new DeliveryCommandBatch(
                    offer.deliveryQueueKey(),
                    List.of(new DeliveryCommandReference(
                            offer.deliveryQueueKey(),
                            command.getCommandId(),
                            evidence.queueConsumerKey(),
                            evidence.adapterId()
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
        if (!running.get() && queue.isEmpty()) {
            return null;
        }
        DeliveryCommandBatch batch = queue.poll(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
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
        queue.clear();
        inflightByCommandId.clear();
        consumerByBucketWorker.clear();
    }

    @Override
    public void claimConsumer(DeliveryCommandConsumerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(claim.deliveryBucketId());
        consumerByBucketWorker.put(
                selectedWorkerConsumerKey(deliveryQueueKey, claim.selectedWorkerId()),
                new ConsumerEvidence(
                        claim.queueConsumerKey(),
                        claim.consumerEvidenceId(),
                        claim.adapterId(),
                        claim.leaseExpireAtEpochMillis()
                )
        );
    }

    @Override
    public void releaseConsumer(DeliveryCommandConsumerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(claim.deliveryBucketId());
        consumerByBucketWorker.computeIfPresent(selectedWorkerConsumerKey(deliveryQueueKey, claim.selectedWorkerId()),
                (ignored, current) -> claim.queueConsumerKey().equals(current.queueConsumerKey())
                        && claim.consumerEvidenceId().equals(current.consumerEvidenceId()) ? null : current);
    }

    private ConsumerEvidence currentEvidence(String deliveryQueueKey, String selectedWorkerId) {
        String key = selectedWorkerConsumerKey(deliveryQueueKey, selectedWorkerId);
        ConsumerEvidence evidence = consumerByBucketWorker.get(key);
        if (evidence == null) {
            return null;
        }
        if (evidence.leaseExpireAtEpochMillis() <= System.currentTimeMillis()) {
            consumerByBucketWorker.remove(key, evidence);
            return null;
        }
        return evidence;
    }

    private static String selectedWorkerConsumerKey(String deliveryQueueKey, String selectedWorkerId) {
        return deliveryQueueKey + "\n" + selectedWorkerId;
    }

    private boolean offerReadyBatch(DeliveryCommandBatch batch) {
        if (queue.size() + inflightByCommandId.size() >= capacity) {
            return false;
        }
        return queue.offer(batch);
    }

    private void reclaimExpiredInflight() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, InflightClaim> entry : inflightByCommandId.entrySet()) {
            InflightClaim claim = entry.getValue();
            if (claim.visibilityDeadlineEpochMillis() > now) {
                continue;
            }
            if (inflightByCommandId.remove(entry.getKey(), claim)) {
                queue.offer(claim.batch());
            }
        }
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

    private record ConsumerEvidence(String queueConsumerKey,
                                    String consumerEvidenceId,
                                    String adapterId,
                                    long leaseExpireAtEpochMillis) {
    }

    private record InflightClaim(DeliveryCommandBatch batch,
                                 long visibilityDeadlineEpochMillis) {
    }
}
