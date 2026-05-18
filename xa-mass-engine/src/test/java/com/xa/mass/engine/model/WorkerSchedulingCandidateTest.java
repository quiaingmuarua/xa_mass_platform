package com.xa.mass.engine.model;

import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.WorkerReachabilityState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerSchedulingCandidateTest {

    @Test
    void retainsWorkerAndSchedulingViewWithoutContextIdentity() {
        Worker worker = worker("worker-1");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE,
                true,
                false
        );

        WorkerSchedulingCandidate candidate = new WorkerSchedulingCandidate(worker, schedulingView);

        assertSame(worker, candidate.getWorker());
        assertSame(schedulingView, candidate.getSchedulingView());
        assertEquals("worker-1", candidate.getWorkerId());
    }

    @Test
    void retainsWorkerLevelCandidateIdentity() {
        Worker worker = worker("worker-2");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE,
                true,
                false
        );

        WorkerSchedulingCandidate candidate = new WorkerSchedulingCandidate(worker, schedulingView);

        assertSame(worker, candidate.getWorker());
        assertEquals("worker-2", candidate.getWorkerId());
        assertSame(schedulingView, candidate.getSchedulingView());
    }

    @Test
    void rejectsMissingRequiredFields() {
        Worker worker = worker("worker-3");
        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE,
                true,
                false
        );

        assertThrows(NullPointerException.class, () -> new WorkerSchedulingCandidate(null, schedulingView));
        assertThrows(NullPointerException.class, () -> new WorkerSchedulingCandidate(worker, null));
    }

    private Worker worker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        return worker;
    }
}
