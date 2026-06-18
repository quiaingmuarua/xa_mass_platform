package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.WorkerEndpointSnapshot;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSocketSessionStore {

    private final String adapterId;
    private final Map<String, WebSocketSessionRecord> recordsByWorkerId = new LinkedHashMap<>();
    private final Map<Channel, WebSocketSessionRecord> recordsByChannel = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, WebSocketSessionRecord>> recordsByEndpointAddress = new LinkedHashMap<>();
    private final Set<Channel> retiredChannels = ConcurrentHashMap.newKeySet();

    public WebSocketSessionStore(String adapterId) {
        this.adapterId = requireText(adapterId, "adapterId").toLowerCase(java.util.Locale.ROOT);
    }

    public synchronized BindResult bind(String deliveryBucketId,
                                        String endpointAddress,
                                        String workerId,
                                        Channel channel) {
        String normalizedDeliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
        String normalizedEndpointAddress = requireText(endpointAddress, "endpointAddress");
        String normalizedWorkerId = requireText(workerId, "workerId");
        if (retiredChannels.contains(channel)) {
            return new BindResult(null, null, true, true, 0);
        }
        WebSocketSessionRecord existingChannelRecord = recordsByChannel.get(channel);
        if (existingChannelRecord != null
                && normalizedEndpointAddress.equals(existingChannelRecord.endpointAddress())
                && normalizedWorkerId.equals(existingChannelRecord.workerId())
                && existingChannelRecord.isActive()) {
            return new BindResult(existingChannelRecord, null, true, false, 0);
        }

        WebSocketSessionRecord replacedWorkerRecord = activeRecordForWorker(normalizedWorkerId, channel);
        WebSocketSessionRecord currentRecord = new WebSocketSessionRecord(
                normalizedDeliveryBucketId,
                normalizedEndpointAddress,
                normalizedWorkerId,
                channel
        );

        int activeCountDelta = 0;
        if (existingChannelRecord != null) {
            removeRecord(existingChannelRecord, false);
        } else {
            activeCountDelta++;
        }

        putRecord(currentRecord);

        if (replacedWorkerRecord != null) {
            removeRecord(replacedWorkerRecord, true);
            retiredChannels.add(replacedWorkerRecord.channel());
            activeCountDelta--;
        }
        return new BindResult(currentRecord, replacedWorkerRecord, false, false, activeCountDelta);
    }

    public synchronized RemoveResult remove(Channel channel) {
        WebSocketSessionRecord removed = recordsByChannel.get(channel);
        if (removed == null) {
            boolean retired = retiredChannels.remove(channel);
            return new RemoveResult(null, false, retired, 0);
        }
        removeRecord(removed, true);
        return new RemoveResult(removed, true, false, -1);
    }

    public synchronized List<WebSocketSessionRecord> clear() {
        List<WebSocketSessionRecord> active = activeRecords();
        recordsByWorkerId.clear();
        recordsByChannel.clear();
        recordsByEndpointAddress.clear();
        retiredChannels.clear();
        return active;
    }

    public synchronized WebSocketSessionRecord activeRecordForWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        WebSocketSessionRecord record = recordsByWorkerId.get(normalizedWorkerId);
        return record != null && record.isActive() ? record : null;
    }

    public synchronized boolean hasActiveEndpointAddress(String endpointAddress) {
        return activeRecordForEndpointAddress(endpointAddress) != null;
    }

    public synchronized WebSocketSessionRecord activeRecordForEndpointAddress(String endpointAddress) {
        List<WebSocketSessionRecord> records = activeRecordsForEndpointAddress(endpointAddress);
        return records.isEmpty() ? null : records.getFirst();
    }

    public synchronized List<WebSocketSessionRecord> activeRecordsForEndpointAddress(String endpointAddress) {
        String normalizedEndpointAddress = normalizeNullable(endpointAddress);
        if (normalizedEndpointAddress == null) {
            return List.of();
        }
        LinkedHashMap<String, WebSocketSessionRecord> records = recordsByEndpointAddress.get(normalizedEndpointAddress);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<WebSocketSessionRecord> active = new ArrayList<>();
        for (WebSocketSessionRecord record : records.values()) {
            if (record != null && record.isActive()) {
                active.add(record);
            }
        }
        return List.copyOf(active);
    }

    public synchronized List<WebSocketSessionRecord> activeRecords() {
        List<WebSocketSessionRecord> records = new ArrayList<>();
        for (WebSocketSessionRecord record : recordsByChannel.values()) {
            if (record != null && record.isActive()) {
                records.add(record);
            }
        }
        return List.copyOf(records);
    }

    public synchronized WebSocketSessionRecord recordForChannel(Channel channel) {
        return recordsByChannel.get(channel);
    }

    public synchronized int activeConnectionCount() {
        return recordsByChannel.size();
    }

    public synchronized int routeCount() {
        return recordsByEndpointAddress.size();
    }

    public synchronized List<WorkerEndpointSnapshot> endpointSnapshots() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (WebSocketSessionRecord record : recordsByChannel.values()) {
            snapshots.add(new WorkerEndpointSnapshot(
                    record.endpointAddress(),
                    record.workerId(),
                    record.isActive(),
                    record.sessionHandle(),
                    adapterId
            ));
        }
        return List.copyOf(snapshots);
    }

    private WebSocketSessionRecord activeRecordForWorker(String workerId, Channel excludedChannel) {
        WebSocketSessionRecord record = recordsByWorkerId.get(workerId);
        if (record == null || record.channel() == excludedChannel || !record.isActive()) {
            return null;
        }
        return record;
    }

    private void putRecord(WebSocketSessionRecord record) {
        recordsByWorkerId.put(record.workerId(), record);
        recordsByChannel.put(record.channel(), record);
        recordsByEndpointAddress
                .computeIfAbsent(record.endpointAddress(), ignored -> new LinkedHashMap<>())
                .put(record.workerId(), record);
    }

    private void removeRecord(WebSocketSessionRecord record, boolean removeChannel) {
        if (record == null) {
            return;
        }
        WebSocketSessionRecord currentWorkerRecord = recordsByWorkerId.get(record.workerId());
        if (currentWorkerRecord == record) {
            recordsByWorkerId.remove(record.workerId());
        }
        LinkedHashMap<String, WebSocketSessionRecord> endpointRecords =
                recordsByEndpointAddress.get(record.endpointAddress());
        if (endpointRecords != null) {
            WebSocketSessionRecord currentEndpointRecord = endpointRecords.get(record.workerId());
            if (currentEndpointRecord == record) {
                endpointRecords.remove(record.workerId());
            }
            if (endpointRecords.isEmpty()) {
                recordsByEndpointAddress.remove(record.endpointAddress());
            }
        }
        if (removeChannel) {
            recordsByChannel.remove(record.channel());
        }
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
