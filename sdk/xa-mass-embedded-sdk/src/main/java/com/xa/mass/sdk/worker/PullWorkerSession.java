package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import com.xa.mass.transport.route.TransportRouteOwnerStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing pull worker session for crawlers, queue consumers, and other
 * executors that receive work by polling instead of server push.
 */
public class PullWorkerSession {

    private final String workerId;
    private final String workerGroupId;
    private final String adapterId;
    private final String routeKey;
    private final String connectionId;
    private final TaskPullChannel taskPullChannel;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final TransportRouteOwnerStore routeOwnerStore;
    private final WorkerPresenceIngress workerPresenceIngress;
    private final String transportHint;

    public PullWorkerSession(String workerId,
                             String workerGroupId,
                             String adapterId,
                             String connectionId,
                             TaskPullChannel taskPullChannel,
                             TaskResultIngestChannel taskResultIngestChannel,
                             TransportRouteOwnerStore routeOwnerStore,
                             WorkerPresenceIngress workerPresenceIngress,
                             String transportHint) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        this.workerId = workerId.trim();
        this.workerGroupId = requireText(workerGroupId, "workerGroupId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.routeKey = CanonicalWorkerGroupRouteKeyCodec.encode(this.workerGroupId);
        this.connectionId = requireText(connectionId, "connectionId");
        this.taskPullChannel = Objects.requireNonNull(taskPullChannel, "taskPullChannel");
        this.taskResultIngestChannel = Objects.requireNonNull(taskResultIngestChannel, "taskResultIngestChannel");
        this.routeOwnerStore = Objects.requireNonNull(routeOwnerStore, "routeOwnerStore");
        this.workerPresenceIngress = workerPresenceIngress != null
                ? workerPresenceIngress
                : NoopWorkerPresenceIngress.INSTANCE;
        this.transportHint = transportHint;
    }

    public String workerId() {
        return workerId;
    }

    public String workerGroupId() {
        return workerGroupId;
    }

    public String routeKey() {
        return routeKey;
    }

    public String connectionId() {
        return connectionId;
    }

    public String adapterId() {
        return adapterId;
    }

    public String transportHint() {
        return transportHint;
    }

    public void connect() {
        connect("pull-session-connect");
    }

    public void connect(String reason) {
        connectAndClaim(reason);
    }

    public boolean connectAndClaim(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-connect");
        workerPresenceIngress.sessionConnected(WorkerSessionPresenceEvent.connected(
                workerId,
                adapterId,
                routeKey,
                connectionId,
                normalizedReason,
                connectionId
        ));
        return isCurrentOwner(routeOwnerStore.claimRouteOwner(workerId, adapterId, routeKey, connectionId, normalizedReason));
    }

    public void disconnect() {
        disconnect("pull-session-disconnect");
    }

    public void disconnect(String reason) {
        disconnectIfCurrent(reason);
    }

    public boolean disconnectIfCurrent(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-disconnect");
        workerPresenceIngress.sessionDisconnected(WorkerSessionPresenceEvent.disconnected(
                workerId,
                adapterId,
                routeKey,
                connectionId,
                normalizedReason,
                connectionId
        ));
        return isCurrentOwner(routeOwnerStore.releaseRouteOwner(workerId, adapterId, routeKey, connectionId, normalizedReason));
    }

    public void heartbeat() {
        heartbeat("pull-session-heartbeat");
    }

    public void heartbeat(String reason) {
        refreshHeartbeatIfCurrent(reason);
    }

    public boolean refreshHeartbeatIfCurrent(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-heartbeat");
        workerPresenceIngress.sessionHeartbeat(WorkerSessionPresenceEvent.heartbeat(
                workerId,
                adapterId,
                routeKey,
                connectionId,
                normalizedReason,
                connectionId
        ));
        return isCurrentOwner(routeOwnerStore.refreshHeartbeat(workerId, adapterId, routeKey, connectionId, normalizedReason));
    }

    public List<PulledTaskDispatch> poll(int maxMessages) {
        return poll(maxMessages, 0L);
    }

    public List<PulledTaskDispatch> poll(int maxMessages, long timeoutMillis) {
        return pollResult(maxMessages, timeoutMillis).getItems();
    }

    public TaskPullResult pollResult(int maxMessages) {
        return pollResult(maxMessages, 0L);
    }

    public TaskPullResult pollResult(int maxMessages, long timeoutMillis) {
        return taskPullChannel.pollTaskMessagesResult(workerId, maxMessages, timeoutMillis);
    }

    public boolean submitResult(PulledTaskDispatch item, boolean success, String detail) {
        Objects.requireNonNull(item, "item");
        return submitResult(item, success, detail, null, Map.of());
    }

    public boolean submitResult(PulledTaskDispatch item,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(item, "item");
        return submitResult(item, success, detail, null, output);
    }

    public boolean submitResult(PulledTaskDispatch item,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        Objects.requireNonNull(item, "item");
        TaskResultReport report = new TaskResultReport(
                item.getTaskId(),
                item.getMessageId(),
                success,
                detail,
                errorCode,
                output
        );
        return taskResultIngestChannel.ingest(new TransportResultEnvelope(
                adapterId,
                routeKey,
                item.getAttemptId(),
                null,
                null,
                report
        ));
    }

    public boolean submitResult(String taskId,
                                String messageId,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        TaskResultReport report = new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                output
        );
        return taskResultIngestChannel.ingest(TransportResultEnvelope.addressed(
                adapterId,
                routeKey,
                report
        ));
    }

    private String normalizeReason(String reason, String defaultReason) {
        return reason == null || reason.isBlank() ? defaultReason : reason.trim();
    }

    private boolean isCurrentOwner(TransportRouteOwnerRecord owner) {
        return owner != null
                && workerId.equals(owner.getWorkerId())
                && adapterId.equals(owner.getAdapterId())
                && routeKey.equals(owner.getRouteKey())
                && connectionId.equals(owner.getConnectionId());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
