package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportAssignedDeliverySubmitterTest {

    @Test
    void handoffBackpressureEmitsOneFailure() {
        FakeHandoff handoff = new FakeHandoff();
        handoff.backpressure = true;
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                failures
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(command("msg-1", "worker-1")));

        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.getFirst().outcome().getSelectedWorkerId());
    }

    @Test
    void handoffFailureIsConvertedToRetryableOutcome() {
        FakeHandoff handoff = new FakeHandoff();
        handoff.failure = new IllegalStateException("redis unavailable");
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                failures
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(command("msg-1", "worker-1")));

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.getFirst().outcome().getSelectedWorkerId());
    }

    @Test
    void groupsCommandsByBucketDerivedQueueKeyOnly() {
        FakeHandoff handoff = new FakeHandoff();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                new RecordingFailureHandler()
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(
                command("msg-1", "worker-1"),
                command("msg-2", "worker-2"),
                command("msg-3", "worker-3")
        ));

        assertEquals(List.of(
                DispatchOutcomeStatus.QUEUED,
                DispatchOutcomeStatus.QUEUED,
                DispatchOutcomeStatus.QUEUED
        ), statuses(outcomes));
        assertEquals(1, handoff.offered.size());
        assertEquals(DeliveryCommandFixtures.queueKey(), handoff.offered.getFirst().deliveryQueueKey());
        assertEquals(List.of("msg-1", "msg-2", "msg-3"), DeliveryCommandFixtures.messages(handoff.offered.getFirst()));
    }

    @Test
    void bucketQueueDerivationDoesNotChangeSelectedWorker() {
        FakeHandoff handoff = new FakeHandoff();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                new RecordingFailureHandler()
        );

        DeliveryCommand command = command("msg-1", "worker-selected");

        submitter.submit(List.of(command));

        DeliveryCommand offered = handoff.offered.getFirst().items().getFirst();
        assertEquals("worker-selected", offered.getSelectedWorkerId());
        assertEquals(DeliveryCommandFixtures.queueKey(), handoff.offered.getFirst().deliveryQueueKey());
    }

    private static DeliveryCommand command(String messageId, String selectedWorkerId) {
        return DeliveryCommandFixtures.command(messageId, selectedWorkerId, null, "route-" + selectedWorkerId);
    }

    private static List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private static final class FakeHandoff implements TransportDeliveryCommandHandoff {
        private final List<DeliveryCommandBatch> offered = new ArrayList<>();
        private boolean backpressure;
        private RuntimeException failure;

        @Override
        public List<DispatchOutcome> offer(DeliveryQueueOffer offer) {
            if (failure != null) {
                throw failure;
            }
            DeliveryCommandBatch batch = new DeliveryCommandBatch(offer.deliveryQueueKey(), offer.commands());
            offered.add(batch);
            if (backpressure) {
                return batch.items().stream()
                        .map(item -> DispatchOutcome.fromCommand(
                                item,
                                DispatchOutcomeStatus.BACKPRESSURE,
                                true,
                                "test backpressure"))
                        .toList();
            }
            return batch.items().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.QUEUED,
                            false,
                            null))
                    .toList();
        }

        @Override
        public DeliveryCommandBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    private static final class RecordingFailureHandler implements TransportDeliveryFailureHandler {
        private final List<TransportDeliveryFailureEvent> events = new ArrayList<>();

        @Override
        public boolean handle(TransportDeliveryFailureEvent event) {
            events.add(event);
            return true;
        }
    }
}
