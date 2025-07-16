package com.xa.mass.base.eventbus.event.device;

import com.xa.mass.base.eventbus.core.MassEvent;
import com.xa.mass.base.eventbus.core.MassPlatformEventType;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备批量下线事件
 */
public class DeviceOfflineBatchEvent extends MassEvent.BaseMassEvent {
    private final List<String> deviceIds;
    private final String reason;
    private final long offlineDurationMs;

    public DeviceOfflineBatchEvent(List<String> deviceIds, String reason, long offlineDurationMs, String traceId) {
        super(
                "DEVICE_OFFLINE_BATCH",
                MassPlatformEventType.DEVICE_OFFLINE_BATCH,
                String.format("设备批量下线，设备数: %d，原因: %s，下线时长: %dms", deviceIds.size(), reason, offlineDurationMs),
                createMetadata(deviceIds, reason, offlineDurationMs),
                traceId,
                null
        );
        this.deviceIds = deviceIds;
        this.reason = reason;
        this.offlineDurationMs = offlineDurationMs;
    }

    private static Map<String, Object> createMetadata(List<String> deviceIds, String reason, long offlineDurationMs) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("deviceIds", deviceIds);
        metadata.put("reason", reason);
        metadata.put("offlineDurationMs", offlineDurationMs);
        metadata.put("deviceCount", deviceIds.size());
        return Collections.unmodifiableMap(metadata);
    }

    public List<String> getDeviceIds() {
        return deviceIds;
    }

    public String getReason() {
        return reason;
    }

    public long getOfflineDurationMs() {
        return offlineDurationMs;
    }
}
