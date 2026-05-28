package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerSchedulingCandidateTest {

    @Test
    void retainsWorkerAndSchedulingViewWithoutContextIdentity() {
        Worker worker = worker("worker-1");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker),
                WorkerReachabilityState.ONLINE,
                true,
                false
        );

        WorkerSchedulingCandidate candidate = new WorkerSchedulingCandidate(
                TestWorkerCandidateRows.from(worker),
                schedulingView
        );

        assertEquals("worker-1", candidate.getCandidateRow().workerId());
        assertSame(schedulingView, candidate.getSchedulingView());
        assertEquals("worker-1", candidate.getWorkerId());
    }

    @Test
    void retainsWorkerLevelCandidateIdentity() {
        Worker worker = worker("worker-2");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker),
                WorkerReachabilityState.ONLINE,
                true,
                false
        );

        WorkerSchedulingCandidate candidate = new WorkerSchedulingCandidate(
                TestWorkerCandidateRows.from(worker),
                schedulingView
        );

        assertEquals("worker-2", candidate.getCandidateRow().workerId());
        assertEquals("worker-2", candidate.getWorkerId());
        assertSame(schedulingView, candidate.getSchedulingView());
    }

    @Test
    void rejectsMissingRequiredFields() {
        Worker worker = worker("worker-3");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker),
                WorkerReachabilityState.ONLINE,
                true,
                false
        );

        assertThrows(NullPointerException.class, () -> new WorkerSchedulingCandidate(null, schedulingView));
        assertThrows(NullPointerException.class,
                () -> new WorkerSchedulingCandidate(TestWorkerCandidateRows.from(worker), null));
    }

    private Worker worker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        return worker;
    }
}
