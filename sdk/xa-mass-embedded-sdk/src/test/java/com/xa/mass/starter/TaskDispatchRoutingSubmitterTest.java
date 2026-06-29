package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.AdapterMailboxDispatchBatch;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.delivery.TransportAssignedDeliverySubmitter;
import com.xa.mass.transport.runtime.delivery.TransportDispatchHandoff;
import com.xa.mass.worker.runtime.evidence.SelectedWorkerDeliveryTargetEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskDispatchRoutingSubmitterTest {

    @Test
    void missingDeliveryTargetEvidenceDoesNotOfferHandoff() {
        RecordingHandoff handoff = new RecordingHandoff();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.empty(),
                handoff
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, handoff.offers);
    }

    @Test
    void expiredDeliveryTargetEvidenceDoesNotOfferHandoff() {
        RecordingHandoff handoff = new RecordingHandoff();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                        selectedWorkerId,
                        "mailbox-a",
                        1L
                )),
                handoff
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, handoff.offers);
    }

    @Test
    void mismatchedDeliveryTargetEvidenceDoesNotOfferHandoff() {
        RecordingHandoff handoff = new RecordingHandoff();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                        "other-worker",
                        "mailbox-a",
                        Long.MAX_VALUE
                )),
                handoff
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, handoff.offers);
    }

    private static TaskDispatchRoutingSubmitter submitter(
            Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> resolver,
            RecordingHandoff handoff) {
        return new TaskDispatchRoutingSubmitter(
                new TransportAssignedDeliverySubmitter(handoff),
                resolver
        );
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
                null,
                null,
                "test-fixture"
        );
    }

    private static final class RecordingHandoff implements TransportDispatchHandoff {
        private int offers;

        @Override
        public List<DispatchOutcome> offer(AdapterMailboxDispatchBatch batch) {
            return offer(batch.adapterMailboxKey(), batch.items());
        }

        @Override
        public List<DispatchOutcome> offer(String dispatchQueueKey, List<DispatchMessage> items) {
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
