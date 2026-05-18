package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.api.BarrierClaimStatus;
import com.xa.mass.runtime.api.BarrierClaim;
import com.xa.mass.runtime.api.BarrierMarkStatus;
import com.xa.mass.runtime.api.CommitResultStatus;
import com.xa.mass.runtime.api.StageResultStatus;
import com.xa.mass.runtime.api.TaskResultCallbackDraft;
import com.xa.mass.runtime.api.TaskResultRepairKind;
import com.xa.mass.runtime.api.TaskResultFinalDraft;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.api.TaskResultWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class TaskResultRuntimeContractTest {

    protected TaskResultRuntime runtime;

    protected abstract TaskResultRuntime createRuntime();

    protected void destroyRuntime(TaskResultRuntime runtime) {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        runtime = createRuntime();
    }

    @AfterEach
    void tearDown() {
        destroyRuntime(runtime);
    }

    @Test
    void stageCallback_isIdempotentByStageIdentity_andDifferentIdentityDoesNotOverwrite() {
        TaskResultCallbackDraft first = draft("task-1", "msg-1", "digest-1");
        TaskResultCallbackDraft duplicate = draft("task-1", "msg-1", "digest-1");
        TaskResultCallbackDraft secondIdentity = draft("task-1", "msg-1", "digest-2");

        assertThat(runtime.stageCallback(first).status()).isEqualTo(StageResultStatus.STAGED);
        assertThat(runtime.stageCallback(duplicate).status()).isEqualTo(StageResultStatus.DUPLICATE);
        assertThat(runtime.stageCallback(secondIdentity).status()).isEqualTo(StageResultStatus.STAGED);

        assertThat(runtime.scanRepairCandidates(10))
                .filteredOn(candidate -> candidate.kind() == TaskResultRepairKind.MISSING_VISIBLE_FINAL)
                .extracting(candidate -> candidate.draft().identityDigest())
                .containsExactly("digest-1", "digest-2");
    }

    @Test
    void visibleFinal_isIdempotentByTaskAndMessage_andAllocatesTaskLocalSeq() {
        assertThat(runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).status())
                .isEqualTo(CommitResultStatus.COMMITTED);
        assertThat(runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).status())
                .isEqualTo(CommitResultStatus.DUPLICATE);
        assertThat(runtime.commitVisibleFinal(finalDraft("task-1", "msg-2", "FAILED")).row().seq())
                .isEqualTo(2L);
        assertThat(runtime.commitVisibleFinal(finalDraft("task-2", "msg-1", "SUCCESS")).row().seq())
                .isEqualTo(1L);
    }

    @Test
    void readWindow_usesAfterSeqLimitNextSeqAndHasMore() {
        runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS"));
        runtime.commitVisibleFinal(finalDraft("task-1", "msg-2", "SUCCESS"));
        runtime.commitVisibleFinal(finalDraft("task-1", "msg-3", "FAILED"));

        TaskResultWindow first = runtime.readWindow("task-1", 0, 2);
        assertThat(first.items()).extracting(row -> row.messageId()).containsExactly("msg-1", "msg-2");
        assertThat(first.nextAfterSeq()).isEqualTo(2L);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.totalVisible()).isEqualTo(3L);

        TaskResultWindow second = runtime.readWindow("task-1", first.nextAfterSeq(), 2);
        assertThat(second.items()).extracting(row -> row.messageId()).containsExactly("msg-3");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void repairCandidates_areStagedCallbacksMissingVisibleRows() {
        TaskResultCallbackDraft draft = draft("task-1", "msg-1", "digest-1");
        runtime.stageCallback(draft);

        assertThat(runtime.scanRepairCandidates(10))
                .extracting(candidate -> candidate.kind())
                .containsExactly(TaskResultRepairKind.MISSING_VISIBLE_FINAL);
        runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS"));
        assertThat(runtime.scanRepairCandidates(10))
                .extracting(candidate -> candidate.kind())
                .containsExactlyInAnyOrder(
                        TaskResultRepairKind.MISSING_ATTEMPT_CLOSED_PUBLISH,
                        TaskResultRepairKind.MISSING_LOGICAL_FINAL_PUBLISH,
                        TaskResultRepairKind.MISSING_PROGRESS_APPLY
                );
    }

    @Test
    void callbackAndVisibleFinalPreserveOutputEntriesWithNullValues() {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("workerId", "worker-1");
        output.put("title", null);

        TaskResultCallbackDraft callbackDraft = TaskResultCallbackDraft.workerLevel(
                TaskResultCallbackDraft.stageId("task-1", "msg-1", "digest-null-output"),
                "task-1",
                "msg-1",
                true,
                "done",
                null,
                output,
                Instant.parse("2026-05-13T00:00:00Z"),
                "attempt-1",
                "lease-1",
                null,
                "polling",
                "worker-1",
                "digest-null-output",
                "worker-1",
                "batch-1",
                "payload-ref",
                "demo.event",
                0,
                3,
                Instant.parse("2026-05-13T00:00:01Z"),
                Instant.parse("2026-05-13T00:00:00Z")
        );

        assertThat(runtime.stageCallback(callbackDraft).status()).isEqualTo(StageResultStatus.STAGED);

        TaskResultFinalDraft finalDraft = TaskResultFinalDraft.workerLevel(
                "task-1",
                "msg-1",
                "demo.event",
                "SUCCESS",
                "BUSINESS_SUCCESS",
                0,
                3,
                "worker-1",
                "batch-1",
                "attempt-1",
                "payload-ref",
                Instant.parse("2026-05-13T00:00:00Z"),
                Instant.parse("2026-05-13T00:00:01Z"),
                Instant.parse("2026-05-13T00:00:02Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                null,
                null,
                output,
                "stage-msg-1"
        );

        assertThat(runtime.commitVisibleFinal(finalDraft).status()).isEqualTo(CommitResultStatus.COMMITTED);
        assertThat(runtime.getVisibleByMessageId("task-1", "msg-1")).get()
                .extracting(row -> row.output())
                .isEqualTo(output);
    }

    @Test
    void barriers_areIndependentAndIdempotent() {
        long seq = runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).row().seq();
        BarrierClaim attemptClaim = runtime.claimAttemptClosedPublish("task-1", "msg-1", seq);

        assertThat(attemptClaim.status())
                .isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(runtime.claimAttemptClosedPublish("task-1", "msg-1", seq).status())
                .isEqualTo(BarrierClaimStatus.BUSY);
        assertThat(runtime.markAttemptClosedPublished("task-1", "msg-1", seq, attemptClaim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.MARKED);
        assertThat(runtime.claimAttemptClosedPublish("task-1", "msg-1", seq).status())
                .isEqualTo(BarrierClaimStatus.ALREADY_DONE);

        BarrierClaim logicalClaim = runtime.claimLogicalFinalPublish("task-1", "msg-1", seq);

        assertThat(logicalClaim.status())
                .isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(runtime.claimLogicalFinalPublish("task-1", "msg-1", seq).status())
                .isEqualTo(BarrierClaimStatus.BUSY);
        assertThat(runtime.markLogicalFinalPublished("task-1", "msg-1", seq, logicalClaim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.MARKED);
        assertThat(runtime.claimLogicalFinalPublish("task-1", "msg-1", seq).status())
                .isEqualTo(BarrierClaimStatus.ALREADY_DONE);

        BarrierClaim progressClaim = runtime.claimProgressApply("task-1", "msg-1", seq);
        assertThat(progressClaim.status())
                .isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(runtime.markProgressApplied("task-1", "msg-1", seq, progressClaim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.MARKED);
        assertThat(runtime.getVisibleByMessageId("task-1", "msg-1")).get()
                .satisfies(row -> {
                    assertThat(row.attemptClosedPublished()).isTrue();
                    assertThat(row.logicalFinalPublished()).isTrue();
                    assertThat(row.progressApplied()).isTrue();
                });
    }

    @Test
    void staleClaimCanBeStolenAndOldTokenCannotMark() {
        long seq = runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).row().seq();
        BarrierClaim claim = runtime.claimLogicalFinalPublish("task-1", "msg-1", seq);
        assertThat(claim.status()).isEqualTo(BarrierClaimStatus.CLAIMED);
        sleep(35L);
        BarrierClaim replacement = runtime.claimLogicalFinalPublish("task-1", "msg-1", seq);
        assertThat(replacement.status()).isEqualTo(BarrierClaimStatus.CLAIMED);
        assertThat(replacement.claimToken()).isNotEqualTo(claim.claimToken());
        assertThat(runtime.markLogicalFinalPublished("task-1", "msg-1", seq, claim.claimToken()).status())
                .isEqualTo(BarrierMarkStatus.TOKEN_MISMATCH);
    }

    @Test
    void discardStagedCallbacksForMessageRemovesAllVariants() {
        runtime.stageCallback(draft("task-1", "msg-1", "digest-1"));
        runtime.stageCallback(draft("task-1", "msg-1", "digest-2"));
        runtime.stageCallback(draft("task-1", "msg-2", "digest-3"));

        assertThat(runtime.discardStagedCallbacksForMessage("task-1", "msg-1")).isEqualTo(2);
        assertThat(runtime.scanRepairCandidates(10))
                .filteredOn(candidate -> candidate.kind() == TaskResultRepairKind.MISSING_VISIBLE_FINAL)
                .extracting(candidate -> candidate.messageId())
                .containsExactly("msg-2");
    }

    @Test
    void scanRepairCandidatesCleansFullyConvergedVisibleStages() {
        runtime.stageCallback(draft("task-1", "msg-1", "digest-1"));
        runtime.stageCallback(draft("task-1", "msg-1", "digest-2"));
        long seq = runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).row().seq();

        BarrierClaim attemptClaim = runtime.claimAttemptClosedPublish("task-1", "msg-1", seq);
        runtime.markAttemptClosedPublished("task-1", "msg-1", seq, attemptClaim.claimToken());
        BarrierClaim logicalClaim = runtime.claimLogicalFinalPublish("task-1", "msg-1", seq);
        runtime.markLogicalFinalPublished("task-1", "msg-1", seq, logicalClaim.claimToken());
        BarrierClaim progressClaim = runtime.claimProgressApply("task-1", "msg-1", seq);
        runtime.markProgressApplied("task-1", "msg-1", seq, progressClaim.claimToken());

        assertThat(runtime.scanRepairCandidates(10)).isEmpty();
        assertThat(runtime.discardStagedCallbacksForMessage("task-1", "msg-1")).isEqualTo(0);
    }

    @Test
    void discardTask_removesStagedVisibleAndBarriersForOneTask() {
        TaskResultCallbackDraft draft = draft("task-1", "msg-1", "digest-1");
        runtime.stageCallback(draft);
        long seq = runtime.commitVisibleFinal(finalDraft("task-1", "msg-1", "SUCCESS")).row().seq();
        BarrierClaim attemptClaim = runtime.claimAttemptClosedPublish("task-1", "msg-1", seq);
        runtime.markAttemptClosedPublished("task-1", "msg-1", seq, attemptClaim.claimToken());
        BarrierClaim logicalClaim = runtime.claimLogicalFinalPublish("task-1", "msg-1", seq);
        runtime.markLogicalFinalPublished("task-1", "msg-1", seq, logicalClaim.claimToken());
        runtime.commitVisibleFinal(finalDraft("task-2", "msg-1", "SUCCESS"));

        assertThat(runtime.discardTask("task-1")).isGreaterThan(0L);
        assertThat(runtime.readWindow("task-1", 0, 10).items()).isEmpty();
        assertThat(runtime.scanRepairCandidates(10))
                .allMatch(candidate -> !"task-1".equals(candidate.taskId()));
        assertThat(runtime.readWindow("task-2", 0, 10).items()).hasSize(1);
    }

    @Test
    void sequentialReadHighVolumeSmoke_preservesMonotonicCheckpointAndNoDuplicates() {
        String taskId = "task-high-volume";
        int total = 5_000;
        int windowSize = 137;
        for (int i = 1; i <= total; i++) {
            String messageId = "msg-" + i;
            assertThat(runtime.commitVisibleFinal(finalDraft(taskId, messageId, "SUCCESS")).status())
                    .isEqualTo(CommitResultStatus.COMMITTED);
        }

        assertThat(runtime.countVisibleResults(taskId)).isEqualTo(total);

        long afterSeq = 0L;
        int readCount = 0;
        long previousSeq = 0L;
        Set<Long> seenSeqs = new HashSet<>();
        Set<String> seenMessageIds = new HashSet<>();

        while (true) {
            TaskResultWindow window = runtime.readWindow(taskId, afterSeq, windowSize);
            assertThat(window.taskId()).isEqualTo(taskId);
            assertThat(window.totalVisible()).isEqualTo(total);

            if (window.items().isEmpty()) {
                assertThat(window.hasMore()).isFalse();
                break;
            }

            for (int i = 0; i < window.items().size(); i++) {
                long currentSeq = window.items().get(i).seq();
                String currentMessageId = window.items().get(i).messageId();
                assertThat(currentSeq).isGreaterThan(previousSeq);
                assertThat(seenSeqs.add(currentSeq)).isTrue();
                assertThat(seenMessageIds.add(currentMessageId)).isTrue();
                previousSeq = currentSeq;
                readCount++;
            }

            long expectedNextAfterSeq = window.items().get(window.items().size() - 1).seq();
            assertThat(window.nextAfterSeq()).isEqualTo(expectedNextAfterSeq);
            afterSeq = window.nextAfterSeq();

            if (!window.hasMore()) {
                break;
            }
        }

        assertThat(readCount).isEqualTo(total);
        assertThat(seenSeqs).hasSize(total);
        assertThat(seenMessageIds).hasSize(total);
        assertThat(previousSeq).isEqualTo(total);

        TaskResultWindow tail = runtime.readWindow(taskId, afterSeq, windowSize);
        assertThat(tail.items()).isEmpty();
        assertThat(tail.hasMore()).isFalse();
        assertThat(tail.totalVisible()).isEqualTo(total);
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("sleep interrupted", e);
        }
    }

    private TaskResultCallbackDraft draft(String taskId, String messageId, String digest) {
        return TaskResultCallbackDraft.workerLevel(
                TaskResultCallbackDraft.stageId(taskId, messageId, digest),
                taskId,
                messageId,
                true,
                "done",
                null,
                Map.of("ok", true),
                Instant.parse("2026-05-13T00:00:00Z"),
                "attempt-1",
                "lease-1",
                null,
                "polling",
                "worker-1",
                digest,
                "worker-1",
                "batch-1",
                "payload-ref",
                "demo.event",
                0,
                3,
                Instant.parse("2026-05-13T00:00:01Z"),
                Instant.parse("2026-05-13T00:00:00Z")
        );
    }

    private TaskResultFinalDraft finalDraft(String taskId, String messageId, String status) {
        return TaskResultFinalDraft.workerLevel(
                taskId,
                messageId,
                "demo.event",
                status,
                "SUCCESS".equals(status) ? "BUSINESS_SUCCESS" : "RETRY_EXHAUSTED",
                0,
                3,
                "worker-1",
                "batch-1",
                "attempt-1",
                "payload-ref",
                Instant.parse("2026-05-13T00:00:00Z"),
                Instant.parse("2026-05-13T00:00:01Z"),
                Instant.parse("2026-05-13T00:00:02Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                Instant.parse("2026-05-13T00:00:03Z"),
                "FAILED".equals(status) ? "ERR" : null,
                "FAILED".equals(status) ? "failed" : null,
                Map.of("status", status),
                "stage-" + messageId
        );
    }
}
