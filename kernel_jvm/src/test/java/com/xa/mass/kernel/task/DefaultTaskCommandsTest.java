package com.xa.mass.kernel.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalStatus;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultTaskCommandsTest {

    @Test
    void submissionReleasesBeforeAndAfterAppend() {
        RecordingScore score = new RecordingScore();
        score.releases.add(transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        ));
        score.releases.add(transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.NOOP
        ));
        RecordingRuntime runtime = new RecordingRuntime();
        TaskItem item = item("message-1");

        var result = new DefaultTaskCallItemSubmission(
                score,
                runtime
        ).submit("task-1", List.of(item));

        assertEquals(TaskCallSubmissionStatus.SUBMITTED, result.status());
        assertEquals(2, score.releaseCalls);
        assertEquals(1, runtime.appendCalls);
        assertEquals(
                TaskItemAppendStatus.APPENDED,
                result.itemResults().get("message-1").status()
        );
    }

    @Test
    void secondReleaseFailureKeepsPersistedItemResults() {
        RecordingScore score = new RecordingScore();
        score.releases.add(transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.NOOP
        ));
        score.releases.add(transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        ));
        RecordingRuntime runtime = new RecordingRuntime();

        var result = new DefaultTaskCallItemSubmission(
                score,
                runtime
        ).submit("task-1", List.of(item("message-1")));

        assertEquals(TaskCallSubmissionStatus.RETRYABLE, result.status());
        assertEquals(1, runtime.appendCalls);
        assertEquals(
                TaskItemAppendStatus.APPENDED,
                result.itemResults().get("message-1").status()
        );
    }

    @Test
    void firstReleaseFailureDoesNotAppend() {
        RecordingScore score = new RecordingScore();
        score.releases.add(transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.INVALID
        ));
        RecordingRuntime runtime = new RecordingRuntime();

        var result = new DefaultTaskCallItemSubmission(
                score,
                runtime
        ).submit("task-1", List.of(item("message-1")));

        assertEquals(TaskCallSubmissionStatus.INVALID, result.status());
        assertEquals(0, runtime.appendCalls);
    }

    @Test
    void lifecycleApprovesWithDescriptorPriorityAndClosesPositiveScore() {
        RecordingScore score = new RecordingScore();
        score.state = new TaskScoreBandCore.TaskScoreState(
                "task-1",
                1,
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW,
                100L,
                1
        );
        score.rewriteResult = transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        TaskDescriptor descriptor = descriptor("task-1", 17);
        DefaultTaskLifecycleCommands lifecycle =
                new DefaultTaskLifecycleCommands(
                        score,
                        taskIds -> Map.of("task-1", descriptor)
                );

        assertEquals(
                TaskApprovalStatus.APPROVED,
                lifecycle.approveTask("task-1").status()
        );
        assertEquals(
                TaskScoreBandCore.TaskScoreBand.PRE_REVIEW,
                score.rewriteExpectedBand
        );
        assertEquals(
                TaskScoreBandCore.TaskScoreBand.ADMISSION_VISIBLE,
                score.rewriteTargetBand
        );
        assertEquals(17, score.rewriteTargetSuffix);

        score.state = new TaskScoreBandCore.TaskScoreState(
                "task-1",
                2,
                TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE,
                100L,
                0
        );
        score.closeResult = transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.TRANSITIONED
        );
        assertEquals(
                TaskCloseStatus.CLOSED,
                lifecycle.closeTask("task-1").status()
        );
        assertEquals(
                TaskScoreBandCore.TERMINAL_SCORE_MAX,
                score.closeTarget
        );
    }

    @Test
    void approvalClassifiesCurrentAndConcurrentStates() {
        RecordingScore score = new RecordingScore();
        DefaultTaskLifecycleCommands lifecycle = lifecycle(score);

        score.state = state(TaskScoreBandCore.TaskScoreBand.ADMISSION_VISIBLE);
        assertEquals(
                TaskApprovalStatus.ALREADY_APPROVED,
                lifecycle.approveTask("task-1").status()
        );

        score = new RecordingScore();
        lifecycle = lifecycle(score);
        score.state = state(TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE);
        assertEquals(
                TaskApprovalStatus.ALREADY_APPROVED,
                lifecycle.approveTask("task-1").status()
        );

        score = new RecordingScore();
        lifecycle = lifecycle(score);
        score.state = state(TaskScoreBandCore.TaskScoreBand.TERMINAL);
        assertEquals(
                TaskApprovalStatus.CONFLICT,
                lifecycle.approveTask("task-1").status()
        );

        score = new RecordingScore();
        lifecycle = lifecycle(score);
        score.state = state(TaskScoreBandCore.TaskScoreBand.PRE_REVIEW);
        score.rewriteResult = transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.INVALID
        );
        assertEquals(
                TaskApprovalStatus.INVALID,
                lifecycle.approveTask("task-1").status()
        );

        score = new RecordingScore();
        lifecycle = lifecycle(score);
        score.state = state(TaskScoreBandCore.TaskScoreBand.PRE_REVIEW);
        score.reclassifiedState = state(
                TaskScoreBandCore.TaskScoreBand.ADMISSION_VISIBLE
        );
        score.rewriteResult = transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
        assertEquals(
                TaskApprovalStatus.ALREADY_APPROVED,
                lifecycle.approveTask("task-1").status()
        );
    }

    @Test
    void approvalDistinguishesMissingDescriptorAndOwnerFailure() {
        RecordingScore score = new RecordingScore();
        DefaultTaskLifecycleCommands missing =
                new DefaultTaskLifecycleCommands(
                        score,
                        taskIds -> Map.of()
                );
        assertEquals(
                TaskApprovalStatus.NOT_FOUND,
                missing.approveTask("task-1").status()
        );

        score = new RecordingScore();
        score.getFailure = new IllegalStateException("offline");
        assertEquals(
                TaskApprovalStatus.RETRYABLE,
                lifecycle(score).approveTask("task-1").status()
        );
    }

    @Test
    void closeClassifiesMissingTerminalRejectedStaleAndFailure() {
        RecordingScore score = new RecordingScore();
        assertEquals(
                TaskCloseStatus.NOT_FOUND,
                lifecycle(score).closeTask("task-1").status()
        );

        score = new RecordingScore();
        score.state = state(TaskScoreBandCore.TaskScoreBand.TERMINAL);
        assertEquals(
                TaskCloseStatus.ALREADY_CLOSED,
                lifecycle(score).closeTask("task-1").status()
        );

        score = new RecordingScore();
        score.state = state(TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE);
        score.closeResult = transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.INVALID
        );
        assertEquals(
                TaskCloseStatus.INVALID,
                lifecycle(score).closeTask("task-1").status()
        );

        score = new RecordingScore();
        score.state = state(TaskScoreBandCore.TaskScoreBand.RUNNING_VISIBLE);
        score.closeResult = transition(
                TaskScoreBandCore.TaskScoreTransitionStatus.STALE
        );
        assertEquals(
                TaskCloseStatus.RETRYABLE,
                lifecycle(score).closeTask("task-1").status()
        );

        score = new RecordingScore();
        score.getFailure = new IllegalStateException("offline");
        assertEquals(
                TaskCloseStatus.RETRYABLE,
                lifecycle(score).closeTask("task-1").status()
        );
    }

    private static TaskItem item(String messageId) {
        return new TaskItem(
                messageId,
                "extension.worker.string.md5",
                1,
                Map.of("value", "abc"),
                5,
                10_000L,
                null
        );
    }

    private static TaskDescriptor descriptor(String taskId, int priority) {
        return new TaskDescriptor(
                taskId,
                "workers",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", Integer.toString(priority),
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
    }

    private static DefaultTaskLifecycleCommands lifecycle(
            RecordingScore score
    ) {
        TaskDescriptor descriptor = descriptor("task-1", 17);
        return new DefaultTaskLifecycleCommands(
                score,
                taskIds -> Map.of("task-1", descriptor)
        );
    }

    private static TaskScoreBandCore.TaskScoreState state(
            TaskScoreBandCore.TaskScoreBand band
    ) {
        boolean terminal = band == TaskScoreBandCore.TaskScoreBand.TERMINAL;
        return new TaskScoreBandCore.TaskScoreState(
                "task-1",
                terminal ? TaskScoreBandCore.TERMINAL_SCORE_MAX : 1,
                band,
                terminal ? null : 100L,
                terminal ? null : 0
        );
    }

    private static TaskScoreBandCore.TaskScoreTransitionResult transition(
            TaskScoreBandCore.TaskScoreTransitionStatus status
    ) {
        return new TaskScoreBandCore.TaskScoreTransitionResult(status, null);
    }

    private static final class RecordingScore
            implements TaskScoreBandCore {

        private final ArrayDeque<TaskScoreTransitionResult> releases =
                new ArrayDeque<>();
        private int releaseCalls;
        private TaskScoreState state;
        private TaskScoreState reclassifiedState;
        private int getScoreCalls;
        private RuntimeException getFailure;
        private TaskScoreTransitionResult rewriteResult;
        private TaskScoreBand rewriteExpectedBand;
        private TaskScoreBand rewriteTargetBand;
        private Integer rewriteTargetSuffix;
        private TaskScoreTransitionResult closeResult;
        private long closeTarget;

        @Override
        public Map<String, TaskScoreState> getScoreStates(
                List<String> taskIds
        ) {
            if (getFailure != null) {
                throw getFailure;
            }
            getScoreCalls++;
            TaskScoreState returned = getScoreCalls > 1
                    && reclassifiedState != null
                    ? reclassifiedState
                    : state;
            Map<String, TaskScoreState> states = new LinkedHashMap<>();
            taskIds.forEach(taskId -> states.put(taskId, returned));
            return states;
        }

        @Override
        public List<TaskScoreState> previewScoreStates(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskScoreTransitionResult rewriteScore(
                String taskId,
                TaskScoreBand expectedBand,
                long targetTimeMillis,
                TaskScoreBand targetBand,
                Integer targetSuffix
        ) {
            rewriteExpectedBand = expectedBand;
            rewriteTargetBand = targetBand;
            rewriteTargetSuffix = targetSuffix;
            assertFalse(targetTimeMillis <= 100);
            assertNotNull(rewriteResult);
            return rewriteResult;
        }

        @Override
        public TaskScoreTransitionResult tryReleaseIdlePark(String taskId) {
            releaseCalls++;
            return releases.removeFirst();
        }

        @Override
        public TaskScoreTransitionResult closeScore(
                String taskId,
                long terminalScore
        ) {
            closeTarget = terminalScore;
            return closeResult;
        }

        @Override
        public int countRunningCapacityTasks() {
            throw unsupported();
        }

        @Override
        public List<String> acquireBandTaskCandidates(
                TaskScoreBand band,
                long beforeTimeMillis,
                int limit
        ) {
            throw unsupported();
        }

        @Override
        public List<String> acquireDispatchWorkTasks(int limit) {
            throw unsupported();
        }

        @Override
        public TaskScoreTransitionResult initializeScore(
                String taskId,
                int suffix,
                long leaseDurationMillis
        ) {
            throw unsupported();
        }

        @Override
        public TaskScoreTransitionResult rewriteSameBandTimeMillis(
                String taskId,
                TaskScoreBand expectedBand,
                long targetTimeMillis
        ) {
            throw unsupported();
        }

        @Override
        public TaskScoreTransitionResult parkObservedIdleTask(
                String taskId,
                long observedScore
        ) {
            throw unsupported();
        }

        @Override
        public TaskScoreTransitionResult closeObservedScore(
                String taskId,
                long observedScore,
                long terminalScore
        ) {
            throw unsupported();
        }

        @Override
        public TaskScoreTransitionResult releaseObservedScoreHold(
                String taskId,
                long observedHoldScore
        ) {
            throw unsupported();
        }
    }

    private static final class RecordingRuntime implements TaskRuntime {

        private int appendCalls;

        @Override
        public Map<String, TaskItemAppendResult> appendItems(
                String taskId,
                List<TaskItem> items
        ) {
            appendCalls++;
            Map<String, TaskItemAppendResult> results =
                    new LinkedHashMap<>();
            items.forEach(item -> results.put(
                    item.messageId(),
                    new TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
            ));
            return results;
        }

        @Override
        public TaskCreationResult createTask(TaskDescriptor descriptor) {
            throw unsupported();
        }

        @Override
        public Map<String, TaskItem> loadTaskItems(
                String taskId,
                List<String> messageIds
        ) {
            throw unsupported();
        }

        @Override
        public void storeTaskItemSuccessResults(
                String taskId,
                Map<String, String> results
        ) {
            throw unsupported();
        }

        @Override
        public Map<String, String> loadTaskItemSuccessResults(
                String taskId,
                List<String> messageIds
        ) {
            throw unsupported();
        }

        @Override
        public TaskItemSuccessResultPage scanTaskItemSuccessResults(
                String taskId,
                String cursor,
                int countHint
        ) {
            throw unsupported();
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("not needed by test");
    }
}
