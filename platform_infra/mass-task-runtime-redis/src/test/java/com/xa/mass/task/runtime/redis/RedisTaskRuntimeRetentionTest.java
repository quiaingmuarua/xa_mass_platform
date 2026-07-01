package com.xa.mass.task.runtime.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.ClaimLeasePolicy;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.ResultFinalityPolicySnapshot;
import com.xa.mass.task.runtime.RetryMode;
import com.xa.mass.task.runtime.RetryPolicySnapshot;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.WorkerReservationEvidence;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisTaskRuntimeRetentionTest {

    private RedisClient redisClient;
    private String redisUri;
    private String namespace;
    private RedisTaskRuntime runtime;

    @AfterEach
    void cleanup() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        if (namespace != null) {
            RedisTaskRuntimeTestSupport.cleanupNamespace(redisUri, namespace);
            namespace = null;
        }
        if (redisClient != null) {
            redisClient.shutdown();
            redisClient = null;
        }
    }

    @Test
    void finalRowsExpireAfterBoundedRetentionWindow() {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace("retention");
        redisClient = RedisTaskRuntimeTestSupport.createClientOrSkip("task runtime redis retention test");
        var clock = new AtomicLong(0L);
        runtime = new RedisTaskRuntime(redisClient, namespace, clock::get);
        var epoch = RuntimeEpoch.of("task-retention", 1L);

        runtime.appendBatch(new AppendBatchCommand(
                "task-retention",
                List.of(new AppendItemInput("message-1", Map.of())),
                new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                epoch));
        var claimed = runtime.claimReady(new ClaimReadyCommand(
                "task-retention",
                List.of(new WorkerReservationEvidence("worker-1", "group-1", "reservation-1", null)),
                new ClaimLeasePolicy(1, 1_000L, 1L, epoch))).claimedItems().getFirst();

        runtime.applyResult(new ResultApplyCommand(
                claimed.taskId(),
                claimed.messageId(),
                claimed.leaseToken(),
                claimed.workerId(),
                claimed.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("ok", true),
                "",
                new RetryPolicySnapshot(RetryMode.FAST_READY, 0, 0L, 1L),
                new ResultFinalityPolicySnapshot(false, true, 10L),
                epoch,
                100L));

        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-retention", 0, 10)).rows()).hasSize(1);

        clock.set(111L);

        assertThat(runtime.readFinalResults(new FinalResultReadRequest("task-retention", 0, 10)).rows()).isEmpty();
    }
}
