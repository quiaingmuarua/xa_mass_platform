package com.xa.mass.transport.runtime.delivery;

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

        List<DispatchOutcome> outcomes = submitter.submit(List.of(batch(item("msg-1", "worker-1"))));

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

        List<DispatchOutcome> outcomes = submitter.submit(List.of(batch(item("msg-1", "worker-1"))));

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.getFirst().outcome().getSelectedWorkerId());
    }

    @Test
    void submitsOneMailboxBatchWithoutSelectedWorkerRegrouping() {
        FakeHandoff handoff = new FakeHandoff();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                new RecordingFailureHandler()
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(batch(
                item("msg-1", "worker-1"),
                item("msg-2", "worker-2"),
                item("msg-3", "worker-3")
        )));

        assertEquals(List.of(
                DispatchOutcomeStatus.QUEUED,
                DispatchOutcomeStatus.QUEUED,
                DispatchOutcomeStatus.QUEUED
        ), statuses(outcomes));
        assertEquals(1, handoff.offered.size());
        assertEquals(DispatchRoutingFixtures.mailboxKey(), handoff.offered.getFirst().adapterMailboxKey());
        assertEquals(List.of("msg-1", "msg-2", "msg-3"), DispatchRoutingFixtures.messages(handoff.offered.getFirst()));
    }

    @Test
    void adapterMailboxGroupingDoesNotChangeSelectedWorker() {
        FakeHandoff handoff = new FakeHandoff();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                new RecordingFailureHandler()
        );

        submitter.submit(List.of(batch(item("msg-1", "worker-selected"))));

        DispatchRoutingItem offered = handoff.offered.getFirst().items().getFirst();
        assertEquals("worker-selected", offered.selectedWorkerId());
        assertEquals(DispatchRoutingFixtures.mailboxKey(), handoff.offered.getFirst().adapterMailboxKey());
    }

    private static DispatchRoutingItem item(String messageId, String selectedWorkerId) {
        return DispatchRoutingFixtures.item(messageId, selectedWorkerId);
    }

    private static DispatchRoutingBatch batch(DispatchRoutingItem... items) {
        return DispatchRoutingFixtures.batch(items);
    }

    private static List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private static final class FakeHandoff implements TransportDispatchHandoff {
        private final List<DispatchRoutingBatch> offered = new ArrayList<>();
        private boolean backpressure;
        private RuntimeException failure;

        @Override
        public List<DispatchOutcome> offer(DispatchRoutingBatch batch) {
            if (failure != null) {
                throw failure;
            }
            offered.add(batch);
            if (backpressure) {
                return batch.items().stream()
                        .map(item -> DispatchOutcomeFactory.fromItem(
                                item,
                                DispatchOutcomeStatus.BACKPRESSURE,
                                true,
                                "test backpressure"))
                        .toList();
            }
            return batch.items().stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.QUEUED,
                            false,
                            null))
                    .toList();
        }

        @Override
        public ClaimedDispatchRoutingBatch poll(String adapterMailboxKey, long timeoutMillis) {
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
