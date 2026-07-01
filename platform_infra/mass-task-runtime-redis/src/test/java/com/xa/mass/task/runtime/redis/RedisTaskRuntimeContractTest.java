package com.xa.mass.task.runtime.redis;

import com.xa.mass.task.runtime.ActiveLeaseRepairBatch;
import com.xa.mass.task.runtime.ActiveTaskWorkQuery;
import com.xa.mass.task.runtime.ActiveTaskWorkSnapshot;
import com.xa.mass.task.runtime.ActiveWorkQuery;
import com.xa.mass.task.runtime.ActiveWorkSnapshot;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.ClaimReadyCommand;
import com.xa.mass.task.runtime.ClaimReadyOutcome;
import com.xa.mass.task.runtime.DiscardTaskRuntimeCommand;
import com.xa.mass.task.runtime.DiscardTaskRuntimeOutcome;
import com.xa.mass.task.runtime.DiscardTaskWorkCommand;
import com.xa.mass.task.runtime.DiscardTaskWorkOutcome;
import com.xa.mass.task.runtime.FinalResultReadRequest;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.PollActiveLeaseRepairCommand;
import com.xa.mass.task.runtime.ResultApplyCommand;
import com.xa.mass.task.runtime.ResultCorrelationSnapshot;
import com.xa.mass.task.runtime.SchedulerDiscoveryCommand;
import com.xa.mass.task.runtime.SchedulerDiscoveryOutcome;
import com.xa.mass.task.runtime.TaskRuntimePortContractTest;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.UpdateSchedulerEligibilityCommand;
import io.lettuce.core.RedisClient;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;

class RedisTaskRuntimeContractTest extends TaskRuntimePortContractTest {

    private RedisClient redisClient;
    private String redisUri;
    private String namespace;
    private RedisTaskRuntime currentRuntime;

    @Override
    protected TaskRuntimePorts createRuntime() {
        redisUri = RedisTaskRuntimeTestSupport.redisUri();
        namespace = RedisTaskRuntimeTestSupport.namespace("contract");
        redisClient = RedisTaskRuntimeTestSupport.createClientOrSkip("task runtime redis contract test");
        currentRuntime = new RedisTaskRuntime(redisClient, namespace, () -> 0L);
        return new Ports(currentRuntime);
    }

    @AfterEach
    void cleanup() {
        if (currentRuntime != null) {
            currentRuntime.close();
            currentRuntime = null;
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

    private final class Ports implements TaskRuntimePorts {

        private final RedisTaskRuntime delegate;

        private Ports(RedisTaskRuntime delegate) {
            this.delegate = delegate;
        }

        @Override
        public AppendBatchOutcome appendBatch(AppendBatchCommand command) {
            return delegate.appendBatch(command);
        }

        @Override
        public void updateTaskEligibility(UpdateSchedulerEligibilityCommand command) {
            delegate.updateTaskEligibility(command);
        }

        @Override
        public SchedulerDiscoveryOutcome discoverEligibleTasks(SchedulerDiscoveryCommand command) {
            return delegate.discoverEligibleTasks(command);
        }

        @Override
        public void markTaskDirty(String taskId) {
            delegate.markTaskDirty(taskId);
        }

        @Override
        public ClaimReadyOutcome claimReady(ClaimReadyCommand command) {
            return delegate.claimReady(command);
        }

        @Override
        public MessageFinalityOutcome applyResult(ResultApplyCommand command) {
            return delegate.applyResult(command);
        }

        @Override
        public ResultCorrelationSnapshot getResultCorrelation(String taskId, String messageId) {
            return delegate.getResultCorrelation(taskId, messageId);
        }

        @Override
        public ActiveLeaseRepairBatch pollExpiredActiveLeases(PollActiveLeaseRepairCommand command) {
            return delegate.pollExpiredActiveLeases(command);
        }

        @Override
        public ActiveTaskWorkSnapshot getActiveWorkForTask(ActiveTaskWorkQuery query) {
            return delegate.getActiveWorkForTask(query);
        }

        @Override
        public ActiveWorkSnapshot getActiveWorkForWorker(ActiveWorkQuery query) {
            return delegate.getActiveWorkForWorker(query);
        }

        @Override
        public FinalResultWindow readFinalResults(FinalResultReadRequest request) {
            return delegate.readFinalResults(request);
        }

        @Override
        public Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId) {
            return delegate.getFinalResultByMessageId(taskId, messageId);
        }

        @Override
        public TaskRuntimeProgressSnapshot progressSnapshot(String taskId) {
            return delegate.progressSnapshot(taskId);
        }

        @Override
        public DiscardTaskRuntimeOutcome discardTaskRuntime(DiscardTaskRuntimeCommand command) {
            return delegate.discardTaskRuntime(command);
        }

        @Override
        public DiscardTaskWorkOutcome discardTaskWork(DiscardTaskWorkCommand command) {
            return delegate.discardTaskWork(command);
        }
    }
}
