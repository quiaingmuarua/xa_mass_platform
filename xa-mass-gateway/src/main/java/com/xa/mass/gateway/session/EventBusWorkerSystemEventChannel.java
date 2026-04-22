package com.xa.mass.gateway.session;

import com.xa.mass.base.channel.eventbus.core.EventPublisher;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOfflineEvent;
import com.xa.mass.base.channel.eventbus.event.worker.WorkerOnlineEvent;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Current mainline system-event sink backed by the in-process EventBus.
 */
public class EventBusWorkerSystemEventChannel implements WorkerSystemEventChannel {

    @Override
    public void publishWorkerOnline(String workerId, String reason, String traceId) {
        EventPublisher.post(new WorkerOnlineEvent(workerId, reason, traceId));
    }

    @Override
    public void publishWorkerOffline(String workerId, String reason, String traceId) {
        EventPublisher.post(new WorkerOfflineEvent(workerId, reason, traceId));
    }

    @Override
    public void publishWorkerHeartbeat(String workerId, String reason, String traceId) {
        EventPublisher.post(new WorkerOnlineEvent(workerId, reason, traceId));
    }
}
