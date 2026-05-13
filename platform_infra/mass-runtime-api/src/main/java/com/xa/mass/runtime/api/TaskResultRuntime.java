package com.xa.mass.runtime.api;

import java.util.List;
import java.util.Optional;

public interface TaskResultRuntime {

    StageResult stageCallback(TaskResultCallbackDraft draft);

    boolean discardStagedCallback(String stageId);

    CommitResult commitVisibleFinal(TaskResultFinalDraft finalDraft);

    List<TaskResultRepairCandidate> scanRepairCandidates(int limit);

    BarrierClaim claimLogicalFinalPublish(String taskId, String messageId, long finalSeq);

    void markLogicalFinalPublished(String taskId, String messageId, long finalSeq);

    BarrierClaim claimProgressApply(String taskId, String messageId, long finalSeq);

    void markProgressApplied(String taskId, String messageId, long finalSeq);

    TaskResultWindow readWindow(String taskId, long afterSeq, int limit);

    long countVisibleResults(String taskId);

    Optional<TaskResultRuntimeRow> getVisibleByMessageId(String taskId, String messageId);

    long discardTask(String taskId);

    void shutdown();
}
