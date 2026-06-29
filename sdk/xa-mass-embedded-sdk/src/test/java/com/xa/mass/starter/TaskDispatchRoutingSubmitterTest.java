package com.xa.mass.starter;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.starter.AssignedDeliveryBatch;
import com.xa.mass.transport.starter.AssignedDeliverySink;
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
        RecordingSink sink = new RecordingSink();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.empty(),
                sink
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, sink.offers);
    }

    @Test
    void expiredDeliveryTargetEvidenceDoesNotOfferHandoff() {
        RecordingSink sink = new RecordingSink();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                        selectedWorkerId,
                        "mailbox-a",
                        1L
                )),
                sink
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, sink.offers);
    }

    @Test
    void mismatchedDeliveryTargetEvidenceDoesNotOfferHandoff() {
        RecordingSink sink = new RecordingSink();
        TaskDispatchRoutingSubmitter submitter = submitter(
                selectedWorkerId -> Optional.of(new SelectedWorkerDeliveryTargetEvidence(
                        "other-worker",
                        "mailbox-a",
                        Long.MAX_VALUE
                )),
                sink
        );

        submitter.onTaskDispatchBatch(context(), List.of(binding("msg-1", "worker-1")));

        assertEquals(0, sink.offers);
    }

    private static TaskDispatchRoutingSubmitter submitter(
            Function<String, Optional<SelectedWorkerDeliveryTargetEvidence>> resolver,
            RecordingSink sink) {
        return new TaskDispatchRoutingSubmitter(
                sink,
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

    private static final class RecordingSink implements AssignedDeliverySink {
        private int offers;

        @Override
        public List<DispatchOutcome> submit(List<AssignedDeliveryBatch> batches) {
            offers++;
            return List.of();
        }
    }
}
