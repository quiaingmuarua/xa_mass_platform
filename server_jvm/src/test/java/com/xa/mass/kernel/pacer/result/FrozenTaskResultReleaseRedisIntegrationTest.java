package com.xa.mass.kernel.pacer.result;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.redis.TaskRedisKeys;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.redis.RedisTaskItemScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisTaskScoreBandCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.task.DefaultTaskItemResultEvents;
import com.xa.mass.kernel.task.TaskRuntime.*;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.DefaultWorkerExecutionResultEvents;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.*;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class FrozenTaskResultReleaseRedisIntegrationTest {
    @Test void ignoredSuccessStillReleasesTheActualWorkerLeaseAfterFreezeReleaseAndPurge() {
        var scope = RedisTestScope.create("retention_result_release");
        var client = RedisClient.create(REDIS_URL);
        var clock = new AtomicLong(2_000_000_000_000L);
        try (var connection = client.connect();
             var taskScores = new RedisTaskScoreBandCore(client, scope.keyspace(), clock::get);
             var itemScores = new RedisTaskItemScoreBandCore(client, scope.keyspace(), clock::get);
             var runtime = new RedisTaskRuntime(client, taskScores, itemScores, scope.keyspace(), java.time.Duration.ofDays(7).toMillis(), clock::get);
             var workerScores = new RedisWorkerScoreCore(client, scope.keyspace(), clock::get)) {
            try {
                var policy = new TaskResultBatchPolicy(new DefaultTaskItemResultEvents(runtime, itemScores, 6),
                        new DefaultWorkerExecutionResultEvents(workerScores), clock::get, new ResultContextCodec());
                for (String phase : List.of("FROZEN", "RELEASED", "PURGED")) {
                    String task = "task-" + phase;
                    String worker = "worker-" + phase;
                    runtime.createTask(new TaskDescriptor(task, "g", TaskIdleDisposition.CLOSE_WHEN_IDLE,
                            Map.of("priority", "1", "maxRetryTimes", "2"), List.of(), null, Map.of()));
                    runtime.appendItems(task, List.of(new TaskItem("item", "event", clock.get(), Map.of(),
                            1, clock.get() + 60_000, new WorkerQuery("workerId", worker))));
                    workerScores.initializeRegisteredScores("g", List.of(worker));
                    workerScores.rewriteCurrentPolarityWithinTimeFence("g", Map.of(worker, clock.get()),
                            WorkerScoreCore.WorkerScorePolarity.HOT_ACQUIRE, 0);
                    clock.addAndGet(1_000);
                    var lease = workerScores.acquireCurrentHotScoreLeases("g", List.of(worker), clock.get() + 60_000).get(worker);
                    assertThat(lease.status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
                    long terminal = taskScores.closeScore(task).score();
                    runtime.freezeTaskData(task, terminal);
                    if (!phase.equals("FROZEN")) runtime.releaseTaskPayloads(task, terminal);
                    if (phase.equals("PURGED")) {
                        runtime.beginTaskPurge(task, terminal);
                        runtime.purgeTaskData(task, terminal);
                        runtime.finishTaskPurge(task, terminal);
                    }
                    String context = "{\"taskId\":\"%s\",\"messageId\":\"item\",\"workerId\":\"%s\",\"workerGroupId\":\"g\",\"workerLeaseScore\":%d}"
                            .formatted(task, worker, lease.score());
                    policy.handleSuccess(List.of(DeliveryReport.create(DeliveryEndpoint.WORKER, worker,
                            DeliveryEndpoint.TASK, "event", "200", "late", context)));
                    assertThat(connection.sync().exists(TaskRedisKeys.results(scope.keyspace(), task))).isZero();
                    clock.addAndGet(1_000);
                    assertThat(workerScores.acquireCurrentHotScoreLeases("g", List.of(worker), clock.get() + 60_000)
                            .get(worker).status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
                }
            } finally { scope.cleanup(connection.sync()); }
        } finally { client.shutdown(); }
    }
}
