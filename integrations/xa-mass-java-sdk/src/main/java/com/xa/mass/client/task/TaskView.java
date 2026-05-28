package com.xa.mass.client.task;

import java.util.Map;

public record TaskView(
        String id,
        String taskId,
        String tid,
        String taskName,
        String tenantId,
        String project,
        String userId,
        String contract,
        String status,
        String intakeStatus,
        String terminalReason,
        String holdReason,
        String sourceRef,
        Map<String, Object> sharedConfig,
        TaskExecutionView execution,
        TaskCounters counters,
        TaskTimestamps timestamps
) {
}
