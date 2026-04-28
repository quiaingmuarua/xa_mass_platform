package com.xa.mass.base.channel.eventbus.event.worker;

import com.xa.mass.base.channel.eventbus.core.MassEvent;
import com.xa.mass.base.channel.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class WorkerOfflineEvent extends MassEvent.BaseMassEvent {
    private final String workerId;
    private final String reason;

    public WorkerOfflineEvent(String workerId, String reason, String traceId) {
        super(
                "WORKER_OFFLINE",
                MassPlatformEventType.WORKER_OFFLINE_SINGLE,
                String.format("Worker %s is offline: %s", workerId, reason),
                createMetadata(workerId, reason),
                traceId,
                null
        );
        this.workerId = workerId;
        this.reason = reason;
    }

    private static Map<String, Object> createMetadata(String workerId, String reason) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("workerId", workerId);
        metadata.put("reason", reason);
        return Collections.unmodifiableMap(metadata);
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getReason() {
        return reason;
    }
}
