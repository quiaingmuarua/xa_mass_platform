package com.xa.mass.task.runtime;

import java.util.List;

public record RetryPromotionBatch(List<String> messageIds) {

    public RetryPromotionBatch {
        messageIds = TaskRuntimeContractChecks.copyList(messageIds);
    }
}
