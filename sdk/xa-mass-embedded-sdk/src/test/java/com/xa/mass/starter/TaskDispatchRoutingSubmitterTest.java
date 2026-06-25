package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDispatchBatch;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskDispatchRoutingSubmitterTest {

    @Test
    void missingDeliveryTargetEvidenceEmitsOneFailureAndDoesNotOfferHandoff() {
        RecordingFailureHandler failures = new RecordingFailureHandler();
        RecordingHandoff handoff = new RecordingHandoff();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.empty(),
                handoff,
                failures
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, handoff.offers);
        assertSingleFailure(failures, "worker-1", "selected worker has no current adapter mailbox target");
    }

    @Test
    void expiredDeliveryTargetEvidenceEmitsOneFailureAndDoesNotOfferHandoff() {
        RecordingFailureHandler failures = new RecordingFailureHandler();
        RecordingHandoff handoff = new RecordingHandoff();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                        selectedWorkerId,
                        "mailbox-a",
                        1L
                )),
                handoff,
                failures
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, handoff.offers);
        assertSingleFailure(failures, "worker-1", "selected worker has no current adapter mailbox target");
    }

    @Test
    void mismatchedDeliveryTargetEvidenceEmitsOneFailureAndDoesNotOfferHandoff() {
        RecordingFailureHandler failures = new RecordingFailureHandler();
        RecordingHandoff handoff = new RecordingHandoff();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                        "other-worker",
                        "mailbox-a",
                        Long.MAX_VALUE
                )),
                handoff,
                failures
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, handoff.offers);
        assertSingleFailure(failures, "worker-1", "selected worker delivery target does not match assignment worker");
    }

    private static TaskDispatchRoutingSubmitter submitter(
            Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> resolver,
            RecordingHandoff handoff,
            RecordingFailureHandler failures) {
        return new TaskDispatchRoutingSubmitter(
                new TransportAssignedDeliverySubmitter(handoff, failures),
                failures,
                resolver
        );
    }

    private static void assertSingleFailure(RecordingFailureHandler failures,
                                            String selectedWorkerId,
                                            String detail) {
        assertEquals(1, failures.events.size());
        TransportDeliveryFailureEvent event = failures.events.getFirst();
        assertEquals(detail, event.detail());
        assertEquals(selectedWorkerId, event.outcome().getSelectedWorkerId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, event.outcome().getStatus());
    }

    private static TaskDispatchContext context() {
        return new TaskDispatchContext("task-1", "task", "demo", "user", "demo.event", Map.of());
    }

    private static TaskDispatchBinding binding(String messageId, String workerId) {
        return TaskDispatchBinding.workerLevelWithEvidence(
                "task-1",
                messageId,
                "demo.event",
                Map.of(),
                null,
                0,
                "attempt-" + messageId,
                1,
                "lease-" + messageId,
                workerId,
                "batch-1",
                "demo-workers",
                null,
                "test-fixture"
        );
    }

    private static final class RecordingFailureHandler implements TransportDeliveryFailureHandler {
        private final List<TransportDeliveryFailureEvent> events = new ArrayList<>();

        @Override
        public boolean handle(TransportDeliveryFailureEvent event) {
            events.add(event);
            return true;
        }
    }

    private static final class RecordingHandoff implements TransportDispatchHandoff {
        private int offers;

        @Override
        public List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch) {
            offers++;
            return List.of();
        }

        @Override
        public List<DispatchMessage> poll(String adapterMailboxKey, int maxItems, long timeoutMillis) {
            return List.of();
        }

        @Override
        public void shutdown() {
        }
    }
}
