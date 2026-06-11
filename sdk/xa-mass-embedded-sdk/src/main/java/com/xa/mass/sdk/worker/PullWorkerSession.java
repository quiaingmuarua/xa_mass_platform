package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.TaskPullChannel;
import com.xa.mass.transport.channel.TaskPullResult;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.route.TransportRouteOwnerStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SDK-facing pull worker session for crawlers, queue consumers, and other
 * executors that receive work by polling instead of server push.
 *
 * <p>This session exposes {@link TaskDispatchItem} as the worker-facing pull
 * view. Transport runtime still owns packet/envelope assembly and canonical
 * addressing underneath.</p>
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
    private final String transportHint;

    public PullWorkerSession(String workerId,
                             String workerGroupId,
                             String adapterId,
                             String connectionId,
                             TaskPullChannel taskPullChannel,
                             TaskResultIngestChannel taskResultIngestChannel,
                             TransportRouteOwnerStore routeOwnerStore,
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
        String normalizedReason = normalizeReason(reason, "pull-session-connect");
        routeOwnerStore.claimRouteOwner(workerId, adapterId, routeKey, connectionId, normalizedReason);
    }

    public void disconnect() {
        disconnect("pull-session-disconnect");
    }

    public void disconnect(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-disconnect");
        routeOwnerStore.releaseRouteOwner(workerId, adapterId, routeKey, connectionId, normalizedReason);
    }

    public void heartbeat() {
        heartbeat("pull-session-heartbeat");
    }

    public void heartbeat(String reason) {
        String normalizedReason = normalizeReason(reason, "pull-session-heartbeat");
        routeOwnerStore.refreshHeartbeat(workerId, adapterId, routeKey, connectionId, normalizedReason);
    }

    public List<TaskDispatchItem> poll(int maxMessages) {
        return poll(maxMessages, 0L);
    }

    public List<TaskDispatchItem> poll(int maxMessages, long timeoutMillis) {
        return pollResult(maxMessages, timeoutMillis).getDispatchViews();
    }

    public TaskPullResult pollResult(int maxMessages) {
        return pollResult(maxMessages, 0L);
    }

    public TaskPullResult pollResult(int maxMessages, long timeoutMillis) {
        return taskPullChannel.pollTaskMessagesResult(workerId, maxMessages, timeoutMillis);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem, boolean success, String detail) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem, success, detail, null, Map.of());
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        return submitResult(dispatchItem, success, detail, null, output);
    }

    public boolean submitResult(TaskDispatchItem dispatchItem,
                                boolean success,
                                String detail,
                                String errorCode,
                                Map<String, Object> output) {
        Objects.requireNonNull(dispatchItem, "dispatchItem");
        TaskResultReport report = new TaskResultReport(
                dispatchItem.getTaskId(),
                dispatchItem.getMessageId(),
                success,
                detail,
                errorCode,
                output
        );
        return taskResultIngestChannel.ingest(new TransportResultEnvelope(
                adapterId,
                routeKeyForResult(dispatchItem),
                dispatchItem.attemptId(),
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

    private String routeKeyForResult(TaskDispatchItem dispatchItem) {
        if (dispatchItem.routeKey() != null && !dispatchItem.routeKey().isBlank()) {
            return dispatchItem.routeKey();
        }
        return routeKey;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
