package com.xa.mass.runtime.api;

import java.util.List;
import java.util.Optional;

public interface TaskResultRuntime {

    StageResult stageCallback(TaskResultCallbackDraft draft);

    boolean discardStagedCallback(String stageId);

    int discardStagedCallbacksForMessage(String taskId, String messageId);

    CommitResult commitVisibleFinal(TaskResultFinalDraft finalDraft);

    List<TaskResultRepairCandidate> scanRepairCandidates(int limit);

    BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq);

    BarrierMarkResult markLogicalFinalPublished(String taskId, String messageId, long finalSeq, String claimToken);

    BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq);

    BarrierMarkResult markProgressApplied(String taskId, String messageId, long finalSeq, String claimToken);

    TaskResultWindow readWindow(String taskId, long afterSeq, int limit);

    long countVisibleResults(String taskId);

    Optional<TaskResultRuntimeRow> getVisibleByMessageId(String taskId, String messageId);

    long discardTask(String taskId);

    void shutdown();
}
