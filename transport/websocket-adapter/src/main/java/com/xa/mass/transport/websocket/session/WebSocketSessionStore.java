package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointSnapshot;
import com.xa.mass.transport.runtime.RouteEndpointIndex;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebSocketSessionStore {

    private final String adapterId;
    private final RouteEndpointIndex<Channel, WebSocketSessionRecord> routeIndex = new RouteEndpointIndex<>();
    private final Set<Channel> retiredChannels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger activeConnectionCount = new AtomicInteger();

    public WebSocketSessionStore(String adapterId) {
        this.adapterId = requireText(adapterId, "adapterId").toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized BindResult bind(String deliveryBucketId,
                                        String endpointAddress,
                                        String workerId,
                                        Channel channel,
                                        ChannelHandlerContext context) {
        String normalizedDeliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        String normalizedEndpointAddress = requireText(endpointAddress, "endpointAddress");
        String normalizedWorkerId = requireText(workerId, "workerId");
        if (retiredChannels.contains(channel)) {
            return new BindResult(null, null, true, true, 0);
        }
        RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> previousForWorker =
                activeEntryForWorker(normalizedWorkerId, channel);
        RouteEndpointIndex.BindResult<Channel, WebSocketSessionRecord> result = routeIndex.bind(
                normalizedEndpointAddress,
                normalizedWorkerId,
                channel,
                new WebSocketSessionRecord(
                        normalizedDeliveryBucketId,
                        normalizedEndpointAddress,
                        normalizedWorkerId,
                        channel,
                        context
                ),
                WebSocketSessionRecord::isActive
        );
        if (result.unchanged()) {
            return new BindResult(result.currentEntry().endpoint(), null, true, false, 0);
        }
        int activeCountDelta = 0;
        if (result.previousEntry() == null || result.previousEntry().handle() != channel) {
            activeConnectionCount.incrementAndGet();
            activeCountDelta++;
        }
        WebSocketSessionRecord replacedWorkerRecord = null;
        if (previousForWorker != null) {
            RouteEndpointIndex.RemoveResult<Channel, WebSocketSessionRecord> removed =
                    routeIndex.removeByHandle(previousForWorker.handle());
            replacedWorkerRecord = removed.removedEntry() != null
                    ? removed.removedEntry().endpoint()
                    : previousForWorker.endpoint();
            activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
            retiredChannels.add(previousForWorker.handle());
            activeCountDelta--;
        }
        return new BindResult(result.currentEntry().endpoint(), replacedWorkerRecord, false, false, activeCountDelta);
    }

    public synchronized RemoveResult remove(Channel channel) {
        RouteEndpointIndex.RemoveResult<Channel, WebSocketSessionRecord> result = routeIndex.removeByHandle(channel);
        if (result.binding() == null) {
            boolean retired = retiredChannels.remove(channel);
            return new RemoveResult(null, false, retired, 0);
        }
        WebSocketSessionRecord removed = result.removedEntry() != null ? result.removedEntry().endpoint() : null;
        int activeCountDelta = 0;
        if (result.removedCurrentRoute()) {
            activeConnectionCount.updateAndGet(current -> Math.max(0, current - 1));
            activeCountDelta--;
        }
        return new RemoveResult(removed, result.removedCurrentRoute(), false, activeCountDelta);
    }

    public synchronized List<WebSocketSessionRecord> clear() {
        List<WebSocketSessionRecord> active = activeRecords();
        routeIndex.clear();
        retiredChannels.clear();
        activeConnectionCount.set(0);
        return active;
    }

    public WebSocketSessionRecord activeRecordForWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        for (RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry : routeIndex.entriesForWorker(normalizedWorkerId)) {
            WebSocketSessionRecord record = entry.endpoint();
            if (record != null && record.isActive()) {
                return record;
            }
        }
        return null;
    }

    public boolean hasActiveEndpointAddress(String endpointAddress) {
        return activeRecordForEndpointAddress(endpointAddress) != null;
    }

    public WebSocketSessionRecord activeRecordForEndpointAddress(String endpointAddress) {
        for (RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry : routeIndex.entriesForRoute(endpointAddress)) {
            WebSocketSessionRecord record = entry.endpoint();
            if (record != null && record.isActive()) {
                return record;
            }
        }
        return null;
    }

    public List<WebSocketSessionRecord> activeRecordsForEndpointAddress(String endpointAddress) {
        List<WebSocketSessionRecord> records = new ArrayList<>();
        for (RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry : routeIndex.entriesForRoute(endpointAddress)) {
            WebSocketSessionRecord record = entry.endpoint();
            if (record != null && record.isActive()) {
                records.add(record);
            }
        }
        return List.copyOf(records);
    }

    public List<WebSocketSessionRecord> activeRecords() {
        List<WebSocketSessionRecord> records = new ArrayList<>();
        for (RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry : routeIndex.entries()) {
            WebSocketSessionRecord record = entry.endpoint();
            if (record != null && record.isActive()) {
                records.add(record);
            }
        }
        return List.copyOf(records);
    }

    public WebSocketSessionRecord recordForChannel(Channel channel) {
        RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry = routeIndex.entryForHandle(channel);
        return entry != null ? entry.endpoint() : null;
    }

    public int activeConnectionCount() {
        return activeConnectionCount.get();
    }

    public int routeCount() {
        return routeIndex.routeCount();
    }

    public List<WorkerEndpointSnapshot> endpointSnapshots() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry : routeIndex.entries()) {
            WebSocketSessionRecord record = entry.endpoint();
            snapshots.add(new WorkerEndpointSnapshot(
                    entry.routeKey(),
                    entry.workerId(),
                    record != null && record.isActive(),
                    record != null ? record.sessionHandle() : null,
                    adapterId
            ));
        }
        return List.copyOf(snapshots);
    }

    private RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> activeEntryForWorker(String workerId,
                                                                                           Channel excludedChannel) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        for (RouteEndpointIndex.Entry<Channel, WebSocketSessionRecord> entry : routeIndex.entriesForWorker(normalizedWorkerId)) {
            if (entry.handle() == excludedChannel || !normalizedWorkerId.equals(entry.workerId())) {
                continue;
            }
            WebSocketSessionRecord record = entry.endpoint();
            if (record != null && record.isActive()) {
                return entry;
            }
        }
        return null;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    public record BindResult(WebSocketSessionRecord currentRecord,
                             WebSocketSessionRecord replacedWorkerRecord,
                             boolean unchanged,
                             boolean ignoredRetiredChannel,
                             int activeCountDelta) {
    }

    public record RemoveResult(WebSocketSessionRecord removedRecord,
                               boolean removedCurrent,
                               boolean retiredChannel,
                               int activeCountDelta) {
    }
}
