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
    private final Map<String, Entry> recordsByWorkerId = new LinkedHashMap<>();
    private final Map<Channel, Entry> recordsByChannel = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, Entry>> recordsByEndpointAddress = new LinkedHashMap<>();
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
            return new BindResult(null, null, null, true, true, 0);
        }
        Entry existingChannelEntry = recordsByChannel.get(channel);
        if (existingChannelEntry != null
                && normalizedEndpointAddress.equals(existingChannelEntry.endpointAddress())
                && normalizedWorkerId.equals(existingChannelEntry.workerId())
                && existingChannelEntry.isActive()) {
            return new BindResult(existingChannelEntry.snapshot(), null, null, true, false, 0);
        }

        Entry replacedWorkerEntry = activeEntryForWorker(normalizedWorkerId, channel);
        Entry currentEntry = new Entry(
                normalizedDeliveryBucketId,
                normalizedEndpointAddress,
                normalizedWorkerId,
                channel
        );

        int activeCountDelta = 0;
        if (existingChannelEntry != null) {
            removeEntry(existingChannelEntry, false);
        } else {
            activeCountDelta++;
        }

        putEntry(currentEntry);

        if (replacedWorkerEntry != null) {
            removeEntry(replacedWorkerEntry, true);
            retiredChannels.add(replacedWorkerEntry.channel());
            activeCountDelta--;
        }
        return new BindResult(
                currentEntry.snapshot(),
                snapshotOrNull(replacedWorkerEntry),
                replacedWorkerEntry == null ? null : replacedWorkerEntry.channel(),
                false,
                false,
                activeCountDelta
        );
    }

    public synchronized RemoveResult remove(Channel channel) {
        Entry removed = recordsByChannel.get(channel);
        if (removed == null) {
            boolean retired = retiredChannels.remove(channel);
            return new RemoveResult(null, false, retired, 0);
        }
        removeEntry(removed, true);
        return new RemoveResult(removed.snapshot(), true, false, -1);
    }

    public synchronized List<SessionRef> clear() {
        List<SessionRef> active = activeSessionRefs();
        recordsByWorkerId.clear();
        recordsByChannel.clear();
        recordsByEndpointAddress.clear();
        retiredChannels.clear();
        return active;
    }

    public synchronized Channel activeChannelForWorker(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return null;
        }
        Entry entry = recordsByWorkerId.get(normalizedWorkerId);
        return entry != null && entry.isActive() ? entry.channel() : null;
    }

    public synchronized boolean hasActiveEndpointAddress(String endpointAddress) {
        return activeChannelForEndpointAddress(endpointAddress) != null;
    }

    public synchronized Channel activeChannelForEndpointAddress(String endpointAddress) {
        List<Channel> channels = activeChannelsForEndpointAddress(endpointAddress);
        return channels.isEmpty() ? null : channels.getFirst();
    }

    public synchronized List<Channel> activeChannelsForEndpointAddress(String endpointAddress) {
        String normalizedEndpointAddress = normalizeNullable(endpointAddress);
        if (normalizedEndpointAddress == null) {
            return List.of();
        }
        LinkedHashMap<String, Entry> records = recordsByEndpointAddress.get(normalizedEndpointAddress);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<Channel> active = new ArrayList<>();
        for (Entry entry : records.values()) {
            if (entry != null && entry.isActive()) {
                active.add(entry.channel());
            }
        }
        return List.copyOf(active);
    }

    public synchronized List<SessionSnapshot> activeSessionSnapshots() {
        List<SessionSnapshot> records = new ArrayList<>();
        for (Entry entry : recordsByChannel.values()) {
            if (entry != null && entry.isActive()) {
                records.add(entry.snapshot());
            }
        }
        return List.copyOf(records);
    }

    public synchronized String workerIdForChannel(Channel channel) {
        Entry entry = recordsByChannel.get(channel);
        return entry != null && entry.isActive() ? entry.workerId() : null;
    }

    public synchronized int activeConnectionCount() {
        return recordsByChannel.size();
    }

    public synchronized int routeCount() {
        return recordsByEndpointAddress.size();
    }

    public synchronized List<WorkerEndpointSnapshot> endpointSnapshots() {
        List<WorkerEndpointSnapshot> snapshots = new ArrayList<>();
        for (Entry record : recordsByChannel.values()) {
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

    private List<SessionRef> activeSessionRefs() {
        List<SessionRef> refs = new ArrayList<>();
        for (Entry entry : recordsByChannel.values()) {
            if (entry != null && entry.isActive()) {
                refs.add(new SessionRef(entry.snapshot(), entry.channel()));
            }
        }
        return List.copyOf(refs);
    }

    private Entry activeEntryForWorker(String workerId, Channel excludedChannel) {
        Entry entry = recordsByWorkerId.get(workerId);
        if (entry == null || entry.channel() == excludedChannel || !entry.isActive()) {
            return null;
        }
        return entry;
    }

    private void putEntry(Entry entry) {
        recordsByWorkerId.put(entry.workerId(), entry);
        recordsByChannel.put(entry.channel(), entry);
        recordsByEndpointAddress
                .computeIfAbsent(entry.endpointAddress(), ignored -> new LinkedHashMap<>())
                .put(entry.workerId(), entry);
    }

    private void removeEntry(Entry entry, boolean removeChannel) {
        if (entry == null) {
            return;
        }
        Entry currentWorkerEntry = recordsByWorkerId.get(entry.workerId());
        if (currentWorkerEntry == entry) {
            recordsByWorkerId.remove(entry.workerId());
        }
        LinkedHashMap<String, Entry> endpointRecords =
                recordsByEndpointAddress.get(entry.endpointAddress());
        if (endpointRecords != null) {
            Entry currentEndpointEntry = endpointRecords.get(entry.workerId());
            if (currentEndpointEntry == entry) {
                endpointRecords.remove(entry.workerId());
            }
            if (endpointRecords.isEmpty()) {
                recordsByEndpointAddress.remove(entry.endpointAddress());
            }
        }
        if (removeChannel) {
            recordsByChannel.remove(entry.channel());
        }
    }

    private static SessionSnapshot snapshotOrNull(Entry record) {
        return record == null ? null : record.snapshot();
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

    record BindResult(SessionSnapshot currentSnapshot,
                      SessionSnapshot replacedSnapshot,
                      Channel replacedChannel,
                      boolean unchanged,
                      boolean ignoredRetiredChannel,
                      int activeCountDelta) {
    }

    record RemoveResult(SessionSnapshot removedSnapshot,
                        boolean removedCurrent,
                        boolean retiredChannel,
                        int activeCountDelta) {
    }

    record SessionRef(SessionSnapshot snapshot, Channel channel) {
    }

    record SessionSnapshot(String deliveryBucketId,
                           String endpointAddress,
                           String workerId,
                           String sessionHandle) {

        SessionSnapshot {
            deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
            endpointAddress = requireText(endpointAddress, "endpointAddress");
            workerId = requireText(workerId, "workerId");
            sessionHandle = requireText(sessionHandle, "sessionHandle");
        }
    }

    private record Entry(String deliveryBucketId,
                         String endpointAddress,
                         String workerId,
                         Channel channel) {

        private Entry {
            deliveryBucketId = requireText(deliveryBucketId, "deliveryBucketId");
            endpointAddress = requireText(endpointAddress, "endpointAddress");
            workerId = requireText(workerId, "workerId");
            java.util.Objects.requireNonNull(channel, "channel");
        }

        private String sessionHandle() {
            return channel.id().asShortText();
        }

        private boolean isActive() {
            return channel != null && channel.isActive();
        }

        private SessionSnapshot snapshot() {
            return new SessionSnapshot(
                    deliveryBucketId,
                    endpointAddress,
                    workerId,
                    sessionHandle()
            );
        }
    }
}
