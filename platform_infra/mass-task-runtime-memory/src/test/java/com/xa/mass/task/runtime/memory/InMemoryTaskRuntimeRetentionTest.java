package com.xa.mass.task.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeGate;
import com.xa.mass.task.runtime.RuntimeResultFact;
import com.xa.mass.task.runtime.TaskRuntimeMetaV1;
import com.xa.mass.task.runtime.TaskRuntimeResultPolicyV1;
import com.xa.mass.task.runtime.TaskScoreV1;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InMemoryTaskRuntimeRetentionTest {

    @Test
    void finalRowsExpireAfterBoundedRetentionWindow() {
        var clock = new AtomicLong(0L);
        var runtime = new InMemoryTaskRuntime(clock::get);
        var epoch = RuntimeEpoch.of("task-retention", 1L);

        runtime.putRuntimeMeta(new TaskRuntimeMetaV1(
                "task-retention",
                "task-retention",
                RuntimeGate.OPEN,
                epoch,
                TaskScoreV1.TIME_SCORE_FLOOR,
                0L,
                0L,
                0L,
                new TaskRuntimeResultPolicyV1(RetryMode.FAST_READY, 0, 0L, 1L, false, true, 10L)));
        runtime.appendBacklog(
                "task-retention",
                List.of(new AppendItemInput("message-1", "", Map.of(), null)),
                10);
        var claimed = runtime.claimBacklog(
                        runtime.scoreCandidate("task-retention", "task-retention").orElseThrow(),
                        List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", null)),
                        1,
                        1_000L,
                        0L)
                .claimedItems()
                .getFirst();

        runtime.applyResult(new RuntimeResultFact(
                claimed.taskId(),
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                epoch,
                100L));

        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-retention", 0, 10)).rows()).hasSize(1);

        clock.set(111L);

        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-retention", 0, 10)).rows()).isEmpty();
    }
}
