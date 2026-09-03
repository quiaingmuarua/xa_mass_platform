package com.xa.mass.kernel.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.assignment.TaskRuleMatchDemand.TaskCandidateNeed;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryWorkerMatchQueueTest {

    @Test
    void offerSizeAndConsumeShareOneBoundedQueue() throws Exception {
        WorkerMatchQueue queue = new InMemoryWorkerMatchQueue(1);
        TaskRuleMatchDemand first = demand("candidate-a");

        assertTrue(queue.offer(first));
        assertFalse(queue.offer(demand("candidate-b")));
        assertEquals(1, queue.size());
        assertEquals(first, queue.consume());
        assertEquals(0, queue.size());
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new InMemoryWorkerMatchQueue(0)
        );
    }

    private static TaskRuleMatchDemand demand(String candidateId) {
        return new TaskRuleMatchDemand(
                "group-1",
                List.of(new TaskCandidateNeed(candidateId, 1)),
                Map.of("worker-1", 101L),
                Long.MAX_VALUE
        );
    }
}
