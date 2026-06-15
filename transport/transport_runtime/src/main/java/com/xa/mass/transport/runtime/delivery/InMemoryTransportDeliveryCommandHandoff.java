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

    private final LinkedBlockingQueue<DeliveryCommandBatch> queue;
    private final Map<String, ConsumerEvidence> consumerByBucketWorker = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    public InMemoryTransportDeliveryCommandHandoff(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be greater than 0");
        }
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @Override
    public List<DispatchOutcome> offer(DeliveryQueueOffer offer) {
        Objects.requireNonNull(offer, "offer");
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
            boolean accepted = queue.offer(batch);
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
        if (!running.get() && queue.isEmpty()) {
            return null;
        }
        return queue.poll(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        running.set(false);
        consumerByBucketWorker.clear();
    }

    @Override
    public void claimConsumer(DeliveryCommandConsumerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(claim.deliveryBucketId());
        consumerByBucketWorker.put(
                bucketWorkerKey(deliveryQueueKey, claim.selectedWorkerId()),
                new ConsumerEvidence(claim.queueConsumerKey(), claim.adapterId(), claim.leaseExpireAtEpochMillis())
        );
    }

    @Override
    public void releaseConsumer(DeliveryCommandConsumerClaim claim) {
        Objects.requireNonNull(claim, "claim");
        String deliveryQueueKey = AssignedDeliveryCommandQueueKey.queueKeyFor(claim.deliveryBucketId());
        consumerByBucketWorker.computeIfPresent(bucketWorkerKey(deliveryQueueKey, claim.selectedWorkerId()),
                (ignored, current) -> claim.queueConsumerKey().equals(current.queueConsumerKey()) ? null : current);
    }

    private ConsumerEvidence currentEvidence(String deliveryQueueKey, String selectedWorkerId) {
        String key = bucketWorkerKey(deliveryQueueKey, selectedWorkerId);
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

    private static String bucketWorkerKey(String deliveryQueueKey, String selectedWorkerId) {
        return deliveryQueueKey + "\n" + selectedWorkerId;
    }

    private record ConsumerEvidence(String queueConsumerKey,
                                    String adapterId,
                                    long leaseExpireAtEpochMillis) {
    }
}
